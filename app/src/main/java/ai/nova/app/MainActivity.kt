package ai.nova.app

import android.os.Bundle
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SecureSettings(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9DDCD9), secondary = Color(0xFFB8A8F4), background = Color(0xFF080D18), surface = Color(0xFF101827))) { NovaApp(settings) } }
    }
}

private data class ChatLine(val text: String, val fromUser: Boolean)
private data class MemoryItem(val id: String, val scope: String, val content: String, val agentId: String?, val projectKey: String?, val importance: Double)
private data class AgentChatReply(val response: String)
internal data class WorkforceAgent(val id: String, val identity: String, val name: String, val title: String, val provider: String, val model: String, val instructions: String, val status: String, val specialties: List<String>, val temperature: Double, val contextLength: Int, val permissions: List<String>, val memoryEnabled: Boolean, val priority: Int, val critical: Boolean, val maxDuration: Int)
internal data class WorkforceTask(val id: String, val title: String, val state: String, val result: String?, val error: String?, val priority: Int, val assignedAgentId: String?, val requestedAgentId: String?, val progressPercent: Int, val progressStage: String)
private data class AgentAnalytics(val identity: String, val name: String, val status: String, val assignments: Int, val completed: Int, val failed: Int, val active: Int, val durationSeconds: Double?, val durationSamples: Int, val requests: Int, val successful: Int, val failedRequests: Int, val inputTokens: Int, val outputTokens: Int)
internal data class OfficeEvent(val type: String, val agentId: String?, val taskId: String?, val createdAt: String, val payload: String)
internal data class WorkforceMessage(val id: String, val taskId: String?, val senderAgentId: String, val recipientAgentId: String?, val messageType: String, val content: String, val createdAt: String)
internal data class MeetingInviteInfo(val agentId: String,val name: String,val status: String)
internal data class MeetingLine(val speaker: String,val role: String,val content: String)
internal data class MeetingSummary(val id: String,val title: String,val agenda: String,val context: String,val status: String,val scheduledAt: String?,val taskId: String?,val invitations: List<MeetingInviteInfo>,val messages: List<MeetingLine>)
internal data class OfficeCell(val x: Int, val y: Int)
internal data class OfficeRoomInfo(val id: String, val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val entrance: OfficeCell)
internal data class OfficeFurnitureInfo(val id: String, val kind: String, val x: Int, val y: Int, val width: Int, val height: Int, val rotation: Int)
internal data class OfficeCharacterState(val agentId: String, val x: Int, val y: Int, val movementState: String, val destination: OfficeCell?, val route: List<OfficeCell>, val moveId: String?)
internal data class OfficeWorld(val width: Int, val height: Int, val rooms: List<OfficeRoomInfo>, val furniture: List<OfficeFurnitureInfo>, val characters: Map<String, OfficeCharacterState>)
internal data class ProviderConnectionInfo(val id: String, val provider: String, val models: List<String>, val priority: Int, val status: String)
internal data class CharacterProfile(val key: String, val agentId: String?, val name: String, val gender: String, val age: Int?, val personality: String, val specialization: String, val communicationStyle: String, val appearance: String, val appearanceJson: String, val provider: String?, val model: String?, val availability: String, val voiceId: String?, val speed: Float, val stability: Float, val style: Float, val similarityBoost: Float, val voiceReady: Boolean)
private data class WorkforceSnapshot<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
private enum class Section(val title: String) { HOME("Home"), ASSISTANT("Chat"), HQ("Headquarters"), AGENTS("Agents"), TASKS("Projects"), MEETINGS("Meetings"), ACTIVITY("Activity"), MEMORY("Memory"), INTEGRATIONS("Integrations"), GATEWAY("NOVA-GATEWAY"), SETTINGS("Settings") }

