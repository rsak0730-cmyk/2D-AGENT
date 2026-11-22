package ai.nova.app

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SecureSettings(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6750A4), background = Color(0xFFF7F8FC))) { NovaApp(settings) } }
    }
}

private data class ChatLine(val text: String, val fromUser: Boolean)
private data class ConversationSummary(val id: String, val title: String)
private data class ProviderConfig(val provider: String, val modelId: String, val configured: Boolean, val keySource: String)
private data class ProviderTestResult(val modelsAvailable: Int, val modelIds: List<String>)
private data class WorkforceAgent(val id: String, val identity: String, val name: String, val title: String, val provider: String, val model: String, val instructions: String, val status: String, val specialties: List<String>, val temperature: Double, val contextLength: Int, val permissions: List<String>, val memoryEnabled: Boolean, val priority: Int, val critical: Boolean, val maxDuration: Int)
private data class WorkforceTask(val id: String, val title: String, val state: String, val result: String?, val error: String?, val priority: Int)
private data class OfficeEvent(val type: String, val agentId: String?, val taskId: String?, val createdAt: String, val payload: String)
private data class ProviderConnectionInfo(val id: String, val provider: String, val models: List<String>, val priority: Int, val status: String)
private data class WorkforceSnapshot<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private enum class Section(val title: String) { HOME("Home"), ASSISTANT("Assistant"), HQ("Headquarters"), AGENTS("Agents"), TASKS("Tasks"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovaApp(settings: SecureSettings) {
    val context = LocalContext.current
    var section by remember { mutableStateOf(Section.ASSISTANT) }
    var base by remember { mutableStateOf(settings.get("base")) }
    var token by remember { mutableStateOf(settings.get("token")) }
    var showSettings by remember { mutableStateOf(false) }
    var showProviderSettings by remember { mutableStateOf(false) }
    var providerKey by remember { mutableStateOf("") }
    var providerModel by remember { mutableStateOf("coding-router-preview") }
    var providerModelOptions by remember { mutableStateOf(emptyList<String>()) }
    var providerStatus by remember { mutableStateOf("Not configured") }
    var providerConfigured by remember { mutableStateOf(false) }
    var providerKeySource by remember { mutableStateOf("not_configured") }
    var providerBusy by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    val conversations = remember { mutableStateListOf<ConversationSummary>() }
    val workforceAgents = remember { mutableStateListOf<WorkforceAgent>() }
    val workforceTasks = remember { mutableStateListOf<WorkforceTask>() }
    val officeEvents = remember { mutableStateListOf<OfficeEvent>() }
    val providerConnections = remember { mutableStateListOf<ProviderConnectionInfo>() }
    var workforceBusy by remember { mutableStateOf(false) }
    var workforceNotice by remember { mutableStateOf("Connect a backend to load saved agent/task records.") }
    var usageSummary by remember { mutableStateOf("Usage summary not loaded") }
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskDescription by remember { mutableStateOf("") }
    var providerDialog by remember { mutableStateOf(false) }
    var registryProvider by remember { mutableStateOf("groq") }
    var registryKey by remember { mutableStateOf("") }
    var registryModel by remember { mutableStateOf("") }
    var registryAccountId by remember { mutableStateOf("") }
    var registryEndpoint by remember { mutableStateOf("") }
    var registryStatus by remember { mutableStateOf("") }
    var editingAgent by remember { mutableStateOf<WorkforceAgent?>(null) }
    val chat = remember { mutableStateListOf<ChatLine>() }
    var draft by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect a NOVA backend in Settings to send messages.") }
    var historyStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val openProviderSettings: () -> Unit = {
        providerKey = ""
        showProviderSettings = true
        providerStatus = "Loading provider status…"
        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { fetchProviderConfiguration(base, token) }
                providerModel = config.modelId
                providerConfigured = config.configured
                providerKeySource = config.keySource
                providerStatus = if (config.configured) "Configured · ${config.keySource.replace('_', ' ')}" else "No provider key configured"
            } catch (e: Exception) { providerStatus = e.message ?: "Could not read provider settings" }
        }
    }
    val refreshWorkforce: () -> Unit = {
        if (!workforceBusy) {
            workforceBusy = true
            scope.launch {
                try {
                    val snapshot = withContext(Dispatchers.IO) { loadWorkforce(base, token) }
                    usageSummary = withContext(Dispatchers.IO) { loadResourceOverview(base, token) }
                    workforceAgents.clear(); workforceAgents.addAll(snapshot.first)
                    workforceTasks.clear(); workforceTasks.addAll(snapshot.second)
                    officeEvents.clear(); officeEvents.addAll(snapshot.third)
                    providerConnections.clear(); providerConnections.addAll(snapshot.fourth)
                    workforceNotice = if (snapshot.first.isEmpty()) "No agent roster yet. Seed the 11 distinct identities to begin." else "Live backend snapshot · ${snapshot.first.count { it.status == "working" }} working · ${snapshot.second.count { it.state in listOf("RECEIVED", "PLANNING", "ASSIGNED", "RUNNING") }} active/queued tasks"
                } catch (e: Exception) { workforceNotice = e.message ?: "Could not load workforce data" }
                finally { workforceBusy = false }
            }
        }
    }
    LaunchedEffect(section, base, token) {
        if (section == Section.HQ || section == Section.AGENTS || section == Section.TASKS) {
            while (isActive) { refreshWorkforce(); delay(15000) }
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("NOVA", fontWeight = FontWeight.Bold); Text("Personal AI workspace", style = MaterialTheme.typography.labelSmall) } }, actions = { if (section == Section.ASSISTANT) { IconButton(onClick = { conversationId = null; chat.clear(); status = "New conversation" }) { Icon(Icons.Default.AddComment, "New conversation") }; IconButton(onClick = { showHistory = true; historyStatus = "Loading history…"; scope.launch { try { val loaded = withContext(Dispatchers.IO) { fetchConversations(base, token) }; conversations.clear(); conversations.addAll(loaded); historyStatus = if (loaded.isEmpty()) "No saved conversations yet." else "Select a conversation to continue." } catch (e: Exception) { historyStatus = e.message ?: "Could not load history" } } }) { Icon(Icons.Default.History, "Conversation history") } }; IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") } }) },
        bottomBar = { NavigationBar { Section.values().forEach { item -> NavigationBarItem(selected = section == item, onClick = { section = item }, icon = { Icon(when(item){Section.HOME->Icons.Default.Home;Section.ASSISTANT->Icons.Default.Chat;Section.HQ->Icons.Default.Business;Section.AGENTS->Icons.Default.Groups;Section.TASKS->Icons.Default.Checklist;Section.SETTINGS->Icons.Default.Settings}, item.title) }, label = { Text(item.title) }) } } }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            when (section) {
                Section.ASSISTANT -> {
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Your assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(status, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = openProviderSettings) { Text("AI provider") }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (chat.isEmpty()) item { EmptyState("Ready when you are", "Connect your own NOVA backend and provider. NOVA won't invent an answer if it is offline.") }
                        items(chat) { line -> ChatBubble(line) }
                        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), label = { Text("Message NOVA") }, enabled = !busy, maxLines = 4)
                        Spacer(Modifier.width(8.dp)); IconButton(enabled = !busy && draft.isNotBlank(), onClick = {
                            val text = draft.trim(); draft = ""; chat.add(ChatLine(text, true)); busy = true; status = "Sending to configured backend…"
                            scope.launch {
                                try { val result = withContext(Dispatchers.IO) { sendMessage(base, token, text, conversationId) }; conversationId = result.conversationId; chat.add(ChatLine(result.response, false)); status = "Connected · response from configured provider" }
                                catch (e: Exception) { status = e.message ?: "Request failed" }
                                finally { busy = false }
                            }
                        }) { Icon(Icons.Default.Send, "Send message") }
                    }
                }
                Section.HOME -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Good day", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp)); Text("Your workspace is ready. Connect a backend to enable cloud-backed features."); DashboardCard("Assistant", "Start a conversation", Icons.Default.Chat) { section = Section.ASSISTANT }; DashboardCard("Workspace status", if (token.isBlank()) "Backend not configured" else "Backend configured", Icons.Default.Cloud) { showSettings = true } }
                Section.HQ -> Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Headquarters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onClick = refreshWorkforce) { Text(if (workforceBusy) "Loading…" else "Refresh") } }
                    Text(workforceNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(usageSummary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
                    Text("Real backend activity · ${workforceAgents.count { it.status == "working" }} / ${workforceAgents.size} agents active · ${workforceTasks.count { it.state == "COMPLETED" }} completed", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                    if (officeEvents.isEmpty()) EmptyState("No recorded activity", "Headquarters only reflects persisted task and agent events. No movement or work is simulated.")
                    else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(officeEvents) { event -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(event.type.replace('.', ' ').replace('_', ' '), fontWeight = FontWeight.SemiBold); Text("${event.payload} · ${event.createdAt}", style = MaterialTheme.typography.bodySmall); if (event.taskId != null) Text("Task ${event.taskId.take(8)}", style = MaterialTheme.typography.labelSmall) } } } }
                }
                Section.AGENTS -> Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Agent workforce", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onClick = refreshWorkforce) { Text("Refresh") } }
                    Text(workforceNotice, style = MaterialTheme.typography.bodySmall)
                    if (workforceAgents.isEmpty()) Button(onClick = { workforceBusy = true; scope.launch { try { val roster = withContext(Dispatchers.IO) { seedAgentRoster(base, token) }; workforceAgents.clear(); workforceAgents.addAll(roster); workforceNotice = "11 independent role identities created. Configure each model and prompt before execution." } catch (e: Exception) { workforceNotice = e.message ?: "Could not seed workforce" } finally { workforceBusy = false } } }, enabled = !workforceBusy) { Text("Seed Head Manager + 10 workers") }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) { items(workforceAgents) { agent -> Card(onClick = { editingAgent = agent }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(agent.name, fontWeight = FontWeight.SemiBold); Text("${agent.identity} · ${agent.status} · ${agent.provider}/${agent.model}", style = MaterialTheme.typography.bodySmall); Text(agent.specialties.joinToString(" · "), style = MaterialTheme.typography.labelSmall) }; Icon(Icons.Default.Edit, "Configure ${agent.name}") } } } }
                    Text("Tap an identity to configure its model and instructions. Tools remain disabled unless explicitly granted by the backend.", style = MaterialTheme.typography.bodySmall)
                }
                Section.TASKS -> Column(Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Tasks", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onClick = refreshWorkforce) { Text("Refresh") } }
                    OutlinedTextField(value = newTaskTitle, onValueChange = { newTaskTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("New task") }, singleLine = true)
                    OutlinedTextField(value = newTaskDescription, onValueChange = { newTaskDescription = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Details (optional)") }, maxLines = 2)
                    Button(enabled = newTaskTitle.isNotBlank() && !workforceBusy, onClick = { val title = newTaskTitle.trim(); val description = newTaskDescription.trim(); scope.launch { workforceBusy = true; try { withContext(Dispatchers.IO) { createWorkforceTask(base, token, title, description) }; newTaskTitle = ""; newTaskDescription = ""; workforceNotice = "Task saved. Tap Run to request real Head Manager planning and worker execution." } catch (e: Exception) { workforceNotice = e.message ?: "Task could not be saved" } finally { workforceBusy = false; refreshWorkforce() } } }) { Text("Save task") }
                    Text(workforceNotice, style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(workforceTasks) { task -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(task.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(task.state) }; if (!task.result.isNullOrBlank()) Text(task.result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)); if (!task.error.isNullOrBlank()) Text(task.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); if (task.state !in listOf("RUNNING", "PLANNING", "COMPLETED", "CANCELLED")) TextButton(onClick = { scope.launch { workforceBusy = true; workforceNotice = "Head Manager planning and workers are calling configured providers…"; try { withContext(Dispatchers.IO) { executeWorkforceTask(base, token, task.id) }; workforceNotice = "Execution finished; output and events are saved by the backend." } catch (e: Exception) { workforceNotice = e.message ?: "Task execution failed. Check saved task status and provider settings." } finally { workforceBusy = false; refreshWorkforce() } } }) { Text(if (task.state == "FAILED") "Retry (bounded)" else "Run with Head Manager") } } } } }
                }
                Section.SETTINGS -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 20.dp)); Text("Backend URL: ${base.ifBlank { "Not configured" }}"); Text("NOVA token: ${if (token.isBlank()) "Not configured" else "Stored encrypted on this device"}"); Button(onClick = { showSettings = true }) { Text("Configure backend") }; Text("Arena legacy key: ${if (providerConfigured) "Configured" else "Not configured"}"); Button(onClick = openProviderSettings) { Text("Configure Arena key") }; HorizontalDivider(); Text(usageSummary, style = MaterialTheme.typography.bodySmall); Text("Provider router · ${providerConnections.size} saved connection(s)"); providerConnections.forEach { Text("${it.provider} · ${it.status} · ${it.models.size} model IDs", style = MaterialTheme.typography.bodySmall) }; Button(onClick = { providerDialog = true; registryKey = ""; registryStatus = "Add provider credentials over HTTPS; they are encrypted by NOVA on the server." }) { Text("Configure multi-provider router") }; Button(onClick = refreshWorkforce) { Text("Refresh workforce and usage") }; Text("Resource overview shows recorded requests only. Provider-specific limits and costs depend on account/model and are not guessed.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (showProviderSettings) AlertDialog(
        onDismissRequest = { if (!providerBusy) { showProviderSettings = false; providerKey = "" } },
        title = { Text("AI provider setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Arena · OpenAI-compatible API", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = providerKey, onValueChange = { providerKey = it }, label = { Text(if (providerConfigured) "Replace key (blank keeps saved key)" else "Arena API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !providerBusy)
                OutlinedTextField(value = providerModel, onValueChange = { providerModel = it }, label = { Text("Exact model ID") }, singleLine = true, enabled = !providerBusy)
                if (providerModelOptions.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(providerModelOptions.take(8)) { modelId -> TextButton(onClick = { providerModel = modelId }, enabled = !providerBusy) { Text(modelId, maxLines = 1) } } }
                Text("The key is sent to your HTTPS NOVA backend and encrypted there. It is not stored in this app or APK.", style = MaterialTheme.typography.bodySmall)
                Text(providerStatus, style = MaterialTheme.typography.bodySmall, color = if (providerConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !providerBusy && providerModel.isNotBlank(), onClick = { providerBusy = true; providerStatus = "Saving encrypted provider settings…"; scope.launch {
                try {
                    val config = withContext(Dispatchers.IO) { saveProviderConfiguration(base, token, providerKey, providerModel) }
                    providerConfigured = config.configured; providerKeySource = config.keySource; providerModel = config.modelId; providerKey = ""
                    providerStatus = if (config.configured) "Saved securely · ${config.keySource.replace('_', ' ')}" else "Provider key is not configured"
                } catch (e: Exception) { providerStatus = e.message ?: "Could not save provider settings" }
                finally { providerBusy = false }
            } }) { Text(if (providerBusy) "Saving…" else "Save") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !providerBusy && providerConfigured, onClick = { providerBusy = true; providerStatus = "Testing Arena connection…"; scope.launch {
                    try { val result = withContext(Dispatchers.IO) { testProviderConnection(base, token) }; providerModelOptions = result.modelIds; providerStatus = "Connected · ${result.modelsAvailable} models available; tap a model below" }
                    catch (e: Exception) { providerStatus = e.message ?: "Provider test failed" }
                    finally { providerBusy = false }
                } }) { Text("Test") }
                if (providerKeySource == "mobile_encrypted") TextButton(enabled = !providerBusy, onClick = { providerBusy = true; scope.launch {
                    try { val config = withContext(Dispatchers.IO) { clearProviderConfiguration(base, token) }; providerConfigured = config.configured; providerKeySource = config.keySource; providerStatus = if (config.configured) "Using backend environment key" else "Provider key cleared" }
                    catch (e: Exception) { providerStatus = e.message ?: "Could not clear provider key" }
                    finally { providerBusy = false }
                } }) { Text("Remove") }
                TextButton(enabled = !providerBusy, onClick = { showProviderSettings = false; providerKey = "" }) { Text("Close") }
            }
        }
    )
    if (providerDialog) AlertDialog(
        onDismissRequest = { providerDialog = false; registryKey = "" },
        title = { Text("Provider router setup") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = registryProvider, onValueChange = { registryProvider = it.trim().lowercase() }, label = { Text("Provider identity") }, supportingText = { Text("arena · groq · gemini · openrouter · cloudflare · claude_gateway") }, singleLine = true)
            OutlinedTextField(value = registryKey, onValueChange = { registryKey = it }, label = { Text("API key or gateway bearer token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (registryProvider == "claude_gateway") OutlinedTextField(value = registryEndpoint, onValueChange = { registryEndpoint = it.trim() }, label = { Text("Gateway HTTPS base URL (usually ends /v1)") }, supportingText = { Text("Backend operator must allow-list this hostname in NOVA_GATEWAY_ALLOWED_HOSTS.") }, singleLine = true)
            OutlinedTextField(value = registryModel, onValueChange = { registryModel = it }, label = { Text("Exact model ID (optional; discovered after test)") }, singleLine = true)
            if (registryProvider == "cloudflare") OutlinedTextField(value = registryAccountId, onValueChange = { registryAccountId = it.trim() }, label = { Text("Cloudflare account ID") }, singleLine = true)
            Text("Provider keys and gateway tokens are Fernet-encrypted by the backend; they are never returned to the app. A successful test confirms model-list access only, not inference, free quotas, or future availability.", style = MaterialTheme.typography.bodySmall)
            val providerDocsUrl = when (registryProvider) {
                "arena" -> "https://platform.arena.ai/docs/api"
                "groq" -> "https://console.groq.com/docs/quickstart"
                "gemini" -> "https://ai.google.dev/gemini-api/docs/openai"
                "openrouter" -> "https://openrouter.ai/docs/quickstart"
                "cloudflare" -> "https://developers.cloudflare.com/workers-ai/get-started/rest-api/"
                "claude_gateway" -> "https://github.com/Mukller/claude-code-gateway"
                else -> null
            }
            if (providerDocsUrl != null) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(providerDocsUrl))) }) { Text("Open provider setup docs") }
            if (registryProvider == "claude_gateway") Text("Self-hosted; requires your own upstream provider credentials and inference budget.", style = MaterialTheme.typography.bodySmall)
            Text(registryStatus, style = MaterialTheme.typography.bodySmall)
            providerConnections.forEach { connection ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${connection.provider} · ${connection.status} · ${connection.models.size} discovered model IDs", style = MaterialTheme.typography.labelSmall)
                    Text(connection.models.take(3).joinToString(), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Row {
                        TextButton(enabled = !providerBusy, onClick = { providerBusy = true; registryStatus = "Testing saved ${connection.provider} connection…"; scope.launch { try { val ids = withContext(Dispatchers.IO) { testProviderConnectionById(base, token, connection.id) }; registryStatus = "Connection test succeeded · ${ids.size} models listed"; refreshWorkforce() } catch (e: Exception) { registryStatus = e.message ?: "Connection test failed"; refreshWorkforce() } finally { providerBusy = false } } }) { Text("Test") }
                        TextButton(enabled = !providerBusy, onClick = { providerBusy = true; scope.launch { try { withContext(Dispatchers.IO) { removeProviderConnection(base, token, connection.id) }; registryStatus = "${connection.provider} disconnected"; refreshWorkforce() } catch (e: Exception) { registryStatus = e.message ?: "Could not disconnect" } finally { providerBusy = false } } }) { Text("Disconnect") }
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = registryKey.length >= 8 && !providerBusy && (registryProvider != "claude_gateway" || registryEndpoint.startsWith("https://")), onClick = { providerBusy = true; registryStatus = "Saving encrypted provider key…"; scope.launch { try { val id = withContext(Dispatchers.IO) { saveProviderConnection(base, token, registryProvider, registryKey, registryModel, registryAccountId, registryEndpoint) }; registryKey = ""; val ids = withContext(Dispatchers.IO) { testProviderConnectionById(base, token, id) }; registryStatus = "Connected · ${ids.size} model IDs listed (catalog check only)"; refreshWorkforce() } catch (e: Exception) { registryStatus = e.message ?: "Provider setup failed" } finally { providerBusy = false } } }) { Text(if (providerBusy) "Checking…" else "Save and test") } },
        dismissButton = { TextButton(onClick = { providerDialog = false; registryKey = "" }) { Text("Close") } }
    )
    editingAgent?.let { agent ->
        var selectedProvider by remember(agent.id) { mutableStateOf(agent.provider) }
        var selectedModel by remember(agent.id) { mutableStateOf(agent.model) }
        var selectedInstructions by remember(agent.id) { mutableStateOf(agent.instructions) }
        AlertDialog(onDismissRequest = { editingAgent = null }, title = { Text("Configure ${agent.name}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Identity ${agent.identity} stays independent even when model/provider choices are shared.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = selectedProvider, onValueChange = { selectedProvider = it.trim().lowercase() }, label = { Text("Provider identity") }, singleLine = true)
            OutlinedTextField(value = selectedModel, onValueChange = { selectedModel = it.trim() }, label = { Text("Exact model ID from provider discovery") }, singleLine = true)
            val availableAgentModels = providerConnections.firstOrNull { it.provider == selectedProvider }?.models.orEmpty()
            if (availableAgentModels.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(availableAgentModels.take(30)) { modelId -> TextButton(onClick = { selectedModel = modelId }) { Text(modelId, maxLines = 1) } } }
            OutlinedTextField(value = selectedInstructions, onValueChange = { selectedInstructions = it }, label = { Text("Agent-specific instructions") }, minLines = 3, maxLines = 6)
            Text("Specialties: ${agent.specialties.joinToString()}", style = MaterialTheme.typography.labelSmall)
        } }, confirmButton = { TextButton(enabled = selectedProvider.isNotBlank() && selectedModel.isNotBlank() && !workforceBusy, onClick = { workforceBusy = true; scope.launch { try { val updated = withContext(Dispatchers.IO) { saveAgentConfig(base, token, agent, selectedProvider, selectedModel, selectedInstructions) }; workforceAgents.indexOfFirst { it.id == updated.id }.takeIf { it >= 0 }?.let { index -> workforceAgents[index] = updated }; workforceNotice = "Saved ${updated.identity} model and instructions."; editingAgent = null } catch (e: Exception) { workforceNotice = e.message ?: "Could not update agent" } finally { workforceBusy = false } } }) { Text("Save agent") } }, dismissButton = { TextButton(onClick = { editingAgent = null }) { Text("Cancel") } })
    }
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("Conversation history") }, text = {
        Column {
            if (historyStatus.isNotBlank()) Text(historyStatus, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(conversations) { item ->
                    TextButton(onClick = { scope.launch {
                        try {
                            val messages = withContext(Dispatchers.IO) { loadConversation(base, token, item.id) }
                            conversationId = item.id; chat.clear(); chat.addAll(messages); showHistory = false; section = Section.ASSISTANT; status = "Loaded saved conversation"
                        } catch (e: Exception) { historyStatus = e.message ?: "Could not load conversation" }
                    } }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text(item.title, maxLines = 2); Text(item.id.take(8), style = MaterialTheme.typography.labelSmall) } }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } })
    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("Backend connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("HTTPS backend URL") }, singleLine = true)
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Scoped NOVA API token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("The token is encrypted with Android Keystore on this device. Use an API key with only the scopes needed.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.put("base", base.trim().trimEnd('/'))
                settings.put("token", token.trim())
                showSettings = false
                status = "Connection settings saved. Send a message to verify."
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    settings.put("base", ""); settings.put("token", "")
                    base = ""; token = ""; showSettings = false
                    status = "Backend credentials cleared."
                }) { Text("Clear credentials") }
                TextButton(onClick = { showSettings = false }) { Text("Cancel") }
            }
        }
    )
}

