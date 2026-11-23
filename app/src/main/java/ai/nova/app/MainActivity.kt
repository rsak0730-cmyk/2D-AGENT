package ai.nova.app

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
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
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6750A4), background = Color(0xFFF7F8FC))) { NovaApp(settings) } }
    }
}

private data class ChatLine(val text: String, val fromUser: Boolean)
private data class ConversationSummary(val id: String, val title: String)
private data class AgentChatSnapshot(val conversationId: String?, val messages: List<ChatLine>)
private data class AgentChatReply(val conversationId: String, val response: String, val provider: String, val model: String)
private data class ProviderConfig(val provider: String, val modelId: String, val configured: Boolean, val keySource: String)
private data class ProviderTestResult(val modelsAvailable: Int, val modelIds: List<String>)
private data class WorkforceAgent(val id: String, val identity: String, val name: String, val title: String, val provider: String, val model: String, val instructions: String, val status: String, val specialties: List<String>, val temperature: Double, val contextLength: Int, val permissions: List<String>, val memoryEnabled: Boolean, val priority: Int, val critical: Boolean, val maxDuration: Int)
private data class WorkforceTask(val id: String, val title: String, val state: String, val result: String?, val error: String?, val priority: Int, val assignedAgentId: String?, val progressPercent: Int, val progressStage: String)
private data class AgentAnalytics(val identity: String, val name: String, val status: String, val assignments: Int, val completed: Int, val failed: Int, val active: Int, val durationSeconds: Double?, val durationSamples: Int, val requests: Int, val successful: Int, val failedRequests: Int, val inputTokens: Int, val outputTokens: Int)
private data class OfficeEvent(val type: String, val agentId: String?, val taskId: String?, val createdAt: String, val payload: String)
private data class MeetingInviteInfo(val agentId: String,val name: String,val status: String)
private data class MeetingLine(val speaker: String,val role: String,val content: String)
private data class MeetingSummary(val id: String,val title: String,val agenda: String,val context: String,val status: String,val scheduledAt: String?,val taskId: String?,val invitations: List<MeetingInviteInfo>,val messages: List<MeetingLine>)
private data class OfficeCell(val x: Int, val y: Int)
private data class OfficeRoomInfo(val id: String, val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val entrance: OfficeCell)
private data class OfficeFurnitureInfo(val id: String, val kind: String, val x: Int, val y: Int, val width: Int, val height: Int, val rotation: Int)
private data class OfficeCharacterState(val agentId: String, val x: Int, val y: Int, val movementState: String, val destination: OfficeCell?, val route: List<OfficeCell>, val moveId: String?)
private data class OfficeWorld(val width: Int, val height: Int, val rooms: List<OfficeRoomInfo>, val furniture: List<OfficeFurnitureInfo>, val characters: Map<String, OfficeCharacterState>)
private data class ProviderConnectionInfo(val id: String, val provider: String, val models: List<String>, val priority: Int, val status: String)
private data class WorkforceSnapshot<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
private enum class Section(val title: String) { HOME("Home"), ASSISTANT("Assistant"), HQ("Headquarters"), AGENTS("Agents"), TASKS("Tasks"), SETTINGS("Settings") }

