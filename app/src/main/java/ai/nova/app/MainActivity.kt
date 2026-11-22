package ai.nova.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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
private enum class Section(val title: String) { HOME("Home"), ASSISTANT("Assistant"), HQ("Headquarters"), AGENTS("Agents"), TASKS("Tasks"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovaApp(settings: SecureSettings) {
    var section by remember { mutableStateOf(Section.ASSISTANT) }
    var base by remember { mutableStateOf(settings.get("base")) }
    var token by remember { mutableStateOf(settings.get("token")) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    val conversations = remember { mutableStateListOf<ConversationSummary>() }
    val chat = remember { mutableStateListOf<ChatLine>() }
    var draft by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect a NOVA backend in Settings to send messages.") }
    var historyStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("NOVA", fontWeight = FontWeight.Bold); Text("Personal AI workspace", style = MaterialTheme.typography.labelSmall) } }, actions = { if (section == Section.ASSISTANT) { IconButton(onClick = { conversationId = null; chat.clear(); status = "New conversation" }) { Icon(Icons.Default.AddComment, "New conversation") }; IconButton(onClick = { showHistory = true; historyStatus = "Loading history…"; scope.launch { try { val loaded = withContext(Dispatchers.IO) { fetchConversations(base, token) }; conversations.clear(); conversations.addAll(loaded); historyStatus = if (loaded.isEmpty()) "No saved conversations yet." else "Select a conversation to continue." } catch (e: Exception) { historyStatus = e.message ?: "Could not load history" } } }) { Icon(Icons.Default.History, "Conversation history") } }; IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") } }) },
        bottomBar = { NavigationBar { Section.values().forEach { item -> NavigationBarItem(selected = section == item, onClick = { section = item }, icon = { Icon(when(item){Section.HOME->Icons.Default.Home;Section.ASSISTANT->Icons.Default.Chat;Section.HQ->Icons.Default.Business;Section.AGENTS->Icons.Default.Groups;Section.TASKS->Icons.Default.Checklist;Section.SETTINGS->Icons.Default.Settings}, item.title) }, label = { Text(item.title) }) } } }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            when (section) {
                Section.ASSISTANT -> {
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) { Text("Your assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(status, style = MaterialTheme.typography.bodySmall) }
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
                Section.HQ -> EmptyState("Headquarters", "Pixel-art office simulation is in development. No agent activity is shown until real worker events are connected.")
                Section.AGENTS -> EmptyState("Agent workforce", "Create and manage separately configured model identities through the NOVA API. The mobile management view is not yet implemented.")
                Section.TASKS -> EmptyState("Tasks", "Persistent task records are available through the backend API. Autonomous execution is not enabled in this build.")
                Section.SETTINGS -> Column { Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 20.dp)); Text("Backend URL: ${base.ifBlank { "Not configured" }}"); Text("API token: ${if (token.isBlank()) "Not configured" else "Stored encrypted on this device"}"); Button(onClick = { showSettings = true }) { Text("Configure backend") } }
            }
        }
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