@Composable private fun ChatBubble(line: ChatLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 320.dp)) { Text(line.text, Modifier.padding(14.dp)) }
    }
}
@Composable private fun EmptyState(title: String, message: String) { Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.AutoAwesome, title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp)); Spacer(Modifier.height(10.dp)); Text(title, style = MaterialTheme.typography.titleMedium); Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun DashboardCard(title: String, message: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, title, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(message, style = MaterialTheme.typography.bodySmall) } } } }

private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
private data class ChatResult(val response: String, val conversationId: String)
private fun checkedBase(base: String, token: String): String {
    if (!base.startsWith("https://")) throw IOException("Backend URL must use HTTPS")
    if (token.isBlank()) throw IOException("Configure a scoped NOVA API token in Settings")
    return base.trimEnd('/')
}
private fun sendMessage(base: String, token: String, text: String, conversationId: String?): ChatResult {
    val root = JSONObject().put("message", text)
    if (conversationId != null) root.put("conversation_id", conversationId)
    val payload = root.toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder().url("${checkedBase(base, token)}/api/v1/assistant/chat").header("Authorization", "Bearer $token").post(payload).build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("NOVA API returned HTTP ${response.code}: ${response.body?.string()?.take(240) ?: "No response body"}")
        val body = response.body?.string() ?: throw IOException("Empty response from NOVA backend")
        val json = JSONObject(body)
        return ChatResult(json.getString("response"), json.getString("conversation_id"))
    }
}
private fun fetchProviderConfiguration(base: String, token: String): ProviderConfig {
    val request = Request.Builder().url("${checkedBase(base, token)}/api/v1/settings/provider").header("Authorization", "Bearer $token").get().build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("NOVA API returned HTTP ${response.code}: ${response.body?.string()?.take(180) ?: "No response body"}")
        val json = JSONObject(response.body?.string() ?: "{}")
        return ProviderConfig(json.optString("provider", "arena"), json.optString("model_id", "coding-router-preview"), json.optBoolean("configured"), json.optString("key_source", "not_configured"))
    }
}
private fun saveProviderConfiguration(base: String, token: String, apiKey: String, model: String): ProviderConfig {
    val body = JSONObject().put("provider", "arena").put("model_id", model.trim())
    if (apiKey.isNotBlank()) body.put("api_key", apiKey.trim())
    val request = Request.Builder().url("${checkedBase(base, token)}/api/v1/settings/provider").header("Authorization", "Bearer $token").put(body.toString().toRequestBody("application/json".toMediaType())).build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("NOVA API returned HTTP ${response.code}: ${response.body?.string()?.take(180) ?: "No response body"}")
        val json = JSONObject(response.body?.string() ?: "{}")
        return ProviderConfig(json.optString("provider", "arena"), json.optString("model_id", model), json.optBoolean("configured"), json.optString("key_source", "not_configured"))
    }
}
private fun testProviderConnection(base: String, token: String): ProviderTestResult {
    val request = Request.Builder().url("${checkedBase(base, token)}/api/v1/settings/provider/test").header("Authorization", "Bearer $token").post("{}".toRequestBody("application/json".toMediaType())).build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("NOVA API returned HTTP ${response.code}: ${response.body?.string()?.take(180) ?: "No response body"}")
        val json = JSONObject(response.body?.string() ?: "{}")
        val models = json.optJSONArray("model_ids") ?: throw IOException("NOVA API returned no model list")
        return ProviderTestResult(json.optInt("models_available"), (0 until models.length()).map { models.getString(it) })
    }
}
private fun clearProviderConfiguration(base: String, token: String): ProviderConfig {
    val request = Request.Builder().url("${checkedBase(base, token)}/api/v1/settings/provider").header("Authorization", "Bearer $token").delete().build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("NOVA API returned HTTP ${response.code}: ${response.body?.string()?.take(180) ?: "No response body"}")
        val json = JSONObject(response.body?.string() ?: "{}")
        return ProviderConfig("arena", "coding-router-preview", json.optBoolean("configured"), json.optString("key_source", "not_configured"))
    }
}
private fun fetchConversations(base: String, token: String): List<ConversationSummary> {
    val request = Request.Builder().url("${checkedBase(base, token)}/api/v1/assistant/conversations").header("Authorization", "Bearer $token").get().build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("NOVA API returned HTTP ${response.code}")
        val array = org.json.JSONArray(response.body?.string() ?: "[]")
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { ConversationSummary(it.getString("id"), it.getString("title")) } }
    }
}
private fun loadConversation(base: String, token: String, id: String): List<ChatLine> {
    val request = Request.Builder().url("${checkedBase(base, token)}/api/v1/assistant/conversations/$id").header("Authorization", "Bearer $token").get().build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("NOVA API returned HTTP ${response.code}")
        val messages = JSONObject(response.body?.string() ?: "{}").getJSONArray("messages")
        return (0 until messages.length()).mapNotNull { i -> messages.getJSONObject(i).let { item -> when (item.getString("role")) { "user" -> ChatLine(item.getString("content"), true); "assistant" -> ChatLine(item.getString("content"), false); else -> null } } }
    }
}