@Composable
private fun PixelOfficeScene(agents: List<WorkforceAgent>, world: OfficeWorld?, modifier: Modifier = Modifier, onAgentSelected: (WorkforceAgent) -> Unit) {
    val active = agents.count { it.status.equals("working", true) }
    val moving = world?.characters?.values?.count { it.movementState in listOf("walking", "waiting", "queued") } ?: 0
    val cycle = rememberInfiniteTransition(label = "work-cycle")
    val phase by cycle.animateFloat(0f, 1f, infiniteRepeatable(tween(450), repeatMode = RepeatMode.Reverse), label = "typing")
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF18243A))) {
        if (world == null) {
            Box(Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) { Text("Waiting for persisted office state from NOVA backend", color = Color.White) }
        } else BoxWithConstraints {
            val logicalHeight = 40f + world.height * (360f / world.width)
            val sceneHeight = maxOf(270f, logicalHeight * maxWidth.value / 360f).dp
            val tileWidth = 360f / world.width
            val tileHeight = (logicalHeight - 40f) / world.height
            val cellWidthDp = maxWidth / world.width.toFloat()
            val headerDp = sceneHeight * (40f / logicalHeight)
            val cellHeightDp = (sceneHeight - headerDp) / world.height.toFloat()
            val density = LocalDensity.current
            Canvas(Modifier.fillMaxWidth().height(sceneHeight)) {
                val sx = size.width / 360f
                val sy = size.height / logicalHeight
                fun pixel(x: Float, y: Float, w: Float, h: Float, color: Color) {
                    drawRect(color, Offset(x * sx, y * sy), Size(w * sx, h * sy))
                }
                pixel(0f, 0f, 360f, logicalHeight, Color(0xFF293B58))
                pixel(0f, 40f, 360f, logicalHeight - 40f, Color(0xFF526D79))
                for (row in 0 until world.height) for (column in 0 until world.width) {
                    pixel(column * tileWidth, 40f + row * tileHeight, tileWidth - 0.25f, tileHeight - 0.25f, if ((row + column) % 2 == 0) Color(0xFF66858A) else Color(0xFF5D7A83))
                }
                world.rooms.forEachIndexed { index, room ->
                    pixel(room.x * tileWidth, 40f + room.y * tileHeight, room.width * tileWidth, room.height * tileHeight, if (index % 2 == 0) Color(0x2251A7B0) else Color(0x223B8B9A))
                    pixel(room.entrance.x * tileWidth, 40f + room.entrance.y * tileHeight, tileWidth, tileHeight, Color(0xFFD7BD8C))
                }
                world.furniture.forEach { item ->
                    val fw = if (item.rotation == 90 || item.rotation == 270) item.height else item.width
                    val fh = if (item.rotation == 90 || item.rotation == 270) item.width else item.height
                    val color = when (item.kind) {
                        "desk", "meeting_table" -> Color(0xFF795638)
                        "chair" -> Color(0xFF594331)
                        "server_rack" -> Color(0xFF334454)
                        "coffee_machine" -> Color(0xFF8C6B59)
                        "whiteboard", "project_board", "monitor" -> Color(0xFFD7BD8C)
                        else -> Color(0xFF6D513E)
                    }
                    pixel(item.x * tileWidth, 40f + item.y * tileHeight, fw * tileWidth, fh * tileHeight, color)
                    if (item.kind == "server_rack") pixel((item.x + 0.2f) * tileWidth, 40f + (item.y + 0.2f) * tileHeight, (fw - 0.4f) * tileWidth, 0.18f * tileHeight, Color(0xFF72D4C2))
                }
                pixel(12f, 8f, 58f, 21f, Color(0xFFB8E4E5)); pixel(17f, 12f, 22f, 12f, Color(0xFF739BB1)); pixel(45f, 12f, 20f, 12f, Color(0xFF8CB3C4))
                pixel(285f, 8f, 58f, 22f, Color(0xFF8C6B59)); pixel(290f, 12f, 48f, 14f, Color(0xFFD7BD8C))
            }
            world.characters.values.forEach { character ->
                val agent = agents.firstOrNull { it.id == character.agentId } ?: return@forEach
                val spriteWidth = cellWidthDp * 0.92f
                val spriteHeight = cellHeightDp * 1.22f
                val target = with(density) {
                    Offset((cellWidthDp * (character.x + 0.5f) - spriteWidth * 0.5f).toPx(), (headerDp + cellHeightDp * (character.y + 0.5f) - spriteHeight * 0.68f).toPx())
                }
                val animated by animateOffsetAsState(target, animationSpec = tween(260), label = "agent-${agent.id}-walk")
                Box(Modifier.offset { IntOffset(animated.x.roundToInt(), animated.y.roundToInt()) }.size(spriteWidth, spriteHeight).clickable { onAgentSelected(agent) }) {
                    PixelCharacterSprite(agent, agent.status.equals("working", true), agent.status.equals("error", true) || agent.status.equals("failed", true), phase)
                }
            }
            Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Text("NOVA OFFICE · ${world.rooms.size} rooms", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Text("$active working · $moving moving/queued · persisted backend state", color = Color(0xFFD6E5F2), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PixelCharacterSprite(agent: WorkforceAgent, working: Boolean, failed: Boolean, phase: Float) {
    val look = officeLook(agent.identity)
    val shirt = Color(look.shirtArgb)
    val hair = Color(look.hairArgb)
    Canvas(Modifier.fillMaxSize()) {
        fun pixel(x: Float, y: Float, w: Float, h: Float, color: Color) { drawRect(color, Offset(x * size.width, y * size.height), Size(w * size.width, h * size.height)) }
        val screen = if (failed) Color(0xFFFFB3A8) else if (working) Color(0xFFB5F1A9) else Color(0xFF29475D)
        pixel(0.08f, 0.68f, 0.84f, 0.12f, Color(0xFF795638))
        pixel(0.31f, 0.12f, 0.38f, 0.26f, Color(0xFFEDBD91)); pixel(0.29f, 0.08f, 0.42f, 0.12f, hair)
        pixel(0.3f, 0.23f, 0.08f, 0.12f, hair); pixel(0.64f, 0.23f, 0.08f, 0.12f, hair)
        pixel(0.4f, 0.25f, 0.04f, 0.04f, Color(0xFF293344)); pixel(0.57f, 0.25f, 0.04f, 0.04f, Color(0xFF293344))
        pixel(0.28f, 0.39f, 0.46f, 0.28f, shirt)
        val handShift = if (working && phase > 0.5f) 0.035f else 0f
        pixel(0.18f, 0.44f + handShift, 0.14f, 0.07f, Color(0xFFEDBD91)); pixel(0.68f, 0.44f - handShift, 0.14f, 0.07f, Color(0xFFEDBD91))
        pixel(0.34f, 0.66f, 0.14f, 0.16f, Color(0xFF34415A)); pixel(0.53f, 0.66f, 0.14f, 0.16f, Color(0xFF34415A))
        pixel(0.04f, 0.37f, 0.25f, 0.23f, if (working) Color(0xFF80D58A) else if (failed) Color(0xFFE07B72) else Color(0xFF8DC8D7))
        pixel(0.08f, 0.41f, 0.18f, 0.15f, screen)
    }
}

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
    var speechEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var speechInitializing by remember { mutableStateOf(false) }
    var pendingSpeech by remember { mutableStateOf<String?>(null) }
    var voiceStatus by remember { mutableStateOf("") }
    DisposableEffect(context) { onDispose { speechEngine?.stop(); speechEngine?.shutdown() } }
    val speakResponse: (String) -> Unit = { text ->
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
    var section by remember { mutableStateOf(Section.ASSISTANT) }
    var base by remember { mutableStateOf(settings.get("base")) }
    var token by remember { mutableStateOf(settings.get("token")) }
    var showSettings by remember { mutableStateOf(false) }
    var showProviderSettings by remember { mutableStateOf(false) }
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
    var agentChatConversationId by remember { mutableStateOf<String?>(null) }
    var agentChatStatus by remember { mutableStateOf("") }
    var agentChatBusy by remember { mutableStateOf(false) }
    var workforceBusy by remember { mutableStateOf(false) }
    var workforceNotice by remember { mutableStateOf("Connect a backend to load saved agent/task records.") }
    var usageSummary by remember { mutableStateOf("Usage summary not loaded") }
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskDescription by remember { mutableStateOf("") }
    val selectedTaskDependencies = remember { mutableStateListOf<String>() }
    var providerDialog by remember { mutableStateOf(false) }
    var registryProvider by remember { mutableStateOf("openai_compatible") }
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
        if (section in setOf(Section.HQ, Section.AGENTS, Section.TASKS) && base.startsWith("https://") && token.isNotBlank()) {
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
    LaunchedEffect(selectedOfficeAgent?.id, base, token) {
        val agent = selectedOfficeAgent ?: return@LaunchedEffect
        agentChatLines.clear(); agentChatDraft = ""; agentChatConversationId = null
        if (!base.startsWith("https://") || token.isBlank()) {
            agentChatStatus = "Connect an HTTPS backend with agents:read and agents:write scopes to chat."
        } else {
            agentChatStatus = "Loading saved employee conversation…"
            try {
                val snapshot = withContext(Dispatchers.IO) { loadAgentChat(base, token, agent.id) }
                agentChatLines.addAll(snapshot.messages)
                agentChatConversationId = snapshot.conversationId
                agentChatStatus = if (snapshot.messages.isEmpty()) "No saved messages. The first prompt is sent to this employee's configured model." else "Loaded ${snapshot.messages.size} persisted messages."
            } catch (e: Exception) { agentChatStatus = e.message ?: "Could not load employee chat history" }
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
                            Column(Modifier.weight(1f)) { Text("Your assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(status, style = MaterialTheme.typography.bodySmall); if (voiceStatus.isNotBlank()) Text(voiceStatus, style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = openProviderSettings) { Text("AI provider") }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (chat.isEmpty()) item { EmptyState("Ready when you are", "Connect your own NOVA backend and provider. NOVA won't invent an answer if it is offline.") }
                        items(chat) { line -> ChatBubble(line, speakResponse) }
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
                Section.HQ -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp).padding(bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Headquarters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onClick = refreshWorkforce) { Text(if (workforceBusy) "Loading…" else "Refresh") } }
                    Text(workforceNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixelOfficeScene(workforceAgents, officeWorld, Modifier.padding(top = 8.dp)) { selectedOfficeAgent = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = officeWorld != null, onClick = { officeDraft = officeWorld?.copy(); showOfficeEditor = true }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text("Edit rooms and furniture") }
                    }
                    Text("Characters occupy saved grid positions. Working animation follows backend status; walking follows validated and persisted A* steps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Schedule a real employee meeting", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(meetingTitle,{meetingTitle=it},Modifier.fillMaxWidth(),label={Text("Meeting title")},singleLine=true)
                        OutlinedTextField(meetingAgenda,{meetingAgenda=it},Modifier.fillMaxWidth(),label={Text("Agenda")},maxLines=2)
                        OutlinedTextField(meetingContext,{meetingContext=it},Modifier.fillMaxWidth(),label={Text("Shared project context")},maxLines=3)
                        OutlinedTextField(meetingScheduleMinutes,{meetingScheduleMinutes=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text("Schedule in minutes (0 = startable now)")},singleLine=true)
                        Text("Invite employees",style=MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(workforceAgents) { agent -> FilterChip(selected=agent.id in selectedMeetingParticipants,onClick={if(agent.id in selectedMeetingParticipants)selectedMeetingParticipants.remove(agent.id) else if(selectedMeetingParticipants.size<10)selectedMeetingParticipants.add(agent.id)},label={Text(agent.name)}) } }
                        if (workforceTasks.isNotEmpty()) { Text("Optional linked task",style=MaterialTheme.typography.labelMedium); LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(workforceTasks) { task -> FilterChip(selected=selectedMeetingTaskId==task.id,onClick={selectedMeetingTaskId=if(selectedMeetingTaskId==task.id)null else task.id},label={Text(task.title.take(22))}) } } }
                        Button(enabled=!workforceBusy && meetingTitle.isNotBlank() && meetingAgenda.isNotBlank() && selectedMeetingParticipants.isNotEmpty(),onClick={
                            val title=meetingTitle.trim();val agenda=meetingAgenda.trim();val context=meetingContext.trim();val attendees=selectedMeetingParticipants.toList();val taskId=selectedMeetingTaskId;val minutes=meetingScheduleMinutes.toLongOrNull()?:0L
                            scope.launch { workforceBusy=true;try { withContext(Dispatchers.IO) { createMeeting(base,token,title,agenda,context,attendees,taskId,minutes) }; meetingTitle="";meetingAgenda="";meetingContext="";selectedMeetingParticipants.clear();workforceNotice="Meeting invitation saved; participant model calls run only when you start it." } catch(e:Exception){workforceNotice=e.message?:"Meeting could not be scheduled"} finally {workforceBusy=false;refreshWorkforce()} }
                        }) { Text("Schedule meeting") }
                    } }
                    Text("Meeting history · saved transcript and participant status",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=8.dp))
                    meetings.forEach { meeting -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) { Text(meeting.title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Text(meeting.status.replace('_',' '),style=MaterialTheme.typography.labelMedium) }
                        Text(meeting.agenda,style=MaterialTheme.typography.bodySmall)
                        if (meeting.scheduledAt!=null) Text("Scheduled ${meeting.scheduledAt}",style=MaterialTheme.typography.labelSmall)
                        meeting.invitations.forEach { invite -> Text("${invite.name}: ${invite.status}",style=MaterialTheme.typography.labelSmall) }
                        meeting.messages.takeLast(8).forEach { line -> Text("${line.speaker}: ${line.content}",style=MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if (meeting.status in listOf("scheduled","invited")) TextButton(enabled=!workforceBusy,onClick={scope.launch { workforceBusy=true;try { withContext(Dispatchers.IO) { startMeeting(base,token,meeting.id) };workforceNotice="Meeting completed; provider responses and history were saved." } catch(e:Exception){workforceNotice=e.message?:"Meeting could not run"} finally {workforceBusy=false;refreshWorkforce()} }}) { Text("Start meeting") }
                            if (meeting.status in listOf("scheduled","invited","running")) TextButton(enabled=!workforceBusy,onClick={scope.launch { workforceBusy=true;try { withContext(Dispatchers.IO) { cancelMeeting(base,token,meeting.id) };workforceNotice="Meeting cancellation requested." } catch(e:Exception){workforceNotice=e.message?:"Could not cancel meeting"} finally {workforceBusy=false;refreshWorkforce()} }}) { Text("Cancel") }
                            if (meeting.invitations.isNotEmpty() && workforceTasks.isNotEmpty()) TextButton(onClick={handoffMeeting=meeting;handoffTaskId=null;handoffAgentId=meeting.invitations.firstOrNull()?.agentId;handoffInstructions=""}) { Text("Task handoff") }
                        }
                    } } }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) { items(workforceAgents) { agent -> AssistChip(onClick = { selectedOfficeAgent = agent }, label = { Text("${agent.name} · ${agent.status}", maxLines = 1) }, leadingIcon = { Icon(Icons.Default.Person, null) }) } }
                    Text("Completed tasks ${workforceTasks.count { it.state == "COMPLETED" }} · recent saved events", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                    if (officeEvents.isEmpty()) EmptyState("No recorded activity yet", "The office shows only agents in the loaded backend roster. Event history appears after real task or provider actions.")
                    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { officeEvents.take(12).forEach { event -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(event.type.replace('.', ' ').replace('_', ' '), fontWeight = FontWeight.SemiBold); Text("${event.payload} · ${event.createdAt}", style = MaterialTheme.typography.bodySmall); if (event.taskId != null) Text("Task ${event.taskId.take(8)}", style = MaterialTheme.typography.labelSmall) } } } }
                }
                Section.AGENTS -> Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Agent workforce", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onClick = refreshWorkforce) { Text("Refresh") } }
                    Text(workforceNotice, style = MaterialTheme.typography.bodySmall)
                    if (workforceAgents.isEmpty()) Button(onClick = { workforceBusy = true; scope.launch { try { val roster = withContext(Dispatchers.IO) { seedAgentRoster(base, token) }; workforceAgents.clear(); workforceAgents.addAll(roster); workforceNotice = "11 independent role identities created. Configure each model and prompt before execution." } catch (e: Exception) { workforceNotice = e.message ?: "Could not seed workforce" } finally { workforceBusy = false; refreshWorkforce() } } }, enabled = !workforceBusy) { Text("Seed Head Manager + 10 workers") }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) { items(workforceAgents) { agent -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Row(Modifier.fillMaxWidth().clickable { editingAgent = agent }, verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(agent.name, fontWeight = FontWeight.SemiBold); Text("${agent.identity} · ${agent.status} · ${agent.provider}/${agent.model}", style = MaterialTheme.typography.bodySmall); Text(agent.specialties.joinToString(" · "), style = MaterialTheme.typography.labelSmall) }; Icon(Icons.Default.Edit, "Configure ${agent.name}") }; Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (agent.status == "paused") TextButton(enabled = !workforceBusy, onClick = { scope.launch { workforceBusy = true; try { withContext(Dispatchers.IO) { controlWorkforceAgent(base, token, agent.id, "resume") }; workforceNotice = "${agent.name} resumed." } catch (e: Exception) { workforceNotice = e.message ?: "Resume failed" } finally { workforceBusy = false; refreshWorkforce() } } }) { Text("Resume") } else TextButton(enabled = !workforceBusy && agent.status != "working", onClick = { scope.launch { workforceBusy = true; try { withContext(Dispatchers.IO) { controlWorkforceAgent(base, token, agent.id, "pause") }; workforceNotice = "${agent.name} paused." } catch (e: Exception) { workforceNotice = e.message ?: "Pause failed" } finally { workforceBusy = false; refreshWorkforce() } } }) { Text("Pause") }; if (agent.status == "working") TextButton(enabled = !workforceBusy, onClick = { scope.launch { workforceBusy = true; try { withContext(Dispatchers.IO) { controlWorkforceAgent(base, token, agent.id, "stop") }; workforceNotice = "Cooperative stop requested for ${agent.name}'s task." } catch (e: Exception) { workforceNotice = e.message ?: "Stop failed" } finally { workforceBusy = false; refreshWorkforce() } } }) { Text("Stop task") } } } } } }
                    Text("Tap an identity to configure its model and instructions. Tools remain disabled unless explicitly granted by the backend.", style = MaterialTheme.typography.bodySmall)
                }
                Section.TASKS -> Column(Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Tasks", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onClick = refreshWorkforce) { Text("Refresh") } }
                    OutlinedTextField(value = newTaskTitle, onValueChange = { newTaskTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("New task") }, singleLine = true)
                    OutlinedTextField(value = newTaskDescription, onValueChange = { newTaskDescription = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Details (optional)") }, maxLines = 2)
                    if (workforceTasks.isNotEmpty()) {
                        Text("Wait for these task dependencies (select any recorded tasks)", style = MaterialTheme.typography.labelSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(workforceTasks) { dependency ->
                                FilterChip(selected = dependency.id in selectedTaskDependencies, onClick = { if (dependency.id in selectedTaskDependencies) selectedTaskDependencies.remove(dependency.id) else if (selectedTaskDependencies.size < 20) selectedTaskDependencies.add(dependency.id) }, label = { Text(dependency.title.take(24)) })
                            }
                        }
                    }
                    Button(enabled = newTaskTitle.isNotBlank() && !workforceBusy, onClick = { val title = newTaskTitle.trim(); val description = newTaskDescription.trim(); val dependencies = selectedTaskDependencies.toList(); scope.launch { workforceBusy = true; try { withContext(Dispatchers.IO) { createWorkforceTask(base, token, title, description, dependencies) }; newTaskTitle = ""; newTaskDescription = ""; selectedTaskDependencies.clear(); workforceNotice = "Task saved with persisted dependencies. Tap Run when every prerequisite is complete." } catch (e: Exception) { workforceNotice = e.message ?: "Task could not be saved" } finally { workforceBusy = false; refreshWorkforce() } } }) { Text("Save task") }
                    Text(workforceNotice, style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(workforceTasks) { task -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(task.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(task.state) }; if (task.state in listOf("PLANNING", "ASSIGNED", "RUNNING")) { Text("${task.progressPercent}% · ${task.progressStage.replace('_', ' ')}", style = MaterialTheme.typography.labelSmall); LinearProgressIndicator(progress = task.progressPercent / 100f, modifier = Modifier.fillMaxWidth()) }; if (!task.result.isNullOrBlank()) Text(task.result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)); if (!task.error.isNullOrBlank()) Text(task.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); if (task.state in listOf("RUNNING", "PLANNING", "ASSIGNED")) TextButton(enabled = !workforceBusy, onClick = { scope.launch { workforceBusy = true; try { withContext(Dispatchers.IO) { cancelWorkforceTask(base, token, task.id) }; workforceNotice = "Cancellation requested. An in-flight provider call may finish before NOVA stops at the next checkpoint." } catch (e: Exception) { workforceNotice = e.message ?: "Could not request cancellation" } finally { workforceBusy = false; refreshWorkforce() } } }) { Text("Request cancel") }; if (task.state !in listOf("RUNNING", "PLANNING", "ASSIGNED", "COMPLETED", "CANCELLED")) TextButton(enabled = !workforceBusy, onClick = { scope.launch { workforceBusy = true; workforceNotice = "Head Manager planning and workers are calling configured providers…"; try { withContext(Dispatchers.IO) { executeWorkforceTask(base, token, task.id) }; workforceNotice = "Execution finished; output and events are saved by the backend." } catch (e: Exception) { workforceNotice = e.message ?: "Task execution failed. Check saved task status and provider settings." } finally { workforceBusy = false; refreshWorkforce() } } }) { Text(if (task.state == "FAILED") "Retry (bounded)" else "Run with Head Manager") } } } } }
                }
                Section.SETTINGS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 20.dp)); Text("Backend URL: ${base.ifBlank { "Not configured" }}"); Text("NOVA token: ${if (token.isBlank()) "Not configured" else "Stored encrypted on this device"}"); Button(onClick = { showSettings = true }) { Text("Configure backend") }; Text("Backend runtime provider: ${if (providerConfigured) "Configured" else "Not configured"}"); Button(onClick = openProviderSettings) { Text("Configure runtime model") }; HorizontalDivider(); Text(usageSummary, style = MaterialTheme.typography.bodySmall); Text("Per-agent recorded workload", style = MaterialTheme.typography.titleSmall); Text(analyticsNote, style = MaterialTheme.typography.bodySmall); agentAnalytics.forEach { metric -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text("${metric.name} · ${metric.status}", fontWeight = FontWeight.SemiBold); Text("Assignments ${metric.assignments} · ${metric.completed} complete · ${metric.failed} failed · ${metric.active} active", style = MaterialTheme.typography.bodySmall); Text("Provider calls ${metric.requests} · ${metric.successful} successful · ${metric.failedRequests} failed · reported tokens ${metric.inputTokens + metric.outputTokens}", style = MaterialTheme.typography.bodySmall); Text("Mean completed assignment duration: ${analyticsDuration(metric)}", style = MaterialTheme.typography.bodySmall) } } }; Text("Provider router · ${providerConnections.size} saved connection(s)"); providerConnections.forEach { Text("${it.provider} · ${it.status} · ${it.models.size} model IDs", style = MaterialTheme.typography.bodySmall) }; Button(onClick = { providerDialog = true; registryKey = ""; registryStatus = "Add provider credentials over HTTPS; they are encrypted by NOVA on the server." }) { Text("Configure multi-provider router") }; Button(onClick = refreshWorkforce) { Text("Refresh workforce and usage") }; Text("Resource overview shows recorded requests only. Provider-specific limits and costs depend on account/model and are not guessed.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (showProviderSettings) AlertDialog(
        onDismissRequest = { if (!providerBusy) { showProviderSettings = false } },
        title = { Text("AI provider setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Arena · OpenAI-compatible API", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = providerModel, onValueChange = { providerModel = it }, label = { Text("Exact model ID") }, singleLine = true, enabled = !providerBusy)
                Text("Provider API keys are backend runtime secrets only. This screen changes the model ID and never uploads an upstream key.", style = MaterialTheme.typography.bodySmall)
                if (providerModelOptions.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(providerModelOptions.take(8)) { modelId -> TextButton(onClick = { providerModel = modelId }, enabled = !providerBusy) { Text(modelId, maxLines = 1) } } }
                Text(providerStatus, style = MaterialTheme.typography.bodySmall, color = if (providerConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !providerBusy && providerModel.isNotBlank(), onClick = { providerBusy = true; providerStatus = "Saving encrypted provider settings…"; scope.launch {
                try {
                    val config = withContext(Dispatchers.IO) { saveProviderConfiguration(base, token, providerModel) }
                    providerConfigured = config.configured; providerKeySource = config.keySource; providerModel = config.modelId
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
                TextButton(enabled = !providerBusy, onClick = { showProviderSettings = false }) { Text("Close") }
            }
        }
    )
    if (providerDialog) AlertDialog(
        onDismissRequest = { providerDialog = false; registryKey = "" },
        title = { Text("Provider router setup") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = registryProvider, onValueChange = { registryProvider = it.trim().lowercase() }, label = { Text("Gateway protocol") }, supportingText = { Text("openai_compatible · anthropic_compatible · claude_gateway") }, singleLine = true)
            if (registryProvider in listOf("claude_gateway", "openai_compatible", "anthropic_compatible")) {
                OutlinedTextField(value = registryKey, onValueChange = { registryKey = it }, label = { Text("Restricted gateway access token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(value = registryEndpoint, onValueChange = { registryEndpoint = it.trim() }, label = { Text("Gateway HTTPS base URL (must end /v1)") }, supportingText = { Text("Operator must allow-list the exact hostname in NOVA_GATEWAY_ALLOWED_HOSTS.") }, singleLine = true)
            } else Text("Upstream provider keys are backend/external-gateway runtime secrets. Only restricted gateway tokens may be entered here.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = registryModel, onValueChange = { registryModel = it }, label = { Text("Exact model ID (optional; discovered after test)") }, singleLine = true)
            Text("The gateway token is encrypted at rest by NOVA and is never returned to this app. Model listing tests do not prove inference or availability.", style = MaterialTheme.typography.bodySmall)
            if (registryProvider == "claude_gateway") {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Mukller/claude-code-gateway"))) }) { Text("Open gateway setup docs") }
                Text("Self-hosted; upstream provider credentials and inference budget remain with the gateway operator.", style = MaterialTheme.typography.bodySmall)
            }
            if (registryProvider == "openai_compatible") Text("Uses /models and /chat/completions. Upstream provider credentials stay at the gateway.", style = MaterialTheme.typography.bodySmall)
            if (registryProvider == "anthropic_compatible") Text("Uses /models and /messages with x-api-key authentication.", style = MaterialTheme.typography.bodySmall)
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
        confirmButton = { TextButton(enabled = registryKey.length >= 8 && !providerBusy && registryProvider in listOf("claude_gateway", "openai_compatible", "anthropic_compatible") && registryEndpoint.startsWith("https://"), onClick = { providerBusy = true; registryStatus = "Saving restricted gateway token…"; scope.launch { try { val id = withContext(Dispatchers.IO) { saveProviderConnection(base, token, registryProvider, registryKey, registryModel, registryAccountId, registryEndpoint) }; registryKey = ""; val ids = withContext(Dispatchers.IO) { testProviderConnectionById(base, token, id) }; registryStatus = "Connected · ${ids.size} model IDs listed (catalog check only)"; refreshWorkforce() } catch (e: Exception) { registryStatus = e.message ?: "Provider setup failed" } finally { providerBusy = false } } }) { Text(if (providerBusy) "Checking…" else "Save and test") } },
        dismissButton = { TextButton(onClick = { providerDialog = false; registryKey = "" }) { Text("Close") } }
    )
    editingAgent?.let { agent ->
        var selectedProvider by remember(agent.id) { mutableStateOf(agent.provider) }
        var selectedModel by remember(agent.id) { mutableStateOf(agent.model) }
        var selectedInstructions by remember(agent.id) { mutableStateOf(agent.instructions) }
        var selectedTools by remember(agent.id) { mutableStateOf(agent.permissions.toSet()) }
        AlertDialog(onDismissRequest = { editingAgent = null }, title = { Text("Configure ${agent.name}") }, text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Identity ${agent.identity} stays independent even when model/provider choices are shared.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = selectedProvider, onValueChange = { selectedProvider = it.trim().lowercase() }, label = { Text("Provider identity") }, singleLine = true)
            OutlinedTextField(value = selectedModel, onValueChange = { selectedModel = it.trim() }, label = { Text("Exact model ID from provider discovery") }, singleLine = true)
            val availableAgentModels = providerConnections.firstOrNull { it.provider == selectedProvider }?.models.orEmpty()
            if (availableAgentModels.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(availableAgentModels.take(30)) { modelId -> TextButton(onClick = { selectedModel = modelId }) { Text(modelId, maxLines = 1) } } }
            OutlinedTextField(value = selectedInstructions, onValueChange = { selectedInstructions = it }, label = { Text("Agent-specific instructions") }, minLines = 3, maxLines = 6)
            Text("Safe deterministic tools (off by default)", style = MaterialTheme.typography.labelMedium)
            listOf("math.calculate", "text.analyze", "memory.search").forEach { tool ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = tool in selectedTools, onCheckedChange = { checked -> selectedTools = if (checked) selectedTools + tool else selectedTools - tool })
                    Text(tool, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("These tools cannot run shell commands, access arbitrary URLs, send messages, or modify external systems.", style = MaterialTheme.typography.labelSmall)
            Text("Specialties: ${agent.specialties.joinToString()}", style = MaterialTheme.typography.labelSmall)
        } }, confirmButton = { TextButton(enabled = selectedProvider.isNotBlank() && selectedModel.isNotBlank() && !workforceBusy, onClick = { workforceBusy = true; scope.launch { try { val updated = withContext(Dispatchers.IO) { saveAgentConfig(base, token, agent, selectedProvider, selectedModel, selectedInstructions, selectedTools) }; workforceAgents.indexOfFirst { it.id == updated.id }.takeIf { it >= 0 }?.let { index -> workforceAgents[index] = updated }; workforceNotice = "Saved ${updated.identity} model and instructions."; editingAgent = null } catch (e: Exception) { workforceNotice = e.message ?: "Could not update agent" } finally { workforceBusy = false } } }) { Text("Save agent") } }, dismissButton = { TextButton(onClick = { editingAgent = null }) { Text("Cancel") } })
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
                        items(listOf("desk", "chair", "computer", "monitor", "whiteboard", "meeting_table", "server_rack", "coffee_machine", "project_board")) { kind ->
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
                Text(agent.title, style = MaterialTheme.typography.titleSmall)
                Text("Identity: ${agent.identity} · ${agent.status}", style = MaterialTheme.typography.bodySmall)
                Text("Model: ${agent.provider}/${agent.model}", style = MaterialTheme.typography.bodySmall)
                val currentTask = workforceTasks.firstOrNull { it.assignedAgentId == agent.id && it.state == "RUNNING" }
                Text("Current task: ${currentTask?.title ?: "No running task in latest snapshot"}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { selectedOfficeAgent = null; section = Section.TASKS }, enabled = workforceTasks.isNotEmpty()) { Text("Open saved task controls") }
                Text("Specialties: ${agent.specialties.joinToString().ifBlank { "Not configured" }}", style = MaterialTheme.typography.bodySmall)
                Text("Tools: ${agent.permissions.joinToString().ifBlank { "None enabled" }}", style = MaterialTheme.typography.bodySmall)
                Text("Saved position: ${officeWorld?.characters?.get(agent.id)?.let { "${it.x}, ${it.y} · ${it.movementState}" } ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
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
                        agentChatStatus = "Waiting for ${agent.provider}/${agent.model}…"
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) { sendAgentChat(base, token, agent.id, prompt, agentChatConversationId) }
                                if (selectedOfficeAgent?.id == agent.id) {
                                    agentChatConversationId = result.first
                                    agentChatLines.add(ChatLine(result.second, false))
                                    agentChatStatus = "Response from ${result.third}/${result.fourth} · saved"
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
private fun saveProviderConfiguration(base: String, token: String, model: String): ProviderConfig {
    val body = JSONObject().put("provider", "arena").put("model_id", model.trim())
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

private fun loadWorkforce(base: String, token: String): WorkforceSnapshot<List<WorkforceAgent>, List<WorkforceTask>, List<OfficeEvent>, List<ProviderConnectionInfo>, OfficeWorld> {
    val agentsJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/agents"))
    val agents = (0 until agentsJson.length()).map { i ->
        val item = agentsJson.getJSONObject(i)
        WorkforceAgent(item.getString("id"), item.optString("identity", "worker"), item.optString("name", "Agent"), item.optString("job_title", "Worker"), item.optString("provider", "arena"), item.optString("model_id", ""), item.optString("instructions", ""), item.optString("status", "idle"), jsonStrings(item.optJSONArray("specialties")), item.optDouble("temperature", 0.2), item.optInt("context_length", 8192), jsonStrings(item.optJSONArray("tool_permissions")), item.optBoolean("memory_enabled", true), item.optInt("priority", 50), item.optBoolean("critical"), item.optInt("max_task_duration_seconds", 300))
    }
    val tasksJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/tasks"))
    val tasks = (0 until tasksJson.length()).map { i -> tasksJson.getJSONObject(i).let { item ->
        val checkpoint = item.optJSONObject("checkpoint") ?: JSONObject()
        WorkforceTask(item.getString("id"), item.optString("title", "Task"), item.optString("state", "RECEIVED"), item.optString("result").takeIf { value -> value.isNotBlank() && value != "null" }, item.optString("error_detail").takeIf { value -> value.isNotBlank() && value != "null" }, item.optInt("priority", 50), item.optString("assigned_agent_id").takeIf { value -> value.isNotBlank() && value != "null" }, checkpoint.optInt("progress_percent", 0).coerceIn(0,100), checkpoint.optString("stage", "queued"))
    } }
    val eventsJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/events/recent?limit=100"))
    val events = (0 until eventsJson.length()).map { i -> eventsJson.getJSONObject(i).let { OfficeEvent(it.optString("event_type", "event"), it.optString("agent_id").takeIf { value -> value.isNotBlank() && value != "null" }, it.optString("task_id").takeIf { value -> value.isNotBlank() && value != "null" }, it.optString("created_at"), it.optJSONObject("payload")?.toString() ?: "{}") } }
    val providerJson = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/providers/connections"))
    val connections = (0 until providerJson.length()).map { i -> providerJson.getJSONObject(i).let { ProviderConnectionInfo(it.getString("id"), it.optString("provider"), jsonStrings(it.optJSONArray("models")), it.optInt("priority", 100), it.optString("status", "not_tested")) } }
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

private fun startMeeting(base:String,token:String,id:String) { authorizedRequest(base,token,"/api/v1/meetings/$id/start","POST") }
private fun cancelMeeting(base:String,token:String,id:String) { authorizedRequest(base,token,"/api/v1/meetings/$id/cancel","POST") }
private fun createMeetingHandoff(base:String,token:String,meetingId:String,taskId:String,agentId:String,instructions:String) {
    authorizedRequest(base,token,"/api/v1/meetings/$meetingId/handoffs","POST",JSONObject().put("task_id",taskId).put("agent_id",agentId).put("instructions",instructions))
}

private fun createWorkforceTask(base: String, token: String, title: String, description: String, dependencies: List<String>) {
    val dependencyArray = org.json.JSONArray().also { array -> dependencies.forEach { array.put(it) } }
    authorizedRequest(base, token, "/api/v1/tasks", "POST", JSONObject().put("title", title).put("description", description).put("dependencies", dependencyArray))
}

private fun controlWorkforceAgent(base: String, token: String, agentId: String, action: String) {
    authorizedRequest(base, token, "/api/v1/agents/$agentId/$action", "POST")
}

private fun executeWorkforceTask(base: String, token: String, taskId: String) {
    authorizedRequest(base, token, "/api/v1/tasks/$taskId/execute", "POST")
}

private fun cancelWorkforceTask(base: String, token: String, taskId: String) {
    authorizedRequest(base, token, "/api/v1/tasks/$taskId/cancel", "POST")
}

private fun loadAgentChat(base: String, token: String, agentId: String): AgentChatSnapshot {
    val json = JSONObject(authorizedRequest(base, token, "/api/v1/agents/$agentId/chat"))
    val messages = json.optJSONArray("messages") ?: org.json.JSONArray()
    val lines = (0 until messages.length()).map { i -> messages.getJSONObject(i).let { item -> ChatLine(item.optString("content"), item.optString("role") == "user") } }
    return AgentChatSnapshot(json.optString("conversation_id").takeIf { it.isNotBlank() && it != "null" }, lines)
}

private fun sendAgentChat(base: String, token: String, agentId: String, message: String, conversationId: String?): AgentChatReply {
    val body = JSONObject().put("message", message)
    if (!conversationId.isNullOrBlank()) body.put("conversation_id", conversationId)
    val json = JSONObject(authorizedRequest(base, token, "/api/v1/agents/$agentId/chat", "POST", body))
    return AgentChatReply(json.getString("conversation_id"), json.getString("response"), json.getString("provider"), json.getString("model"))
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

private fun saveAgentConfig(base: String, token: String, agent: WorkforceAgent, provider: String, model: String, instructions: String, selectedTools: Set<String>): WorkforceAgent {
    val specialties = org.json.JSONArray().also { list -> agent.specialties.forEach { list.put(it) } }
    val tools = org.json.JSONArray().also { list -> selectedTools.sorted().forEach { list.put(it) } }
    val body = JSONObject().put("identity", agent.identity).put("name", agent.name).put("job_title", agent.title).put("provider", provider).put("model_id", model).put("specialties", specialties).put("instructions", instructions).put("temperature", agent.temperature).put("context_length", agent.contextLength).put("tool_permissions", tools).put("memory_enabled", agent.memoryEnabled).put("priority", agent.priority).put("critical", agent.critical).put("max_task_duration_seconds", agent.maxDuration)
    val item = JSONObject(authorizedRequest(base, token, "/api/v1/agents/${agent.id}", "PUT", body))
    return WorkforceAgent(item.getString("id"), item.getString("identity"), item.getString("name"), item.getString("job_title"), item.getString("provider"), item.getString("model_id"), item.optString("instructions", ""), item.optString("status", "idle"), jsonStrings(item.optJSONArray("specialties")), item.optDouble("temperature", 0.2), item.optInt("context_length", 8192), jsonStrings(item.optJSONArray("tool_permissions")), item.optBoolean("memory_enabled", true), item.optInt("priority", 50), item.optBoolean("critical"), item.optInt("max_task_duration_seconds", 300))
}

private fun saveProviderConnection(base: String, token: String, provider: String, apiKey: String, model: String, accountId: String, endpoint: String): String {
    if (provider !in listOf("claude_gateway", "openai_compatible", "anthropic_compatible")) throw IOException("Only restricted gateway credentials may be entered in the app")
    if (!endpoint.startsWith("https://")) throw IOException("Gateway URL must use HTTPS")
    val models = org.json.JSONArray().also { if (model.isNotBlank()) it.put(model.trim()) }
    val body = JSONObject().put("provider", provider).put("api_key", apiKey.trim()).put("model_ids", models).put("endpoint", endpoint.trim().trimEnd('/'))
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