@Composable
private fun OfficeStepper(label: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = { onChange(value - 1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Remove, "Decrease $label", modifier = Modifier.size(16.dp)) }
        Text(value.toString(), Modifier.widthIn(min = 20.dp), style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = { onChange(value + 1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, "Increase $label", modifier = Modifier.size(16.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovaApp(settings: SecureSettings) {
    val context = LocalContext.current
    val gatewayClient = remember(settings) { GatewayClient(settings) }
    var gatewaySession by remember { mutableStateOf(gatewayClient.session()) }
    var speechEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var speechInitializing by remember { mutableStateOf(false) }
    var pendingSpeech by remember { mutableStateOf<String?>(null) }
    var voiceStatus by remember { mutableStateOf("") }
    var queuedSpeakText by remember { mutableStateOf<String?>(null) }
    var messagePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(context) { onDispose { speechEngine?.stop(); speechEngine?.shutdown(); messagePlayer?.stop(); messagePlayer?.release() } }
    val speakOnDevice: (String) -> Unit = { text ->
        if (speechEngine != null && !speechInitializing) {
            speechEngine?.stop()
            speechEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova-assistant-response")
            voiceStatus = "Speaking with the device's configured speech engine."
        } else if (!speechInitializing) {
            pendingSpeech = text
            speechInitializing = true
            speechEngine = TextToSpeech(context) { result ->
                speechInitializing = false
                if (result == TextToSpeech.SUCCESS) {
                    val languageResult = speechEngine?.setLanguage(Locale.getDefault())
                    if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        voiceStatus = "The device speech engine does not support the current language."
                        pendingSpeech = null
                    } else {
                        pendingSpeech?.let { speechEngine?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "nova-assistant-response") }
                        pendingSpeech = null
                        voiceStatus = "Speaking with the device's configured speech engine."
                    }
                } else {
                    pendingSpeech = null
                    voiceStatus = "Device text-to-speech could not initialize."
                }
            }
        } else {
            pendingSpeech = text
        }
    }
    var section by remember { mutableStateOf(Section.HQ) }
    var base by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var reduceMotion by remember { mutableStateOf(settings.get("reduce_motion", "false") == "true") }
    var backendStatus by remember { mutableStateOf("AI services connect through NOVA-GATEWAY") }
    var backendBusy by remember { mutableStateOf(false) }
    var showNavigation by remember { mutableStateOf(false) }
    var assistantExpanded by remember { mutableStateOf(false) }
    var assistantListening by remember { mutableStateOf(false) }
    var assistantResponding by remember { mutableStateOf(false) }
    var memoryDraft by remember { mutableStateOf("") }
    var memoryScope by remember { mutableStateOf("headquarters") }
    var memoryAgentId by remember { mutableStateOf<String?>(null) }
    var memoryProjectKey by remember { mutableStateOf("") }
    var memoryStatus by remember { mutableStateOf("Saved workspace memories load from your NOVA backend.") }
    val memories = remember { mutableStateListOf<MemoryItem>() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val workforceAgents = remember { mutableStateListOf<WorkforceAgent>() }
    val workforceTasks = remember { mutableStateListOf<WorkforceTask>() }
    val officeEvents = remember { mutableStateListOf<OfficeEvent>() }
    val workerMessages = remember { mutableStateListOf<WorkforceMessage>() }
    val meetings = remember { mutableStateListOf<MeetingSummary>() }
    val selectedMeetingParticipants = remember { mutableStateListOf<String>() }
    var meetingTitle by remember { mutableStateOf("") }
    var meetingAgenda by remember { mutableStateOf("") }
    var meetingContext by remember { mutableStateOf("") }
    var meetingScheduleMinutes by remember { mutableStateOf("0") }
    var selectedMeetingTaskId by remember { mutableStateOf<String?>(null) }
    var handoffMeeting by remember { mutableStateOf<MeetingSummary?>(null) }
    var handoffTaskId by remember { mutableStateOf<String?>(null) }
    var handoffAgentId by remember { mutableStateOf<String?>(null) }
    var handoffInstructions by remember { mutableStateOf("") }
    val providerConnections = remember { mutableStateListOf<ProviderConnectionInfo>() }
    val agentAnalytics = remember { mutableStateListOf<AgentAnalytics>() }
    var analyticsNote by remember { mutableStateOf("Analytics not loaded") }
    var officeWorld by remember { mutableStateOf<OfficeWorld?>(null) }
    var officeDraft by remember { mutableStateOf<OfficeWorld?>(null) }
    var officeEditorKind by remember { mutableStateOf("chair") }
    var officeEditorX by remember { mutableStateOf("0") }
    var officeEditorY by remember { mutableStateOf("0") }
    var officeEditorBusy by remember { mutableStateOf(false) }
    var officeEditorNotice by remember { mutableStateOf("") }
    var showOfficeEditor by remember { mutableStateOf(false) }
    var selectedOfficeAgent by remember { mutableStateOf<WorkforceAgent?>(null) }
    val agentChatLines = remember { mutableStateListOf<ChatLine>() }
    var agentChatDraft by remember { mutableStateOf("") }
    var agentChatStatus by remember { mutableStateOf("") }
    var agentChatBusy by remember { mutableStateOf(false) }
    var workforceBusy by remember { mutableStateOf(false) }
    var workforceNotice by remember { mutableStateOf("Connect a backend to load saved agent/task records.") }
    var usageSummary by remember { mutableStateOf("Usage summary not loaded") }
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskDescription by remember { mutableStateOf("") }
    var requestedTaskAgentId by remember { mutableStateOf<String?>(null) }
    val selectedTaskDependencies = remember { mutableStateListOf<String>() }
    var editingAgent by remember { mutableStateOf<WorkforceAgent?>(null) }
    var customizingAppearanceFor by remember { mutableStateOf<CharacterProfile?>(null) }
    var appearanceBusy by remember { mutableStateOf(false) }
    var appearanceNotice by remember { mutableStateOf("") }
    var showCharacters by remember { mutableStateOf(false) }
    var characterBusy by remember { mutableStateOf(false) }
    var characterNotice by remember { mutableStateOf("") }
    val characters = remember { mutableStateListOf<CharacterProfile>() }
    var selectedCharacterKey by remember { mutableStateOf("head") }
    val chat = remember { mutableStateListOf<ChatLine>() }
    var draft by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sign in to NOVA-GATEWAY to message Head.") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // Remove superseded legacy API credentials; only the gateway JWT triple is retained.
        settings.put("base", "")
        settings.put("token", "")
    }
    val speakResponse: (String) -> Unit = { text ->
        speechEngine?.stop(); messagePlayer?.stop()
        queuedSpeakText = text
    }
    val stopSpeech: () -> Unit = { queuedSpeakText = null; speechEngine?.stop(); messagePlayer?.stop(); voiceStatus = "Speech playback stopped." }
    LaunchedEffect(queuedSpeakText, base, token) {
        val message = queuedSpeakText ?: return@LaunchedEffect
        if (base.isBlank() || token.isBlank()) { queuedSpeakText = null; speakOnDevice(message); return@LaunchedEffect }
        voiceStatus = "Requesting Head voice for this actual assistant message…"
        try {
            val audio = withContext(Dispatchers.IO) { synthesizeHeadMessage(base, token, message) }
            val file = withContext(Dispatchers.IO) { java.io.File(context.cacheDir, "nova-head-message.mp3").apply { writeBytes(audio) } }
            messagePlayer?.stop(); messagePlayer?.release()
            messagePlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { voiceStatus = "Head finished speaking this assistant message." }
                setOnErrorListener { _, _, _ -> voiceStatus = "Head audio playback failed; the message remains available as text."; true }
                setOnPreparedListener { it.start(); voiceStatus = "Head is speaking this assistant message." }
                prepareAsync()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            voiceStatus = "Head voice is unavailable; using device TTS for this actual assistant message."
            speakOnDevice(message)
        } finally {
            if (queuedSpeakText == message) queuedSpeakText = null
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        assistantListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) draft = listOf(draft.trim(), spoken.trim()).filter { it.isNotBlank() }.joinToString(" ")
        }
    }
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val attached = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 256_000) throw IOException("Text attachments are limited to 256 KB")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IOException("Could not read the selected file")
                val text = attached.toString(Charsets.UTF_8)
                if ('\u0000' in text) throw IOException("Only plain UTF-8 text attachments are supported")
                val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "text file"
                draft = listOf(draft.trim(), "Attached text ($filename):\n$text").filter { it.isNotBlank() }.joinToString("\n\n")
            } catch (error: Exception) { status = error.message ?: "Could not read attachment" }
        }
    }
    val sendAssistantMessage: () -> Unit = {
        if (!busy && draft.isNotBlank()) {
            val text = draft.trim(); draft = ""; chat.add(ChatLine(text, true)); val answerIndex = chat.size; chat.add(ChatLine("", false)); busy = true; status = "Sending to NOVA-GATEWAY Head…"
            scope.launch {
                val chunks = Channel<String>(Channel.UNLIMITED)
                var completedResponse: String? = null
                val collector = launch { for (chunk in chunks) { assistantResponding = true; if (answerIndex < chat.size) chat[answerIndex] = ChatLine(chat[answerIndex].text + chunk, false) } }
                try {
                    if (gatewayClient.session() == null) throw IOException("Sign in to NOVA-GATEWAY from the navigation drawer before sending AI requests.")
                    val response = withContext(Dispatchers.IO) { gatewayHeadOrchestrate(gatewayClient, text) }
                    chunks.trySend(response)
                    completedResponse = response
                    status = "Head orchestration completed through NOVA-GATEWAY."
                } catch (error: Exception) {
                    status = error.message ?: "NOVA-GATEWAY request failed; no response was fabricated."
                    if (answerIndex < chat.size && chat[answerIndex].text.isBlank()) chat.removeAt(answerIndex)
                } finally {
                    chunks.close(); collector.join()
                    if (completedResponse != null && answerIndex < chat.size) chat[answerIndex] = ChatLine(completedResponse!!, false)
                    busy = false; assistantResponding = false
                }
            }
        }
    }
    val refreshMemories: () -> Unit = {
        scope.launch {
            try { memories.clear(); memories.addAll(withContext(Dispatchers.IO) { loadMemories(base, token) }); memoryStatus = "${memories.size} saved memories · synced" }
            catch (error: Exception) { memoryStatus = error.message ?: "Could not load saved memory" }
        }
    }
    val saveMemory: () -> Unit = {
        if (memoryDraft.isNotBlank()) scope.launch {
            try { withContext(Dispatchers.IO) { createMemory(base, token, memoryScope, memoryDraft.trim(), memoryAgentId, memoryProjectKey.trim()) }; memoryDraft = ""; refreshMemories() }
            catch (error: Exception) { memoryStatus = error.message ?: "Memory was not saved" }
        }
    }
    val refreshWorkforce: () -> Unit = {
        if (base.isBlank() || token.isBlank()) {
            workforceNotice = "Sign in to NOVA-GATEWAY for live Head, worker, task, memory, and activity data."
        } else if (!workforceBusy) {
            workforceBusy = true
            scope.launch {
                try {
                    var snapshot = withContext(Dispatchers.IO) { loadWorkforce(base, token) }
                    if (snapshot.first.isEmpty()) {
                        try { withContext(Dispatchers.IO) { seedAgentRoster(base, token) }; snapshot = withContext(Dispatchers.IO) { loadWorkforce(base, token) } }
                        catch (_: Exception) { /* Preserve the empty/offline workspace if this token cannot create the default roster. */ }
                    }
                    val loadedCharacters = try { withContext(Dispatchers.IO) { loadCharacterProfiles(base, token) } } catch (_: Exception) { emptyList() }
                    characters.clear(); characters.addAll(loadedCharacters)
                    usageSummary = withContext(Dispatchers.IO) { loadResourceOverview(base, token) }
                    workforceAgents.clear(); workforceAgents.addAll(snapshot.first)
                    workforceTasks.clear(); workforceTasks.addAll(snapshot.second)
                    officeEvents.clear(); officeEvents.addAll(snapshot.third)
                    try { workerMessages.clear(); workerMessages.addAll(withContext(Dispatchers.IO) { loadWorkerMessages(base, token) }) }
                    catch (_: Exception) { workerMessages.clear() }
                    providerConnections.clear(); providerConnections.addAll(snapshot.fourth)
                    officeWorld = snapshot.fifth
                    try { meetings.clear(); meetings.addAll(withContext(Dispatchers.IO) { loadMeetings(base, token) }) }
                    catch (meetingError: Exception) { workforceNotice = meetingError.message ?: "Meeting history could not be synchronized" }
                    try {
                        val metrics = withContext(Dispatchers.IO) { loadWorkforceAnalytics(base, token) }
                        agentAnalytics.clear(); agentAnalytics.addAll(metrics)
                        analyticsNote = "Persisted counts/durations only; provider token totals may be zero when unavailable. No inferred productivity score or cost."
                    } catch (metricError: Exception) { agentAnalytics.clear(); analyticsNote = metricError.message ?: "Per-agent analytics unavailable for this token" }
                    workforceNotice = if (snapshot.first.isEmpty()) "No agent roster yet. Seed the 11 distinct identities to begin." else "Live backend snapshot · ${snapshot.first.count { it.status == "working" }} working · ${snapshot.second.count { it.state in listOf("RECEIVED", "PLANNING", "ASSIGNED", "RUNNING") }} active/queued tasks"
                } catch (e: Exception) { workforceNotice = e.message ?: "Could not load workforce data" }
                finally { workforceBusy = false }
            }
        }
    }
    LaunchedEffect(section, base, token) {
        if (section == Section.MEMORY) refreshMemories()
        if (section in setOf(Section.ACTIVITY, Section.INTEGRATIONS, Section.MEETINGS)) refreshWorkforce()

    }
    LaunchedEffect(section, base, token) {
        if (section in setOf(Section.HQ, Section.AGENTS, Section.TASKS, Section.ACTIVITY, Section.INTEGRATIONS, Section.MEETINGS) && base.startsWith("https://") && token.isNotBlank()) {
            var backoffMs = 1_000L
            while (isActive) {
                refreshWorkforce()
                val updates = Channel<Unit>(Channel.CONFLATED)
                val closed = Channel<Unit>(Channel.CONFLATED)
                val request = Request.Builder().url("${base.trimEnd('/').replaceFirst(Regex("^https:"), "wss:")}/api/v1/events").header("Authorization", "Bearer $token").build()
                val socketClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()
                val socket = socketClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) { updates.trySend(Unit) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.trySend(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.trySend(Unit) }
                })
                val synchronizer = launch {
                    for (ignored in updates) {
                        delay(180)
                        while (updates.tryReceive().isSuccess) { /* coalesce backend event bursts */ }
                        refreshWorkforce()
                    }
                }
                try { closed.receive() }
                finally { socket.cancel(); updates.close(); closed.close(); synchronizer.cancelAndJoin() }
                refreshWorkforce()
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }
    val movingSignature = officeWorld?.characters?.values?.filter { it.movementState in listOf("walking", "waiting") }?.joinToString { "${it.agentId}:${it.x}:${it.y}:${it.route.firstOrNull()}" }
    LaunchedEffect(section, base, token, movingSignature) {
        if (section == Section.HQ && !movingSignature.isNullOrBlank()) {
            val before = officeWorld ?: return@LaunchedEffect
            val moving = before.characters.values.filter { it.movementState in listOf("walking", "waiting") }
            delay(260)
            val progressed = mutableMapOf<String, OfficeCharacterState>()
            for (character in moving) {
                try { progressed[character.agentId] = withContext(Dispatchers.IO) { advanceOfficeStep(base, token, character) } }
                catch (e: Exception) { workforceNotice = e.message ?: "Movement step could not be saved"; break }
            }
            if (progressed.isNotEmpty()) officeWorld = officeWorld?.let { current -> current.copy(characters = current.characters + progressed) }
        }
    }
    LaunchedEffect(selectedOfficeAgent?.id) {
        agentChatLines.clear(); agentChatDraft = ""
        agentChatStatus = if (gatewayClient.session() == null) "Sign in to NOVA-GATEWAY to chat with this worker." else "Worker prompts are routed to NOVA-GATEWAY."
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("NOVA", Modifier.padding(start = 24.dp, top = 26.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Living AI headquarters", Modifier.padding(start = 24.dp, bottom = 18.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NovaDrawerItem(Section.HQ, section, Icons.Default.Business) { section = Section.HQ; scope.launch { drawerState.close() } }
                NovaDrawerItem(Section.AGENTS, section, Icons.Default.Groups) { section = Section.AGENTS; scope.launch { drawerState.close() } }
                NovaDrawerItem(Section.GATEWAY, section, Icons.Default.FolderOpen, "Gateway tasks & messaging") { section = Section.GATEWAY; scope.launch { drawerState.close() } }
                NovaDrawerItem(Section.MEETINGS, section, Icons.Default.Groups) { section = Section.MEETINGS; scope.launch { drawerState.close() } }
                NovaDrawerItem(Section.GATEWAY, section, Icons.Default.History, "Workspace activity") { section = Section.GATEWAY; scope.launch { drawerState.close() } }
                NovaDrawerItem(Section.GATEWAY, section, Icons.Default.Psychology, "Workspace memory") { section = Section.GATEWAY; scope.launch { drawerState.close() } }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                NavigationDrawerItem(label = { Text("Model pools & routing") }, selected = false, icon = { Icon(Icons.Default.Tune, null) }, onClick = { scope.launch { drawerState.close() }; section = Section.GATEWAY }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
                NavigationDrawerItem(label = { Text("NOVA-GATEWAY workspace") }, selected = section == Section.GATEWAY, icon = { Icon(Icons.Default.Hub, null) }, onClick = { scope.launch { drawerState.close() }; section = Section.GATEWAY }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
                NovaDrawerItem(Section.INTEGRATIONS, section, Icons.Default.Extension) { section = Section.INTEGRATIONS; scope.launch { drawerState.close() } }
                NavigationDrawerItem(label = { Text("Application settings") }, selected = section == Section.SETTINGS, icon = { Icon(Icons.Default.Settings, null) }, onClick = { section = Section.SETTINGS; scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
                NavigationDrawerItem(label = { Text("AI assistant") }, selected = false, icon = { Icon(Icons.Default.AutoAwesome, null) }, onClick = { assistantExpanded = true; scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            }
        },
    ) {
      Scaffold(
        topBar = { TopAppBar(navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Open NOVA navigation") } }, title = { Column { Text("NOVA", fontWeight = FontWeight.Bold); Text(backendStatus, style = MaterialTheme.typography.labelSmall, maxLines = 1) } }, actions = { IconButton(onClick = { section = Section.GATEWAY }) { Icon(Icons.Default.Notifications, "Gateway activity") }; IconButton(onClick = { section = Section.GATEWAY }) { Icon(Icons.Default.Settings, "NOVA-GATEWAY account") } }) },
        bottomBar = { NavigationBar(containerColor = Color(0xFF0D1421)) { listOf(Section.HQ, Section.ASSISTANT, Section.AGENTS, Section.TASKS, Section.SETTINGS).forEach { item -> NavigationBarItem(selected = section == item, onClick = { section = item }, icon = { Icon(when(item){Section.HQ->Icons.Default.Business;Section.ASSISTANT->Icons.Default.Chat;Section.AGENTS->Icons.Default.Groups;Section.TASKS->Icons.Default.FolderOpen;else->Icons.Default.Settings}, item.title) }, label = { Text(item.title) }) } } }
      ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 68.dp)) {
            when (section) {
                Section.ASSISTANT -> {
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Your assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(status, style = MaterialTheme.typography.bodySmall); if (voiceStatus.isNotBlank()) Text(voiceStatus, style = MaterialTheme.typography.labelSmall) }
                            if (voiceStatus.startsWith("Head is speaking") || voiceStatus.startsWith("Speaking with the device") || voiceStatus.startsWith("Playing real")) TextButton(onClick = stopSpeech) { Text("Stop voice") }
                            TextButton(onClick = { section = Section.GATEWAY }) { Text("NOVA-GATEWAY") }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (chat.isEmpty()) item { EmptyState("Ready when you are", "Messages route through NOVA-GATEWAY. Sign in from the Gateway tab; offline responses are never fabricated.") }
                        items(chat) { line -> ChatBubble(line, speakResponse) }
                        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), label = { Text("Message NOVA") }, enabled = !busy, maxLines = 4)
                        Spacer(Modifier.width(8.dp)); IconButton(enabled = !busy && draft.isNotBlank(), onClick = sendAssistantMessage) { Icon(Icons.Default.Send, "Send message") }
                    }
                }
                Section.HOME -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Good day", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp)); Text("Your workspace is ready. Connect a backend to enable cloud-backed features."); DashboardCard("Assistant", "Start a conversation", Icons.Default.Chat) { section = Section.ASSISTANT }; DashboardCard("NOVA-GATEWAY", gatewaySession?.let { "Workspace ${it.workspaceId}" } ?: "Sign in or register", Icons.Default.Cloud) { section = Section.GATEWAY } }
                Section.HQ -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Headquarters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("${workforceAgents.size} workers · ${workforceAgents.count { it.status.equals("working", true) }} active · $backendStatus", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        TextButton(onClick = refreshWorkforce, enabled = !workforceBusy) { Text(if (workforceBusy) "Syncing" else "Sync") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        TwoFloorOfficeScene(workforceAgents, characters, providerConnections, workforceTasks, officeEvents, workerMessages, meetings, reduceMotion, onReduceMotionChange = { reduceMotion = it; settings.put("reduce_motion", it.toString()) }, modifier = Modifier.fillMaxSize(), onAgentSelected = { selectedOfficeAgent = it })
                        // The environment preview remains visible while offline; its footer clearly marks live data as unavailable.
                    }
                    if (workforceAgents.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                            items(workforceAgents) { agent ->
                                val currentTask = workforceTasks.firstOrNull { it.assignedAgentId == agent.id && it.state in listOf("PLANNING", "ASSIGNED", "RUNNING") }
                                AssistChip(onClick = { selectedOfficeAgent = agent }, label = { Column { Text(agent.name, maxLines = 1, style = MaterialTheme.typography.labelSmall); Text("${agent.title} · ${currentTask?.title ?: agentActivity(agent.status)}", maxLines = 1, style = MaterialTheme.typography.labelSmall) } }, leadingIcon = { Icon(Icons.Default.Person, null) })
                            }
                        }
                    } else Text(workforceNotice, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { section = Section.MEETINGS }) { Text("Meetings") }
                        TextButton(onClick = { section = Section.GATEWAY }) { Text("Gateway tasks") }
                        TextButton(enabled = officeWorld != null, onClick = { officeDraft = officeWorld?.copy(); showOfficeEditor = true }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text("Edit HQ") }
                    }
                }
                Section.AGENTS -> CharacterRosterScreen(
                    agents = workforceAgents,
                    profiles = characters,
                    tasks = workforceTasks,
                    onOpenGateway = { section = Section.GATEWAY },
                    onAgentSelected = { selectedOfficeAgent = it },
                )
                Section.TASKS -> GatewayProjectsScreen(
                    client = gatewayClient,
                    onOpenGateway = { section = Section.GATEWAY },
                    modifier = Modifier.fillMaxSize(),
                )
                Section.MEETINGS -> Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
                    Text("Meetings & shared context", style = MaterialTheme.typography.headlineSmall)
                    Text("Legacy meeting metadata only. AI participation buttons route you to NOVA-GATEWAY; this screen does not call provider models.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(meetingTitle, { meetingTitle = it }, Modifier.fillMaxWidth(), label = { Text("Meeting title") }, singleLine = true)
                    OutlinedTextField(meetingAgenda, { meetingAgenda = it }, Modifier.fillMaxWidth(), label = { Text("Agenda") }, maxLines = 2)
                    OutlinedTextField(meetingContext, { meetingContext = it }, Modifier.fillMaxWidth(), label = { Text("Shared project context") }, maxLines = 3)
                    OutlinedTextField(meetingScheduleMinutes, { meetingScheduleMinutes = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Schedule in minutes (0 = startable now)") }, singleLine = true)
                    Text("Invite workers", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(workforceAgents) { agent -> FilterChip(selected = agent.id in selectedMeetingParticipants, onClick = { if (agent.id in selectedMeetingParticipants) selectedMeetingParticipants.remove(agent.id) else if (selectedMeetingParticipants.size < 10) selectedMeetingParticipants.add(agent.id) }, label = { Text(agent.name) }) } }
                    if (workforceTasks.isNotEmpty()) {
                        Text("Optional linked task", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(workforceTasks) { task -> FilterChip(selected = selectedMeetingTaskId == task.id, onClick = { selectedMeetingTaskId = if (selectedMeetingTaskId == task.id) null else task.id }, label = { Text(task.title.take(22)) }) } }
                    }
                    Button(enabled = !workforceBusy && meetingTitle.isNotBlank() && meetingAgenda.isNotBlank() && selectedMeetingParticipants.isNotEmpty(), onClick = {
                        val title = meetingTitle.trim(); val agenda = meetingAgenda.trim(); val context = meetingContext.trim(); val attendees = selectedMeetingParticipants.toList(); val taskId = selectedMeetingTaskId; val minutes = meetingScheduleMinutes.toLongOrNull() ?: 0L
                        scope.launch { workforceBusy = true; try { withContext(Dispatchers.IO) { createMeeting(base, token, title, agenda, context, attendees, taskId, minutes) }; meetingTitle = ""; meetingAgenda = ""; meetingContext = ""; selectedMeetingParticipants.clear(); workforceNotice = "Meeting metadata saved. AI participation now routes through NOVA-GATEWAY Head and worker chat."; refreshWorkforce() } catch (error: Exception) { workforceNotice = error.message ?: "Meeting could not be scheduled" } finally { workforceBusy = false } }
                    }) { Text("Schedule meeting") }
                    Text(workforceNotice, style = MaterialTheme.typography.labelSmall)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(meetings) { meeting -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Text(meeting.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(meeting.status, style = MaterialTheme.typography.labelMedium) }
                            Text(meeting.agenda, style = MaterialTheme.typography.bodySmall)
                            if (meeting.scheduledAt != null) Text("Scheduled ${meeting.scheduledAt}", style = MaterialTheme.typography.labelSmall)
                            meeting.invitations.forEach { invite -> Text("${invite.name}: ${invite.status}", style = MaterialTheme.typography.labelSmall) }
                            meeting.messages.takeLast(6).forEach { line -> Text("${line.speaker}: ${line.content}", style = MaterialTheme.typography.bodySmall) }
                            Row {
                                if (meeting.status in listOf("scheduled", "invited")) TextButton(onClick = { section = Section.GATEWAY; workforceNotice = "Use gateway Head orchestration and worker chat for live AI participation." }) { Text("Open Gateway AI") }
                                if (meeting.status in listOf("scheduled", "invited", "running")) TextButton(enabled = !workforceBusy, onClick = { scope.launch { workforceBusy = true; try { withContext(Dispatchers.IO) { cancelMeeting(base, token, meeting.id) }; workforceNotice = "Cancellation requested."; refreshWorkforce() } catch (error: Exception) { workforceNotice = error.message ?: "Could not cancel" } finally { workforceBusy = false } } }) { Text("Cancel") }
                                if (meeting.invitations.isNotEmpty() && workforceTasks.isNotEmpty()) TextButton(onClick = { handoffMeeting = meeting; handoffTaskId = null; handoffAgentId = meeting.invitations.firstOrNull()?.agentId; handoffInstructions = "" }) { Text("Task handoff") }
                            }
                        } } }
                    }
                }
                Section.ACTIVITY -> Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Activity", style = MaterialTheme.typography.headlineSmall); Text("Owner-scoped persisted backend events", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = refreshWorkforce) { Text("Sync") }
                    }
                    if (officeEvents.isEmpty()) EmptyState("No recorded activity", "Events appear here after real worker, task, meeting or provider actions.")
                    else LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                        items(officeEvents) { event -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(event.type.replace('.', ' ').replace('_', ' '), style = MaterialTheme.typography.titleSmall)
                            Text("${event.payload} · ${event.createdAt}", style = MaterialTheme.typography.bodySmall)
                            if (event.taskId != null) Text("Task ${event.taskId.take(8)}", style = MaterialTheme.typography.labelSmall)
                        } } }
                    }
                }
                Section.MEMORY -> Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Memory", style = MaterialTheme.typography.headlineSmall); Text(memoryStatus, style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = refreshMemories) { Text("Sync") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("headquarters", "agent", "project").forEach { scopeName -> FilterChip(selected = memoryScope == scopeName, onClick = { memoryScope = scopeName }, label = { Text(scopeName.replaceFirstChar { it.uppercase() }) }) }
                    }
                    if (memoryScope == "agent" && workforceAgents.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { items(workforceAgents) { agent -> FilterChip(selected = memoryAgentId == agent.id, onClick = { memoryAgentId = agent.id }, label = { Text(agent.name) }) } }
                    }
                    if (memoryScope == "project") OutlinedTextField(memoryProjectKey, { memoryProjectKey = it }, Modifier.fillMaxWidth(), label = { Text("Project key") }, singleLine = true)
                    OutlinedTextField(memoryDraft, { memoryDraft = it }, Modifier.fillMaxWidth(), label = { Text("Save a memory") }, minLines = 2, maxLines = 4)
                    Button(onClick = saveMemory, enabled = memoryDraft.isNotBlank() && (memoryScope != "agent" || memoryAgentId != null) && (memoryScope != "project" || memoryProjectKey.isNotBlank())) { Text("Save to NOVA memory") }
                    if (memories.isEmpty()) EmptyState("No saved memories", "Memory is separate per worker, project, or headquarters and is only read from this authenticated backend.")
                    else LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) { items(memories) { memory -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("${memory.scope} · importance ${String.format(Locale.US, "%.1f", memory.importance)}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); IconButton(onClick = { scope.launch { try { withContext(Dispatchers.IO) { deleteMemory(base, token, memory.id) }; refreshMemories() } catch (error: Exception) { memoryStatus = error.message ?: "Could not delete memory" } } }) { Icon(Icons.Default.DeleteOutline, "Delete memory") } }
                        Text(memory.content, style = MaterialTheme.typography.bodySmall)
                        memory.projectKey?.let { Text("Project $it", style = MaterialTheme.typography.labelSmall) }
                    } } } }
                }
                Section.INTEGRATIONS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Gateway integrations", style = MaterialTheme.typography.headlineSmall)
                    Text("All provider gateway registration and model discovery must use NOVA-GATEWAY. This app does not send provider credentials to the legacy NOVA backend.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { section = Section.GATEWAY }) { Text("Open NOVA-GATEWAY") }
                    Text("GitHub, IDE control, and external OAuth integrations are not configured by this screen.", style = MaterialTheme.typography.bodySmall)
                }
                Section.GATEWAY -> GatewayWorkspaceScreen(gatewayClient, Modifier.fillMaxSize(), onSessionChanged = { gatewaySession = it })
                Section.SETTINGS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Settings center", style = MaterialTheme.typography.headlineSmall)
                    SettingsCategory("NOVA-GATEWAY account", gatewaySession?.let { "Workspace ${it.workspaceId} · JWTs encrypted with Android Keystore" } ?: "Not signed in · AI features require NOVA-GATEWAY login", "Open gateway") { section = Section.GATEWAY }
                    SettingsCategory("Gateway session", gatewaySession?.let { "Workspace ${it.workspaceId} · secure JWT session" } ?: "Authentication required", "Sign in / manage") { section = Section.GATEWAY }
                    SettingsCategory("AI providers & gateways", "Gateway registration, model discovery, verified pools, and routing are managed only by NOVA-GATEWAY.", "Open NOVA-GATEWAY") { section = Section.GATEWAY }
                    SettingsCategory("Head & workers", "${gatewaySession?.workspaceId?.let { "Gateway workspace $it" } ?: "Sign in to load gateway workers"} · assignments and chat route through NOVA-GATEWAY", "Open gateway workers") { section = Section.GATEWAY }
                    SettingsCategory("Character profiles", "NOVA-GATEWAY's supplied API contract does not expose character appearance/personality endpoints, so profile editing cannot sync to this gateway yet.")
                    SettingsCategory("Model pools & routing", "Head Claude-family pool and verified non-Claude worker pool are loaded from NOVA-GATEWAY.", "Manage in gateway") { section = Section.GATEWAY }
                    SettingsCategory("External integrations", "Gateway registration is handled in NOVA-GATEWAY. GitHub, IDEs, OAuth integrations, and external actions are not connected by this build.", "Gateway integrations") { section = Section.INTEGRATIONS }
                    SettingsCategory("Memory & data", "Workspace memory and shared/personal records are managed by NOVA-GATEWAY.", "Manage memory") { section = Section.GATEWAY }
                    SettingsCategory("Notifications", "Push notifications are not implemented; NOVA does not claim background notification delivery.")
                    Card { Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Appearance & animations", style = MaterialTheme.typography.titleSmall); Text("OLED-friendly palette · ${if (reduceMotion) "reduced motion" else "ambient office animation"}", style = MaterialTheme.typography.bodySmall) }; Switch(checked = reduceMotion, onCheckedChange = { reduceMotion = it; settings.put("reduce_motion", it.toString()) }) } }
                    SettingsCategory("Security & permissions", "Gateway access token, refresh token, and workspace ID are encrypted with Android Keystore. Provider API keys are sent only to NOVA-GATEWAY over HTTPS and are not persisted by this app.")
                    SettingsCategory("Advanced configuration", "$usageSummary\n$analyticsNote")
                }
            }
        }
        CompactAssistantDock(
            mood = if (assistantListening) AssistantMood.LISTENING else if (assistantResponding) AssistantMood.RESPONDING else if (busy) AssistantMood.THINKING else AssistantMood.IDLE,
            draft = draft,
            onDraftChange = { draft = it },
            enabled = !busy,
            onOrbClick = { assistantExpanded = true },
            onSend = sendAssistantMessage,
            onAttach = { attachmentLauncher.launch(arrayOf("text/plain", "text/markdown", "application/json", "text/csv")) },
            onVoice = {
                assistantListening = true
                try { voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
catch (_: Exception) { assistantListening = false; voiceStatus = "Speech recognition is unavailable on this device." }
            },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )
        }
      }
    }
    if (assistantExpanded) ModalBottomSheet(onDismissRequest = { assistantExpanded = false }, containerColor = Color(0xFF101827)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantOrb(if (assistantListening) AssistantMood.LISTENING else if (assistantResponding) AssistantMood.RESPONDING else if (busy) AssistantMood.THINKING else AssistantMood.IDLE, diameter = 48.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text("NOVA assistant", style = MaterialTheme.typography.titleLarge); Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                if (voiceStatus.startsWith("Head is speaking") || voiceStatus.startsWith("Speaking with the device") || voiceStatus.startsWith("Playing real")) IconButton(onClick = stopSpeech) { Icon(Icons.Default.Stop, "Stop voice playback") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chat.isEmpty()) item { EmptyState("Ready when you are", "Responses use your configured NOVA backend. Offline states are shown rather than fabricated.") }
                items(chat) { line -> ChatBubble(line, speakResponse) }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            CompactAssistantDock(mood = if (assistantListening) AssistantMood.LISTENING else if (assistantResponding) AssistantMood.RESPONDING else if (busy) AssistantMood.THINKING else AssistantMood.IDLE, draft = draft, onDraftChange = { draft = it }, enabled = !busy, onOrbClick = { assistantExpanded = false }, onSend = sendAssistantMessage, onAttach = { attachmentLauncher.launch(arrayOf("text/plain", "text/markdown", "application/json", "text/csv")) }, onVoice = { assistantListening = true; try { voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) } catch (_: Exception) { assistantListening = false; voiceStatus = "Speech recognition is unavailable on this device." } }, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp))
        }
    }
    if (showCharacters) {
        val selectedCharacter = characters.firstOrNull { it.key == selectedCharacterKey }
        var characterName by remember(selectedCharacter?.key) { mutableStateOf(selectedCharacter?.name.orEmpty()) }
        var characterPersonality by remember(selectedCharacter?.key) { mutableStateOf(selectedCharacter?.personality.orEmpty()) }
        var characterSpecialization by remember(selectedCharacter?.key) { mutableStateOf(selectedCharacter?.specialization.orEmpty()) }
        var characterStyle by remember(selectedCharacter?.key) { mutableStateOf(selectedCharacter?.communicationStyle.orEmpty()) }
        var characterVoiceId by remember(selectedCharacter?.key) { mutableStateOf(selectedCharacter?.voiceId.orEmpty()) }
        var voiceCheckStatus by remember(selectedCharacter?.key) { mutableStateOf("") }
        var voiceSpeed by remember(selectedCharacter?.key) { mutableFloatStateOf(selectedCharacter?.speed ?: 0.96f) }
        var voiceStability by remember(selectedCharacter?.key) { mutableFloatStateOf(selectedCharacter?.stability ?: 0.55f) }
        var voiceStyle by remember(selectedCharacter?.key) { mutableFloatStateOf(selectedCharacter?.style ?: 0.25f) }
        var voiceSimilarity by remember(selectedCharacter?.key) { mutableFloatStateOf(selectedCharacter?.similarityBoost ?: 0.75f) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        DisposableEffect(Unit) { onDispose { player?.stop(); player?.release(); player = null } }
        AlertDialog(
            onDismissRequest = { if (!characterBusy) showCharacters = false },
            title = { Text(if (selectedCharacter?.key == "head") "Head character profile" else "Worker character profile") },
            text = {
                Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (characters.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(characters) { profile -> FilterChip(selected = selectedCharacterKey == profile.key, onClick = { selectedCharacterKey = profile.key }, label = { Text(if (profile.key == "head") "Head · ${profile.name}" else profile.name) }) }
                    }
                    if (selectedCharacter == null) Text(if (characterBusy) "Loading…" else "No profile selected. Connect the NOVA backend and refresh.")
                    else {
                        Text("${if (selectedCharacter.key == "head") "Head" else "Worker"} · ${selectedCharacter.gender.replaceFirstChar { it.uppercase() }} · ${selectedCharacter.availability}", style = MaterialTheme.typography.labelMedium)
                        Text("Configured provider/model (not gateway-verified): ${selectedCharacter.provider ?: "unavailable"} / ${selectedCharacter.model ?: "unavailable"} · Appearance: ${selectedCharacter.appearance.ifBlank { "Not specified" }}", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(characterName, { characterName = it }, label = { Text("Character name") }, singleLine = true, enabled = !characterBusy)
                        OutlinedTextField(characterPersonality, { characterPersonality = it }, label = { Text("Personality") }, minLines = 2, maxLines = 4, enabled = !characterBusy)
                        OutlinedTextField(characterSpecialization, { characterSpecialization = it }, label = { Text("Specialization") }, minLines = 2, maxLines = 3, enabled = !characterBusy)
                        OutlinedTextField(characterStyle, { characterStyle = it }, label = { Text("Communication style") }, minLines = 2, maxLines = 3, enabled = !characterBusy)
                        Text("Voice customization · ElevenLabs", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(characterVoiceId, { characterVoiceId = it.trim(); voiceCheckStatus = "" }, label = { Text("Voice ID") }, singleLine = true, enabled = !characterBusy, supportingText = { Text("Provider credentials remain on the NOVA server. Voice ID must have explicit matching gender metadata.") })
                        Text("Speed ${"%.2f".format(voiceSpeed)}"); Slider(value = voiceSpeed, onValueChange = { voiceSpeed = it }, valueRange = 0.7f..1.2f, enabled = !characterBusy)
                        Text("Stability ${"%.2f".format(voiceStability)}"); Slider(value = voiceStability, onValueChange = { voiceStability = it }, valueRange = 0f..1f, enabled = !characterBusy)
                        Text("Style ${"%.2f".format(voiceStyle)}"); Slider(value = voiceStyle, onValueChange = { voiceStyle = it }, valueRange = 0f..1f, enabled = !characterBusy)
                        Text("Similarity boost ${"%.2f".format(voiceSimilarity)}"); Slider(value = voiceSimilarity, onValueChange = { voiceSimilarity = it }, valueRange = 0f..1f, enabled = !characterBusy)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = !characterBusy && characterVoiceId.isNotBlank(), onClick = { characterBusy = true; voiceCheckStatus = "Checking Voice ID with configured ElevenLabs service…"; scope.launch { try { val result = withContext(Dispatchers.IO) { validateCharacterVoice(base, token, selectedCharacter.key, characterVoiceId) }; voiceCheckStatus = "Valid · ${result.optString("voice_name")} · ${result.optString("gender")}" } catch (e: Exception) { voiceCheckStatus = e.message ?: "Voice validation failed" } finally { characterBusy = false } } }) { Text("Validate") }
                            TextButton(enabled = !characterBusy && characterVoiceId.isNotBlank(), onClick = { characterBusy = true; voiceCheckStatus = "Requesting actual audio preview…"; scope.launch { try { val audio = withContext(Dispatchers.IO) { previewCharacterVoice(base, token, selectedCharacter.key, characterVoiceId, "Hello, I'm ${characterName.ifBlank { "ready to help" }}.", voiceSpeed, voiceStability, voiceStyle, voiceSimilarity) }; val file = java.io.File(context.cacheDir, "nova-character-preview.mp3"); file.writeBytes(audio); player?.stop(); player?.release(); player = MediaPlayer().apply { setDataSource(file.absolutePath); setOnCompletionListener { voiceCheckStatus = "Preview complete." }; prepare(); start() }; voiceCheckStatus = "Playing real ElevenLabs preview audio." } catch (e: Exception) { voiceCheckStatus = e.message ?: "Voice preview unavailable; use text or device TTS." } finally { characterBusy = false } } }) { Text("Preview") }
                            TextButton(enabled = !characterBusy, onClick = { characterBusy = true; scope.launch { try { val profile = withContext(Dispatchers.IO) { resetCharacterVoice(base, token, selectedCharacter.key) }; replaceCharacter(characters, profile); characterVoiceId = ""; voiceCheckStatus = "Voice reset to default; this character's settings do not affect any other voice." } catch (e: Exception) { voiceCheckStatus = e.message ?: "Could not reset voice" } finally { characterBusy = false } } }) { Text("Reset") }
                        }
                        if (voiceCheckStatus.isNotBlank()) Text(voiceCheckStatus, style = MaterialTheme.typography.bodySmall, color = if (voiceCheckStatus.startsWith("Valid") || voiceCheckStatus.startsWith("Playing") || voiceCheckStatus.startsWith("Preview complete")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text("${if (selectedCharacter.voiceReady) "Server voice configured" else "ElevenLabs voice unavailable or not configured"} · text/device-TTS fallback remains available. Preview and speak-aloud send the selected text to the configured provider; review provider terms and obtain user consent.", style = MaterialTheme.typography.labelSmall)
                    }
                    if (characterNotice.isNotBlank()) Text(characterNotice, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(enabled = selectedCharacter != null && !characterBusy && characterName.isNotBlank(), onClick = { val current = selectedCharacter!!; characterBusy = true; characterNotice = "Saving character identity…"; scope.launch { try { val result = withContext(Dispatchers.IO) { saveCharacterProfile(base, token, current.key, characterName, characterPersonality, characterSpecialization, characterStyle) }; replaceCharacter(characters, result); characterNotice = "${result.name} identity saved." } catch (e: Exception) { characterNotice = e.message ?: "Could not save character profile" } finally { characterBusy = false } } }) { Text("Save profile") } },
            dismissButton = { Row { TextButton(enabled = selectedCharacter != null && !characterBusy && characterVoiceId.isNotBlank(), onClick = { val current = selectedCharacter!!; characterBusy = true; voiceCheckStatus = "Saving independently to this character…"; scope.launch { try { val result = withContext(Dispatchers.IO) { saveCharacterVoice(base, token, current.key, characterVoiceId, voiceSpeed, voiceStability, voiceStyle, voiceSimilarity) }; replaceCharacter(characters, result); voiceCheckStatus = "Voice saved for ${result.name} only." } catch (e: Exception) { voiceCheckStatus = e.message ?: "Voice save failed" } finally { characterBusy = false } } }) { Text("Save voice") }; TextButton(enabled = !characterBusy, onClick = { showCharacters = false }) { Text("Close") } } }
        )
    }
    customizingAppearanceFor?.let { profile ->
        val current = characters.firstOrNull { it.key == profile.key } ?: profile
        val saved = remember(profile.key) { runCatching { JSONObject(profile.appearanceJson) }.getOrElse { JSONObject() } }
        var description by remember(profile.key) { mutableStateOf(saved.optString("description")) }
        var faceShape by remember(profile.key) { mutableStateOf(saved.optString("face_shape")) }
        var hairStyle by remember(profile.key) { mutableStateOf(saved.optString("hair_style")) }
        var hairColor by remember(profile.key) { mutableStateOf(saved.optString("hair_color")) }
        var skinColor by remember(profile.key) { mutableStateOf(saved.optString("skin_color")) }
        var eyeColor by remember(profile.key) { mutableStateOf(saved.optString("eye_color")) }
        var clothing by remember(profile.key) { mutableStateOf(saved.optString("clothing")) }
        var clothingColor by remember(profile.key) { mutableStateOf(saved.optString("clothing_color")) }
        var accessory by remember(profile.key) { mutableStateOf(saved.optString("accessory")) }
        AlertDialog(
            onDismissRequest = { if (!appearanceBusy) customizingAppearanceFor = null },
            title = { Text("Customize ${current.name}'s appearance") },
            text = {
                Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Visual-only settings. Model assignment, personality, instructions, and memory remain unchanged.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(description, { description = it }, label = { Text("Appearance description") }, minLines = 2, maxLines = 4)
                    OutlinedTextField(faceShape, { faceShape = it }, label = { Text("Face shape · oval, square, round, heart, long, diamond") }, singleLine = true)
                    OutlinedTextField(hairStyle, { hairStyle = it }, label = { Text("Hair style") }, singleLine = true)
                    OutlinedTextField(hairColor, { hairColor = it }, label = { Text("Hair color · #RRGGBB") }, singleLine = true)
                    OutlinedTextField(skinColor, { skinColor = it }, label = { Text("Skin color · #RRGGBB") }, singleLine = true)
                    OutlinedTextField(eyeColor, { eyeColor = it }, label = { Text("Eye color · #RRGGBB") }, singleLine = true)
                    OutlinedTextField(clothing, { clothing = it }, label = { Text("Clothing") }, singleLine = true)
                    OutlinedTextField(clothingColor, { clothingColor = it }, label = { Text("Clothing color · #RRGGBB") }, singleLine = true)
                    OutlinedTextField(accessory, { accessory = it }, label = { Text("Accessory") }, singleLine = true)
                    if (appearanceNotice.isNotBlank()) Text(appearanceNotice, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(enabled = !appearanceBusy, onClick = {
                val appearance = JSONObject(saved.toString()).put("description", description.trim()).put("face_shape", faceShape.trim()).put("hair_style", hairStyle.trim()).put("hair_color", hairColor.trim()).put("skin_color", skinColor.trim()).put("eye_color", eyeColor.trim()).put("clothing", clothing.trim()).put("clothing_color", clothingColor.trim()).put("accessory", accessory.trim())
                appearanceBusy = true
                scope.launch {
                    try {
                        val updated = withContext(Dispatchers.IO) { saveCharacterAppearance(base, token, current.key, appearance) }
                        replaceCharacter(characters, updated)
                        customizingAppearanceFor = null
                        appearanceNotice = "Appearance saved separately. Model, personality, instructions, and memory were not changed."
                    } catch (e: Exception) { appearanceNotice = e.message ?: "Could not save appearance" }
                    finally { appearanceBusy = false }
                }
            }) { Text(if (appearanceBusy) "Saving…" else "Save appearance") } },
            dismissButton = { TextButton(enabled = !appearanceBusy, onClick = { customizingAppearanceFor = null }) { Text("Cancel") } },
        )
    }
    editingAgent?.let { agent ->
        AlertDialog(
            onDismissRequest = { editingAgent = null },
            title = { Text("${agent.name} · model routing") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The legacy NOVA provider/model editor is disabled. Worker model identity and routing must be assigned from NOVA-GATEWAY's verified non-Claude pool.", style = MaterialTheme.typography.bodySmall)
                Text("This screen does not save a provider API key, model ID, or alternate route.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { editingAgent = null; section = Section.GATEWAY }) { Text("Open NOVA-GATEWAY") } },
            dismissButton = { TextButton(onClick = { editingAgent = null }) { Text("Close") } },
        )
    }
    if (showOfficeEditor) {
        val draft = officeDraft
        AlertDialog(
            onDismissRequest = { if (!officeEditorBusy) { showOfficeEditor = false; officeDraft = null } },
            title = { Text("Persisted office layout editor") },
            text = {
                Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Grid ${draft?.width ?: 0} × ${draft?.height ?: 0}. Room/furniture geometry is validated and saved by the NOVA backend; invalid collisions are rejected.", style = MaterialTheme.typography.bodySmall)
                    draft?.rooms?.forEach { room ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(room.name, fontWeight = FontWeight.SemiBold)
                                Row {
                                    OfficeStepper("x", room.x, { value -> officeDraft = officeDraft?.copy(rooms = officeDraft!!.rooms.map { if (it.id == room.id) it.copy(x = value) else it }) }, Modifier.weight(1f))
                                    OfficeStepper("y", room.y, { value -> officeDraft = officeDraft?.copy(rooms = officeDraft!!.rooms.map { if (it.id == room.id) it.copy(y = value) else it }) }, Modifier.weight(1f))
                                }
                                Row {
                                    OfficeStepper("width", room.width, { value -> officeDraft = officeDraft?.copy(rooms = officeDraft!!.rooms.map { if (it.id == room.id) it.copy(width = value) else it }) }, Modifier.weight(1f))
                                    OfficeStepper("height", room.height, { value -> officeDraft = officeDraft?.copy(rooms = officeDraft!!.rooms.map { if (it.id == room.id) it.copy(height = value) else it }) }, Modifier.weight(1f))
                                }
                                Row {
                                    OfficeStepper("door x", room.entrance.x, { value -> officeDraft = officeDraft?.copy(rooms = officeDraft!!.rooms.map { if (it.id == room.id) it.copy(entrance = it.entrance.copy(x = value)) else it }) }, Modifier.weight(1f))
                                    OfficeStepper("door y", room.entrance.y, { value -> officeDraft = officeDraft?.copy(rooms = officeDraft!!.rooms.map { if (it.id == room.id) it.copy(entrance = it.entrance.copy(y = value)) else it }) }, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Text("Furniture · ${draft?.furniture?.size ?: 0}", style = MaterialTheme.typography.titleSmall)
                    draft?.furniture?.forEach { furniture ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${furniture.kind} · ${furniture.id.take(8)}", fontWeight = FontWeight.SemiBold)
                                Row {
                                    OfficeStepper("x", furniture.x, { value -> officeDraft = officeDraft?.copy(furniture = officeDraft!!.furniture.map { if (it.id == furniture.id) it.copy(x = value) else it }) }, Modifier.weight(1f))
                                    OfficeStepper("y", furniture.y, { value -> officeDraft = officeDraft?.copy(furniture = officeDraft!!.furniture.map { if (it.id == furniture.id) it.copy(y = value) else it }) }, Modifier.weight(1f))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${furniture.width} × ${furniture.height} · ${furniture.rotation}°", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { officeDraft = officeDraft?.copy(furniture = officeDraft!!.furniture.map { if (it.id == furniture.id) it.copy(rotation = (it.rotation + 90) % 360) else it }) }) { Text("Rotate") }
                                    TextButton(onClick = { officeDraft = officeDraft?.copy(furniture = officeDraft!!.furniture.filterNot { it.id == furniture.id }) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                    if (officeEditorNotice.isNotBlank()) Text(officeEditorNotice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("Add furniture at a grid cell", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(listOf("desk", "chair", "computer", "monitor", "whiteboard", "meeting_table", "server_rack", "coffee_machine", "project_board", "memory_core")) { kind ->
                            FilterChip(selected = officeEditorKind == kind, onClick = { officeEditorKind = kind }, label = { Text(kind) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = officeEditorX, onValueChange = { officeEditorX = it.filter { character -> character.isDigit() }.take(3) }, modifier = Modifier.weight(1f), label = { Text("x") }, singleLine = true)
                        OutlinedTextField(value = officeEditorY, onValueChange = { officeEditorY = it.filter { character -> character.isDigit() }.take(3) }, modifier = Modifier.weight(1f), label = { Text("y") }, singleLine = true)
                        TextButton(enabled = officeEditorX.isNotBlank() && officeEditorY.isNotBlank(), onClick = {
                            val x = officeEditorX.toIntOrNull() ?: return@TextButton
                            val y = officeEditorY.toIntOrNull() ?: return@TextButton
                            val item = OfficeFurnitureInfo(java.util.UUID.randomUUID().toString(), officeEditorKind, x, y, 1, 1, 0)
                            officeDraft = officeDraft?.copy(furniture = officeDraft!!.furniture + item)
                        }) { Text("Add") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !officeEditorBusy && officeDraft != null, onClick = {
                    val submitted = officeDraft ?: return@TextButton
                    officeEditorBusy = true
                    scope.launch {
                        try {
                            val saved = withContext(Dispatchers.IO) { saveOfficeLayout(base, token, submitted) }
                            officeWorld = saved; officeDraft = null; showOfficeEditor = false
                            workforceNotice = "Office layout and furniture placements saved by the backend."
                        } catch (e: Exception) { officeEditorNotice = e.message ?: "Office layout was rejected; saved layout remains unchanged." }
                        finally { officeEditorBusy = false }
                    }
                }) { Text(if (officeEditorBusy) "Saving…" else "Save layout") }
            },
            dismissButton = { TextButton(enabled = !officeEditorBusy, onClick = { showOfficeEditor = false; officeDraft = null }) { Text("Cancel") } }
        )
    }
    selectedOfficeAgent?.let { agent ->
        AlertDialog(onDismissRequest = { selectedOfficeAgent = null }, title = { Text(agent.name) }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val profileKey = if (agent.identity == "manager") "head" else agent.identity
                val identityProfile = characters.firstOrNull { it.key == profileKey }
                val verifiedModel = verifiedModelIdentity(agent, providerConnections)
                val currentTask = workforceTasks.firstOrNull { (it.assignedAgentId == agent.id || it.requestedAgentId == agent.id) && it.state in listOf("PLANNING", "ASSIGNED", "RUNNING") }
                val modelStatus = modelConnectionStatus(agent, currentTask, providerConnections)
                Text(agent.title, style = MaterialTheme.typography.titleSmall)
                Text("Identity: ${agent.identity} · ${agent.status} · ${identityProfile?.gender ?: "gender not configured"}${identityProfile?.age?.let { " · age $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                Text("Assigned AI model: ${verifiedModel ?: "Model identity unverified"}", style = MaterialTheme.typography.bodySmall)
                Text("Model status: $modelStatus · independent of gateway label", style = MaterialTheme.typography.bodySmall)
                Text("Personality: ${identityProfile?.personality ?: "Not configured"}", style = MaterialTheme.typography.bodySmall)
                Text("Appearance: ${identityProfile?.appearance ?: "Not configured"}", style = MaterialTheme.typography.bodySmall)
                Text("Voice: ${identityProfile?.voiceId ?: "default/device fallback"} · ${if (identityProfile?.voiceReady == true) "configured" else "not verified"}", style = MaterialTheme.typography.bodySmall)
                val savedPosition = officeWorld?.characters?.get(agent.id)
                val station = officeWorld?.furniture?.firstOrNull { it.id == "workstation-${agent.identity}" }
                val location = savedPosition?.let { p -> officeWorld?.rooms?.firstOrNull { p.x in it.x until it.x + it.width && p.y in it.y until it.y + it.height }?.name }
                Text("Personal workstation: ${station?.id ?: "not saved"} · ${location ?: "location unavailable"} · desk/computer/monitor/keyboard/mouse/chair", style = MaterialTheme.typography.bodySmall)
                Text("Specialization: ${identityProfile?.specialization ?: agent.title}", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = identityProfile != null, onClick = { customizingAppearanceFor = identityProfile; appearanceNotice = "Appearance changes are saved separately and do not alter model, personality, or memory." }) { Text("Customize appearance") }
                Text("Current activity: ${currentTask?.let { "${it.state.lowercase()} · ${it.title}" } ?: agentActivity(agent.status)}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { requestedTaskAgentId = agent.id; selectedOfficeAgent = null; section = Section.GATEWAY }) { Text("Assign a gateway task to ${agent.name}") }
                TextButton(onClick = { selectedOfficeAgent = null; section = Section.AGENTS; editingAgent = agent }) { Text("Worker settings & controls") }
                Text("Specialties: ${agent.specialties.joinToString().ifBlank { "Not configured" }}", style = MaterialTheme.typography.bodySmall)
                Text("Tools: ${agent.permissions.joinToString().ifBlank { "None enabled" }}", style = MaterialTheme.typography.bodySmall)
                Text("Saved position: ${officeWorld?.characters?.get(agent.id)?.let { "${it.x}, ${it.y} · ${it.movementState}" } ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                val agentTaskHistory = workforceTasks.filter { it.assignedAgentId == agent.id || it.requestedAgentId == agent.id }.takeLast(8)
                Text("Task history · ${agentTaskHistory.size} saved", style = MaterialTheme.typography.labelMedium)
                agentTaskHistory.forEach { Text("${it.state} · ${it.title}${it.progressStage.takeIf(String::isNotBlank)?.let { stage -> " · $stage ${it.progressPercent}%" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
                val agentMemories = memories.filter { it.scope == "agent" && it.agentId == agent.id }.takeLast(4)
                Text("Personal saved memory · ${agentMemories.size} loaded", style = MaterialTheme.typography.labelMedium)
                agentMemories.forEach { Text("• ${it.content.take(240)}", style = MaterialTheme.typography.bodySmall) }
                if (agentMemories.isEmpty()) Text("Open Memory to sync or manage this worker's backend-persisted notes.", style = MaterialTheme.typography.bodySmall)
                val relatedEvents = officeEvents.filter { event -> event.agentId == agent.id || runCatching { JSONObject(event.payload).optString("recipient_agent_id") == agent.id }.getOrDefault(false) }.takeLast(6)
                Text("Recent recorded communication/activity", style = MaterialTheme.typography.labelMedium)
                relatedEvents.forEach { Text("${it.type.replace('.', ' ')} · ${it.createdAt}", style = MaterialTheme.typography.bodySmall) }
                val messageNames = workforceAgents.associate { it.id to it.name }
                workerMessages.filter { it.senderAgentId == agent.id || it.recipientAgentId == agent.id }.takeLast(8).forEach { message ->
                    val sender = messageNames[message.senderAgentId] ?: "Worker"
                    val recipient = message.recipientAgentId?.let { messageNames[it] } ?: "Head Manager"
                    Text("$sender → $recipient · ${message.messageType}: ${message.content.take(220)}", style = MaterialTheme.typography.bodySmall)
                }
                meetings.filter { meeting -> meeting.invitations.any { it.agentId == agent.id } }.takeLast(2).forEach { meeting ->
                    Text("${meeting.title} · ${meeting.status}", style = MaterialTheme.typography.labelSmall)
                    meeting.messages.takeLast(2).forEach { line -> Text("${line.speaker}: ${line.content.take(180)}", style = MaterialTheme.typography.bodySmall) }
                }
                Text("Send employee to a real room entrance:", style = MaterialTheme.typography.labelMedium)
                officeWorld?.rooms?.forEach { room ->
                    TextButton(enabled = !workforceBusy, onClick = {
                        workforceBusy = true
                        scope.launch {
                            var refresh = false
                            try {
                                withContext(Dispatchers.IO) { requestOfficeMove(base, token, agent.id, room.entrance) }
                                selectedOfficeAgent = null
                                workforceNotice = "${agent.name} movement routed to ${room.name}. The backend persists each tile step."
                                refresh = true
                            } catch (e: Exception) { workforceNotice = e.message ?: "Movement request failed" }
                            finally { workforceBusy = false; if (refresh) refreshWorkforce() }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("${room.name} · ${room.entrance.x},${room.entrance.y}") }
                }
                if (agent.instructions.isNotBlank()) Text("Instructions: ${agent.instructions}", style = MaterialTheme.typography.bodySmall)
                Text("Direct employee chat · saved on the backend", style = MaterialTheme.typography.titleSmall)
                agentChatLines.takeLast(12).forEach { line ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (line.fromUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Text("${if (line.fromUser) "You" else agent.name}: ${line.text}", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(agentChatStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = agentChatDraft, onValueChange = { agentChatDraft = it }, modifier = Modifier.weight(1f), label = { Text("Message ${agent.name}") }, enabled = !agentChatBusy, maxLines = 3)
                    IconButton(enabled = !agentChatBusy && agentChatDraft.isNotBlank() && token.isNotBlank(), onClick = {
                        val prompt = agentChatDraft.trim()
                        agentChatDraft = ""
                        agentChatLines.add(ChatLine(prompt, true))
                        agentChatBusy = true
                        agentChatStatus = "Waiting for NOVA-GATEWAY worker routing…"
                        scope.launch {
                            try {
                                if (gatewayClient.session() == null) throw IOException("Sign in to NOVA-GATEWAY before chatting with a worker.")
                                val result = withContext(Dispatchers.IO) {
                                    val gatewayWorker = gatewayWorkerId(gatewayClient, agent.identity, agent.id)
                                    AgentChatReply(gatewayWorkerChat(gatewayClient, gatewayWorker, prompt))
                                }
                                if (selectedOfficeAgent?.id == agent.id) {
                                    agentChatLines.add(ChatLine(result.response, false))
                                    agentChatStatus = "Response returned through NOVA-GATEWAY · saved"
                                }
                            } catch (e: Exception) {
                                if (selectedOfficeAgent?.id == agent.id) agentChatStatus = e.message ?: "Employee chat request failed; no response was fabricated."
                            } finally { agentChatBusy = false }
                        }
                    }) { if (agentChatBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Send, "Send employee message") }
                }
            }
        }, confirmButton = { TextButton(onClick = { selectedOfficeAgent = null; editingAgent = agent }) { Text("Configure identity") } }, dismissButton = { TextButton(onClick = { selectedOfficeAgent = null }) { Text("Close") } })
    }
    handoffMeeting?.let { meeting -> AlertDialog(
        onDismissRequest={handoffMeeting=null},
        title={Text("Task handoff · ${meeting.title}")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Choose a non-running task and one invited employee. NOVA appends this decision to the task context and records the handoff message.",style=MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(workforceTasks.filter { it.state !in listOf("RUNNING","PLANNING","ASSIGNED","COMPLETED","CANCELLED") }) { task -> FilterChip(selected=handoffTaskId==task.id,onClick={handoffTaskId=task.id},label={Text(task.title.take(20))}) } }
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(meeting.invitations) { invite -> FilterChip(selected=handoffAgentId==invite.agentId,onClick={handoffAgentId=invite.agentId},label={Text(invite.name)}) } }
            OutlinedTextField(handoffInstructions,{handoffInstructions=it},label={Text("Handoff instructions")},maxLines=4)
        }},
        confirmButton={TextButton(enabled=!workforceBusy && handoffTaskId!=null && handoffAgentId!=null && handoffInstructions.isNotBlank(),onClick={val task=handoffTaskId!!;val agent=handoffAgentId!!;val instructions=handoffInstructions.trim();scope.launch { workforceBusy=true;try { withContext(Dispatchers.IO) { createMeetingHandoff(base,token,meeting.id,task,agent,instructions) };handoffMeeting=null;workforceNotice="Meeting decisions appended to the queued task and sent to the selected employee." } catch(e:Exception){workforceNotice=e.message?:"Task handoff failed"} finally { workforceBusy=false;refreshWorkforce()} }}) { Text("Save handoff") }},
        dismissButton={TextButton(onClick={handoffMeeting=null}) { Text("Close") }}
    ) }
}

@Composable private fun ChatBubble(line: ChatLine, onSpeak: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(line.text)
                if (!line.fromUser) TextButton(onClick = { onSpeak(line.text) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.VolumeUp, "Read response aloud", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Speak")
                }
            }
        }
    }
}
@Composable
private fun CompactAssistantDock(
    mood: AssistantMood,
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    onOrbClick: () -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier, shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = Color(0xF21B2637)), elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistantOrb(mood, Modifier, diameter = 48.dp, onClick = onOrbClick)
            Spacer(Modifier.width(5.dp))
            TextField(value = draft, onValueChange = onDraftChange, modifier = Modifier.weight(1f), enabled = enabled, singleLine = true, placeholder = { Text("Ask NOVA…", style = MaterialTheme.typography.bodySmall) }, shape = RoundedCornerShape(24.dp), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            IconButton(enabled = enabled, onClick = onAttach) { Icon(Icons.Default.AttachFile, "Attach UTF-8 text file") }
            IconButton(enabled = enabled, onClick = onVoice) { Icon(Icons.Default.Mic, "Dictate message") }
            IconButton(enabled = enabled && draft.isNotBlank(), onClick = onSend) { Icon(Icons.Default.Send, "Send to NOVA") }
        }
    }
}

@Composable
private fun NovaDrawerItem(target: Section, selected: Section, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String = target.title, onClick: () -> Unit) {
    NavigationDrawerItem(label = { Text(label) }, selected = selected == target, icon = { Icon(icon, label) }, onClick = onClick, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
}

@Composable
private fun SettingsCategory(title: String, detail: String, action: String? = null, onClick: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121D2B))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && onClick != null) TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable private fun EmptyState(title: String, message: String) { Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.AutoAwesome, title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp)); Spacer(Modifier.height(10.dp)); Text(title, style = MaterialTheme.typography.titleMedium); Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun DashboardCard(title: String, message: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, title, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(message, style = MaterialTheme.typography.bodySmall) } } } }

internal val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
internal fun checkedBase(base: String, token: String): String {
    if (!base.startsWith("https://")) throw IOException("Backend URL must use HTTPS")
    if (token.isBlank()) throw IOException("Configure a scoped NOVA API token in Settings")
    return base.trimEnd('/')
}
internal fun authorizedRequest(base: String, token: String, path: String, method: String = "GET", json: JSONObject? = null): String {
    val builder = Request.Builder().url("${checkedBase(base, token)}$path").header("Authorization", "Bearer $token")
    val media = "application/json".toMediaType()
    when (method) {
        "POST" -> builder.post((json ?: JSONObject()).toString().toRequestBody(media))
        "PUT" -> builder.put((json ?: JSONObject()).toString().toRequestBody(media))
        "PATCH" -> builder.patch((json ?: JSONObject()).toString().toRequestBody(media))
        "DELETE" -> builder.delete()
        else -> builder.get()
    }
    http.newCall(builder.build()).execute().use { response ->
        val body = response.body?.string() ?: "{}"
        if (!response.isSuccessful) throw IOException("NOVA API returned HTTP ${response.code}: ${body.take(200)}")
        return body
    }
}

private fun loadWorkerMessages(base: String, token: String): List<WorkforceMessage> {
    val array = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/agents/messages"))
    return (0 until array.length()).map { index -> array.getJSONObject(index).let { item ->
        WorkforceMessage(item.getString("id"), item.optString("task_id").takeIf { it.isNotBlank() && it != "null" }, item.getString("sender_agent_id"), item.optString("recipient_agent_id").takeIf { it.isNotBlank() && it != "null" }, item.optString("message_type", "message"), item.optJSONObject("content")?.toString() ?: "{}", item.optString("created_at"))
    } }
}

private fun loadWorkforce(base: String, token: String): WorkforceSnapshot<List<WorkforceAgent>, List<WorkforceTask>, List<OfficeEvent>, List<ProviderConnectionInfo>, OfficeWorld> {
    val agentsJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/agents"))
    val agents = (0 until agentsJson.length()).map { i ->
        val item = agentsJson.getJSONObject(i)
        WorkforceAgent(item.getString("id"), item.optString("identity", "worker"), item.optString("name", "Agent"), item.optString("job_title", "Worker"), item.optString("provider", "arena"), item.optString("model_id", ""), item.optString("instructions", ""), item.optString("status", "idle"), jsonStrings(item.optJSONArray("specialties")), item.optDouble("temperature", 0.2), item.optInt("context_length", 8192), jsonStrings(item.optJSONArray("tool_permissions")), item.optBoolean("memory_enabled", true), item.optInt("priority", 50), item.optBoolean("critical"), item.optInt("max_task_duration_seconds", 300))
    }
    val tasksJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/tasks"))
    val tasks = (0 until tasksJson.length()).map { i -> tasksJson.getJSONObject(i).let { item ->
        val checkpoint = item.optJSONObject("checkpoint") ?: JSONObject()
        WorkforceTask(item.getString("id"), item.optString("title", "Task"), item.optString("state", "RECEIVED"), item.optString("result").takeIf { value -> value.isNotBlank() && value != "null" }, item.optString("error_detail").takeIf { value -> value.isNotBlank() && value != "null" }, item.optInt("priority", 50), item.optString("assigned_agent_id").takeIf { value -> value.isNotBlank() && value != "null" }, item.optString("requested_agent_id").takeIf { value -> value.isNotBlank() && value != "null" }, checkpoint.optInt("progress_percent", 0).coerceIn(0,100), checkpoint.optString("stage", "queued"))
    } }
    val eventsJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/events/recent?limit=100"))
    val events = (0 until eventsJson.length()).map { i -> eventsJson.getJSONObject(i).let { OfficeEvent(it.optString("event_type", "event"), it.optString("agent_id").takeIf { value -> value.isNotBlank() && value != "null" }, it.optString("task_id").takeIf { value -> value.isNotBlank() && value != "null" }, it.optString("created_at"), it.optJSONObject("payload")?.toString() ?: "{}") } }
    val connections = emptyList<ProviderConnectionInfo>() // Model discovery and provider links belong exclusively to NOVA-GATEWAY.
    val office = parseOfficeWorld(JSONObject(authorizedRequest(base, token, "/api/v1/office/state")))
    return WorkforceSnapshot(agents, tasks, events, connections, office)
}

private fun parseOfficeWorld(json: JSONObject): OfficeWorld {
    val layout = json.getJSONObject("layout")
    val roomJson = layout.optJSONArray("rooms") ?: org.json.JSONArray()
    val rooms = (0 until roomJson.length()).map { i -> roomJson.getJSONObject(i).let { room -> val door=room.getJSONObject("entrance"); OfficeRoomInfo(room.getString("id"),room.getString("name"),room.getInt("x"),room.getInt("y"),room.getInt("width"),room.getInt("height"),OfficeCell(door.getInt("x"),door.getInt("y"))) } }
    val furnitureJson = layout.optJSONArray("furniture") ?: org.json.JSONArray()
    val furniture = (0 until furnitureJson.length()).map { i -> furnitureJson.getJSONObject(i).let { item -> OfficeFurnitureInfo(item.getString("id"),item.getString("kind"),item.getInt("x"),item.getInt("y"),item.optInt("width",1),item.optInt("height",1),item.optInt("rotation",0)) } }
    val agentsJson = json.optJSONArray("agents") ?: org.json.JSONArray()
    val characters = (0 until agentsJson.length()).map { i -> agentsJson.getJSONObject(i).let { item ->
        val pos=item.getJSONObject("position"); val movement=item.optJSONObject("movement") ?: JSONObject()
        val destination=movement.optJSONObject("destination")?.let { OfficeCell(it.optInt("x"),it.optInt("y")) }
        val routeJson=movement.optJSONArray("route") ?: org.json.JSONArray()
        val route=(0 until routeJson.length()).map { j -> routeJson.getJSONObject(j).let { OfficeCell(it.getInt("x"),it.getInt("y")) } }
        item.getString("id") to OfficeCharacterState(item.getString("id"),pos.getInt("x"),pos.getInt("y"),movement.optString("state","idle"),destination,route,movement.optString("move_id").takeIf { it.isNotBlank() && it != "null" })
    } }.toMap()
    return OfficeWorld(layout.getInt("width"),layout.getInt("height"),rooms,furniture,characters)
}

private fun loadWorkforceAnalytics(base: String, token: String): List<AgentAnalytics> {
    val result = JSONObject(authorizedRequest(base, token, "/api/v1/analytics/workforce")).optJSONArray("agents") ?: org.json.JSONArray()
    return (0 until result.length()).map { i -> result.getJSONObject(i).let { item ->
        AgentAnalytics(item.optString("identity"), item.optString("name"), item.optString("backend_status"), item.optInt("assignments_recorded"), item.optInt("completed_assignments"), item.optInt("failed_assignments"), item.optInt("active_assignments"), if (item.isNull("average_completed_assignment_seconds")) null else item.optDouble("average_completed_assignment_seconds"), item.optInt("completed_duration_samples"), item.optInt("provider_requests_recorded"), item.optInt("provider_requests_succeeded"), item.optInt("provider_requests_failed"), item.optInt("reported_input_tokens"), item.optInt("reported_output_tokens"))
    } }
}

private fun agentActivity(status: String): String = when (status.lowercase()) {
    "working" -> "Working / executing"
    "paused" -> "Resting · paused"
    "error", "failed" -> "Needs attention"
    "idle", "available" -> "Resting · available"
    else -> status.replace('_', ' ').ifBlank { "Status unavailable" }
}

private fun analyticsDuration(metric: AgentAnalytics): String = metric.durationSeconds?.let {
    String.format(Locale.US, "%.2f s (%d samples)", it, metric.durationSamples)
} ?: "Unavailable (no completed timing samples)"

private fun jsonStrings(array: org.json.JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }

private fun seedAgentRoster(base: String, token: String): List<WorkforceAgent> {
    val response = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/agents/seed", "POST"))
    return (0 until response.length()).map { i -> val item = response.getJSONObject(i); WorkforceAgent(item.getString("id"), item.optString("identity", "worker"), item.optString("name", "Agent"), item.optString("job_title", "Worker"), item.optString("provider", "arena"), item.optString("model_id", ""), item.optString("instructions", ""), item.optString("status", "idle"), jsonStrings(item.optJSONArray("specialties")), item.optDouble("temperature", 0.2), item.optInt("context_length", 8192), jsonStrings(item.optJSONArray("tool_permissions")), item.optBoolean("memory_enabled", true), item.optInt("priority", 50), item.optBoolean("critical"), item.optInt("max_task_duration_seconds", 300)) }
}

private fun loadMeetings(base: String, token: String): List<MeetingSummary> {
    val rows=org.json.JSONArray(authorizedRequest(base,token,"/api/v1/meetings"))
    return (0 until rows.length()).map { index ->
        val item=rows.getJSONObject(index)
        val invitationsJson=item.optJSONArray("invitations")?:org.json.JSONArray()
        val invitations=(0 until invitationsJson.length()).map { i -> invitationsJson.getJSONObject(i).let { MeetingInviteInfo(it.getString("agent_id"),it.optString("name","Employee"),it.optString("status","invited")) } }
        val names=invitations.associateBy({it.agentId},{it.name})
        val messagesJson=item.optJSONArray("messages")?:org.json.JSONArray()
        val messages=(0 until messagesJson.length()).map { i -> messagesJson.getJSONObject(i).let { MeetingLine(names[it.optString("speaker_agent_id")]?:it.optString("role","Meeting"),it.optString("role"),it.optString("content")) } }
        MeetingSummary(item.getString("id"),item.optString("title"),item.optString("agenda"),item.optString("project_context"),item.optString("status","scheduled"),item.optString("scheduled_at").takeIf { it.isNotBlank() && it!="null" },item.optString("task_id").takeIf { it.isNotBlank() && it!="null" },invitations,messages)
    }
}

private fun createMeeting(base:String,token:String,title:String,agenda:String,context:String,participants:List<String>,taskId:String?,scheduleMinutes:Long) {
    val ids=org.json.JSONArray().also { array->participants.forEach { array.put(it) } }
    val body=JSONObject().put("title",title).put("agenda",agenda).put("project_context",context).put("participant_ids",ids)
    if(taskId!=null)body.put("task_id",taskId)
    if(scheduleMinutes>0)body.put("scheduled_at",java.time.Instant.now().plusSeconds(scheduleMinutes*60).toString())
    authorizedRequest(base,token,"/api/v1/meetings","POST",body)
}

private fun cancelMeeting(base:String,token:String,id:String) { authorizedRequest(base,token,"/api/v1/meetings/$id/cancel","POST") }
private fun createMeetingHandoff(base:String,token:String,meetingId:String,taskId:String,agentId:String,instructions:String) {
    authorizedRequest(base,token,"/api/v1/meetings/$meetingId/handoffs","POST",JSONObject().put("task_id",taskId).put("agent_id",agentId).put("instructions",instructions))
}

private fun createWorkforceTask(base: String, token: String, title: String, description: String, dependencies: List<String>, requestedAgentId: String?) {
    val dependencyArray = org.json.JSONArray().also { array -> dependencies.forEach { array.put(it) } }
    val body = JSONObject().put("title", title).put("description", description).put("dependencies", dependencyArray)
    requestedAgentId?.let { body.put("requested_agent_id", it) }
    authorizedRequest(base, token, "/api/v1/tasks", "POST", body)
}

private fun controlWorkforceAgent(base: String, token: String, agentId: String, action: String) {
    authorizedRequest(base, token, "/api/v1/agents/$agentId/$action", "POST")
}

private fun cancelWorkforceTask(base: String, token: String, taskId: String) {
    authorizedRequest(base, token, "/api/v1/tasks/$taskId/cancel", "POST")
}

private fun saveOfficeLayout(base: String, token: String, world: OfficeWorld): OfficeWorld {
    val rooms = org.json.JSONArray().also { array -> world.rooms.forEach { room ->
        array.put(JSONObject().put("id", room.id).put("name", room.name).put("x", room.x).put("y", room.y).put("width", room.width).put("height", room.height)
            .put("entrance", JSONObject().put("x", room.entrance.x).put("y", room.entrance.y)))
    } }
    val furniture = org.json.JSONArray().also { array -> world.furniture.forEach { item ->
        array.put(JSONObject().put("id", item.id).put("kind", item.kind).put("x", item.x).put("y", item.y).put("width", item.width).put("height", item.height).put("rotation", item.rotation))
    } }
    val response = JSONObject(authorizedRequest(base, token, "/api/v1/office/layout", "PUT", JSONObject().put("width", world.width).put("height", world.height).put("rooms", rooms).put("furniture", furniture)))
    return parseOfficeWorld(response)
}

private fun requestOfficeMove(base: String, token: String, agentId: String, destination: OfficeCell) {
    authorizedRequest(base, token, "/api/v1/office/agents/$agentId/move", "POST", JSONObject().put("x", destination.x).put("y", destination.y))
}

private fun advanceOfficeStep(base: String, token: String, current: OfficeCharacterState): OfficeCharacterState {
    val json = JSONObject(authorizedRequest(base, token, "/api/v1/office/agents/${current.agentId}/step", "POST"))
    val pos = json.getJSONObject("position")
    val routeJson = json.optJSONArray("route") ?: org.json.JSONArray()
    val route = (0 until routeJson.length()).map { i -> routeJson.getJSONObject(i).let { OfficeCell(it.getInt("x"), it.getInt("y")) } }
    val state = json.optString("state", "walking")
    val idle = state == "arrived"
    return current.copy(agentId=current.agentId, x=pos.getInt("x"), y=pos.getInt("y"), movementState=if (idle) "idle" else state, destination=if (idle) null else current.destination, route=route, moveId=if (idle) null else current.moveId)
}

private fun loadMemories(base: String, token: String): List<MemoryItem> {
    val rows = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/memories"))
    return (0 until rows.length()).map { index -> rows.getJSONObject(index).let { item ->
        MemoryItem(item.getString("id"), item.optString("scope", "agent"), item.optString("content", ""), item.optString("agent_id").takeIf { it.isNotBlank() && it != "null" }, item.optString("project_key").takeIf { it.isNotBlank() && it != "null" }, item.optDouble("importance", 0.5))
    } }
}

private fun createMemory(base: String, token: String, scope: String, content: String, agentId: String?, projectKey: String) {
    val body = JSONObject().put("scope", scope).put("content", content)
    if (scope == "agent") body.put("agent_id", agentId ?: throw IOException("Choose a worker for worker-scoped memory"))
    if (scope == "project") body.put("project_key", projectKey.takeIf { it.isNotBlank() } ?: throw IOException("Enter a project key"))
    authorizedRequest(base, token, "/api/v1/memories", "POST", body)
}

private fun deleteMemory(base: String, token: String, id: String) {
    authorizedRequest(base, token, "/api/v1/memories/$id", "DELETE")
}

private fun loadResourceOverview(base: String, token: String): String {
    val json = JSONObject(authorizedRequest(base, token, "/api/v1/resources/overview"))
    val agents = json.optJSONObject("agents") ?: JSONObject()
    val tasks = json.optJSONObject("tasks") ?: JSONObject()
    return "Workers ${agents.optInt("working")}/${agents.optInt("total")} · tasks ${tasks.optInt("active")} active, ${tasks.optInt("queued")} queued, ${tasks.optInt("completed_today")} completed today · ${json.optInt("provider_requests_today")} provider requests today. Token/cost estimates are not fabricated."
}