private fun authorizedRequest(base: String, token: String, path: String, method: String = "GET", json: JSONObject? = null): String {
    val builder = Request.Builder().url("${checkedBase(base, token)}$path").header("Authorization", "Bearer $token")
    val media = "application/json".toMediaType()
    when (method) {
        "POST" -> builder.post((json ?: JSONObject()).toString().toRequestBody(media))
        "PUT" -> builder.put((json ?: JSONObject()).toString().toRequestBody(media))
        "DELETE" -> builder.delete()
        else -> builder.get()
    }
    http.newCall(builder.build()).execute().use { response ->
        val body = response.body?.string() ?: "{}"
        if (!response.isSuccessful) throw IOException("NOVA API returned HTTP ${response.code}: ${body.take(200)}")
        return body
    }
}

private fun loadWorkforce(base: String, token: String): WorkforceSnapshot<List<WorkforceAgent>, List<WorkforceTask>, List<OfficeEvent>, List<ProviderConnectionInfo>> {
    val agentsJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/agents"))
    val agents = (0 until agentsJson.length()).map { i ->
        val item = agentsJson.getJSONObject(i)
        WorkforceAgent(item.getString("id"), item.optString("identity", "worker"), item.optString("name", "Agent"), item.optString("job_title", "Worker"), item.optString("provider", "arena"), item.optString("model_id", ""), item.optString("instructions", ""), item.optString("status", "idle"), jsonStrings(item.optJSONArray("specialties")), item.optDouble("temperature", 0.2), item.optInt("context_length", 8192), jsonStrings(item.optJSONArray("tool_permissions")), item.optBoolean("memory_enabled", true), item.optInt("priority", 50), item.optBoolean("critical"), item.optInt("max_task_duration_seconds", 300))
    }
    val tasksJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/tasks"))
    val tasks = (0 until tasksJson.length()).map { i -> tasksJson.getJSONObject(i).let { WorkforceTask(it.getString("id"), it.optString("title", "Task"), it.optString("state", "RECEIVED"), it.optString("result").takeIf { value -> value.isNotBlank() && value != "null" }, it.optString("error_detail").takeIf { value -> value.isNotBlank() && value != "null" }, it.optInt("priority", 50)) } }
    val eventsJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/events/recent?limit=100"))
    val events = (0 until eventsJson.length()).map { i -> eventsJson.getJSONObject(i).let { OfficeEvent(it.optString("event_type", "event"), it.optString("agent_id").takeIf { value -> value.isNotBlank() && value != "null" }, it.optString("task_id").takeIf { value -> value.isNotBlank() && value != "null" }, it.optString("created_at"), it.optJSONObject("payload")?.toString() ?: "{}") } }
    val providerJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/providers/connections"))
    val connections = (0 until providerJson.length()).map { i -> providerJson.getJSONObject(i).let { ProviderConnectionInfo(it.getString("id"), it.optString("provider"), jsonStrings(it.optJSONArray("models")), it.optInt("priority", 100), it.optString("status", "not_tested")) } }
    return WorkforceSnapshot(agents, tasks, events, connections)
}

private fun jsonStrings(array: org.json.JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }

private fun seedAgentRoster(base: String, token: String): List<WorkforceAgent> {
    val response = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/agents/seed", "POST"))
    return (0 until response.length()).map { i -> val item = response.getJSONObject(i); WorkforceAgent(item.getString("id"), item.optString("identity", "worker"), item.optString("name", "Agent"), item.optString("job_title", "Worker"), item.optString("provider", "arena"), item.optString("model_id", ""), item.optString("instructions", ""), item.optString("status", "idle"), jsonStrings(item.optJSONArray("specialties")), item.optDouble("temperature", 0.2), item.optInt("context_length", 8192), jsonStrings(item.optJSONArray("tool_permissions")), item.optBoolean("memory_enabled", true), item.optInt("priority", 50), item.optBoolean("critical"), item.optInt("max_task_duration_seconds", 300)) }
}

private fun createWorkforceTask(base: String, token: String, title: String, description: String) {
    authorizedRequest(base, token, "/api/v1/tasks", "POST", JSONObject().put("title", title).put("description", description))
}

private fun executeWorkforceTask(base: String, token: String, taskId: String) {
    authorizedRequest(base, token, "/api/v1/tasks/$taskId/execute", "POST")
}

private fun saveAgentConfig(base: String, token: String, agent: WorkforceAgent, provider: String, model: String, instructions: String): WorkforceAgent {
    val specialties = org.json.JSONArray().also { list -> agent.specialties.forEach { list.put(it) } }
    val tools = org.json.JSONArray().also { list -> agent.permissions.forEach { list.put(it) } }
    val body = JSONObject().put("identity", agent.identity).put("name", agent.name).put("job_title", agent.title).put("provider", provider).put("model_id", model).put("specialties", specialties).put("instructions", instructions).put("temperature", agent.temperature).put("context_length", agent.contextLength).put("tool_permissions", tools).put("memory_enabled", agent.memoryEnabled).put("priority", agent.priority).put("critical", agent.critical).put("max_task_duration_seconds", agent.maxDuration)
    val item = JSONObject(authorizedRequest(base, token, "/api/v1/agents/${agent.id}", "PUT", body))
    return WorkforceAgent(item.getString("id"), item.getString("identity"), item.getString("name"), item.getString("job_title"), item.getString("provider"), item.getString("model_id"), item.optString("instructions", ""), item.optString("status", "idle"), jsonStrings(item.optJSONArray("specialties")), item.optDouble("temperature", 0.2), item.optInt("context_length", 8192), jsonStrings(item.optJSONArray("tool_permissions")), item.optBoolean("memory_enabled", true), item.optInt("priority", 50), item.optBoolean("critical"), item.optInt("max_task_duration_seconds", 300))
}

private fun saveProviderConnection(base: String, token: String, provider: String, apiKey: String, model: String, accountId: String, endpoint: String): String {
    if (provider !in listOf("arena", "groq", "gemini", "openrouter", "cloudflare", "claude_gateway")) throw IOException("Choose a supported provider identity")
    if (provider == "cloudflare" && accountId.isBlank()) throw IOException("Cloudflare account ID is required")
    val models = org.json.JSONArray().also { if (model.isNotBlank()) it.put(model.trim()) }
    val body = JSONObject().put("provider", provider).put("api_key", apiKey.trim()).put("model_ids", models)
    if (provider == "cloudflare") body.put("account_id", accountId.trim())
    if (provider == "claude_gateway") body.put("endpoint", endpoint.trim().trimEnd('/'))
    return JSONObject(authorizedRequest(base, token, "/api/v1/providers/connections", "POST", body)).getString("id")
}

private fun removeProviderConnection(base: String, token: String, id: String) {
    authorizedRequest(base, token, "/api/v1/providers/connections/$id", "DELETE")
}

private fun testProviderConnectionById(base: String, token: String, id: String): List<String> {
    val response = JSONObject(authorizedRequest(base, token, "/api/v1/providers/connections/$id/test", "POST"))
    return jsonStrings(response.optJSONArray("model_ids"))
}

private fun loadResourceOverview(base: String, token: String): String {
    val json = JSONObject(authorizedRequest(base, token, "/api/v1/resources/overview"))
    val agents = json.optJSONObject("agents") ?: JSONObject()
    val tasks = json.optJSONObject("tasks") ?: JSONObject()
    return "Workers ${agents.optInt("working")}/${agents.optInt("total")} · tasks ${tasks.optInt("active")} active, ${tasks.optInt("queued")} queued, ${tasks.optInt("completed_today")} completed today · ${json.optInt("provider_requests_today")} provider requests today. Token/cost estimates are not fabricated."
}
