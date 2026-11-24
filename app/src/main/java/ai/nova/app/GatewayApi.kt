package ai.nova.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PasswordVisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val NOVA_GATEWAY_BASE = "https://gateway-server-mea8.onrender.com"
private const val NOVA_GATEWAY_WS = "wss://gateway-server-mea8.onrender.com"
private val gatewayHttp = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()

internal data class GatewaySession(val accessToken: String, val refreshToken: String, val workspaceId: String)
internal data class GatewaySnapshot(
    val head: JSONObject, val workers: JSONArray, val gateways: JSONArray, val headPool: JSONObject,
    val workerPool: JSONObject, val tasks: JSONArray, val messages: JSONArray, val memory: JSONArray, val activity: JSONArray,
)

private fun responseObject(raw: String): JSONObject = runCatching { JSONObject(raw) }.getOrElse {
    JSONObject().put("items", runCatching { JSONArray(raw) }.getOrElse { JSONArray() })
}

/** All gateway API and websocket traffic is TLS-only. Provider keys are one-shot payload data, never stored. */
internal class GatewayClient(private val secure: SecureSettings) {
    fun session(): GatewaySession? {
        val access = secure.get("gateway_access_token")
        val refresh = secure.get("gateway_refresh_token")
        val workspace = secure.get("gateway_workspace_id")
        return if (access.isNotBlank() && refresh.isNotBlank() && workspace.isNotBlank()) GatewaySession(access, refresh, workspace) else null
    }

    fun clearSession() {
        secure.put("gateway_access_token", "")
        secure.put("gateway_refresh_token", "")
        secure.put("gateway_workspace_id", "")
    }

    private fun saveSession(json: JSONObject, old: GatewaySession? = null): GatewaySession {
        val session = GatewaySession(
            json.optString("access_token").takeIf(String::isNotBlank) ?: throw IOException("Gateway response omitted access_token"),
            json.optString("refresh_token").takeIf(String::isNotBlank) ?: old?.refreshToken ?: throw IOException("Gateway response omitted refresh_token"),
            json.optString("workspace_id").takeIf(String::isNotBlank) ?: old?.workspaceId ?: throw IOException("Gateway response omitted workspace_id"),
        )
        secure.put("gateway_access_token", session.accessToken)
        secure.put("gateway_refresh_token", session.refreshToken)
        secure.put("gateway_workspace_id", session.workspaceId)
        return session
    }

    fun login(email: String, password: String) = saveSession(JSONObject(auth("/v1/auth/login", JSONObject().put("email", email.trim()).put("password", password))))

    fun register(email: String, password: String, name: String, workspace: String): GatewaySession {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        if (name.isNotBlank()) body.put("full_name", name.trim())
        if (workspace.isNotBlank()) body.put("workspace_name", workspace.trim())
        return saveSession(JSONObject(auth("/v1/auth/register", body)))
    }

    private fun auth(path: String, body: JSONObject): String = try {
        execute(path, "POST", body, null, false)
    } catch (_: Exception) {
        throw IOException("Gateway authentication failed. Check the account details and try again.")
    }

    @Synchronized private fun refresh(old: GatewaySession): GatewaySession {
        session()?.takeIf { it.accessToken != old.accessToken || it.refreshToken != old.refreshToken }?.let { return it }
        val result = JSONObject(execute("/v1/auth/refresh", "POST", JSONObject().put("refresh_token", old.refreshToken), null, false))
        return saveSession(result, old)
    }

    fun request(path: String, method: String = "GET", body: JSONObject? = null): String {
        val current = session() ?: throw IOException("Sign in to NOVA-GATEWAY first")
        return try { execute(path, method, body, current, true) }
        catch (error: GatewayUnauthorized) { clearSession(); throw IOException("Gateway session expired. Sign in again.", error) }
    }

    private fun execute(path: String, method: String, body: JSONObject?, credentials: GatewaySession?, retry: Boolean): String {
        if (!path.startsWith("/v1/") || path.contains("..")) throw IOException("Invalid gateway API path")
        val builder = Request.Builder().url(NOVA_GATEWAY_BASE + path).header("Accept", "application/json")
        if (credentials != null) {
            builder.header("Authorization", "Bearer ${credentials.accessToken}")
            builder.header("X-Workspace-Id", credentials.workspaceId)
        }
        if (method == "GET") builder.get() else {
            val json = (body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            when (method) {
                "POST" -> builder.post(json)
                "PUT" -> builder.put(json)
                "PATCH" -> builder.patch(json)
                "DELETE" -> if (body == null) builder.delete() else builder.delete(json)
                else -> throw IOException("Unsupported gateway method")
            }
        }
        gatewayHttp.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 && credentials != null && retry) {
                val renewed = try { refresh(credentials) } catch (e: Exception) { throw GatewayUnauthorized(e) }
                return execute(path, method, body, renewed, false)
            }
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrDefault(text).take(200)
                if (response.code == 401) throw GatewayUnauthorized(IOException(detail))
                throw IOException("NOVA-GATEWAY HTTP ${response.code}${if (detail.isBlank()) "" else ": $detail"}")
            }
            return text.ifBlank { "{}" }
        }
    }

    fun loadSnapshot(): GatewaySnapshot = GatewaySnapshot(
        responseObject(request("/v1/head/status")),
        responseArray(request("/v1/workers"), "workers", "items", "data"),
        responseArray(request("/v1/gateways"), "gateways", "items", "data"),
        responseObject(request("/v1/models/pools/head")),
        responseObject(request("/v1/models/pools/workers")),
        responseArray(request("/v1/tasks"), "tasks", "items", "data"),
        responseArray(request("/v1/messages"), "messages", "items", "data"),
        responseArray(request("/v1/memory"), "memory", "memories", "items", "data"),
        responseArray(request("/v1/activity"), "activity", "items", "data"),
    )

    fun openSocket(credentials: GatewaySession, event: (JSONObject) -> Unit, closed: () -> Unit): WebSocket {
        val url = NOVA_GATEWAY_WS.toHttpUrl().newBuilder()
            .addPathSegment("ws").addPathSegment(credentials.workspaceId)
            .addQueryParameter("token", credentials.accessToken).build()
        val req = Request.Builder().url(url).header("X-Workspace-Id", credentials.workspaceId).build()
        return gatewayHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { runCatching { JSONObject(text) }.getOrNull()?.let(event) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed() }
        })
    }
}

private class GatewayUnauthorized(cause: Throwable? = null) : IOException("Gateway rejected credentials", cause)

private fun responseArray(raw: String, vararg keys: String): JSONArray {
    val root = runCatching { JSONObject(raw) }.getOrNull()
    keys.forEach { key ->
        root?.optJSONArray(key)?.let { return it }
        root?.optJSONObject(key)?.let { nested ->
            keys.forEach { nestedKey -> nested.optJSONArray(nestedKey)?.let { return it } }
        }
    }
    return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
}

internal fun gatewayHeadOrchestrate(client: GatewayClient, prompt: String): String {
    val json = JSONObject(client.request("/v1/head/orchestrate", "POST", JSONObject().put("prompt", prompt).put("require_reasoning", false)))
    for (key in listOf("response", "message", "output", "content", "result")) {
        val value = json.opt(key)
        if (value is String && value.isNotBlank()) return value
        if (value is JSONObject || value is JSONArray) return value.toString(2)
    }
    return json.toString(2)
}

internal fun isSafeGatewayId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,160}"))

internal fun isSecureProviderBaseUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value.trim())
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)

internal fun gatewayWorkerId(client: GatewayClient, identity: String, fallback: String): String {
    val raw = client.request("/v1/workers")
    val rows = responseArray(raw, "workers", "items", "data")
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        if (row.optString("identity") == identity || row.optString("role") == identity || row.optString("key") == identity) {
            val id = row.optString("worker_id").ifBlank { row.optString("id") }.takeIf(String::isNotBlank)
                ?: throw IOException("Gateway worker record has no ID")
            if (!isSafeGatewayId(id)) throw IOException("Gateway returned an invalid worker ID")
            return id
        }
    }
    if ((0 until rows.length()).any { i -> rows.optJSONObject(i)?.let { it.optString("id") == fallback || it.optString("worker_id") == fallback } == true }) {
        if (!isSafeGatewayId(fallback)) throw IOException("Gateway returned an invalid worker ID")
        return fallback
    }
    throw IOException("No gateway worker matches '$identity'. Refresh its roster.")
}

internal fun gatewayAssignWorkerModel(client: GatewayClient, workerId: String, modelIdentityId: String): String {
    if (!isSafeGatewayId(workerId) || !isSafeGatewayId(modelIdentityId)) throw IOException("Worker and model identity IDs must use letters, digits, underscore, or hyphen")
    return client.request("/v1/workers/$workerId/assign-model", "POST", JSONObject().put("model_identity_id", modelIdentityId))
}

internal fun gatewayWorkerChat(client: GatewayClient, workerId: String, message: String): String {
    if (!isSafeGatewayId(workerId)) throw IOException("Invalid gateway worker ID")
    val raw = client.request("/v1/workers/$workerId/chat", "POST", JSONObject().put("message", message))
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
    for (key in listOf("response", "message", "output", "content", "reply")) {
        val value = json.opt(key)
        if (value is String && value.isNotBlank()) return value
        if (value is JSONObject || value is JSONArray) return value.toString(2)
    }
    return json.toString(2)
}

@Composable
internal fun GatewayWorkspaceScreen(client: GatewayClient, modifier: Modifier = Modifier, onSessionChanged: (GatewaySession?) -> Unit) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(client.session()) }
    LaunchedEffect(session?.accessToken, session?.workspaceId) { onSessionChanged(session) }
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var workspaceName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("Sign in or register for a NOVA-GATEWAY workspace.") }
    var data by remember { mutableStateOf<GatewaySnapshot?>(null) }
    var headPrompt by remember { mutableStateOf("") }
    var headReply by remember { mutableStateOf("") }
    var requireReasoning by remember { mutableStateOf(false) }
    var workerId by remember { mutableStateOf("") }
    var modelIdentity by remember { mutableStateOf("") }
    var workerMessage by remember { mutableStateOf("") }
    var workerTaskId by remember { mutableStateOf("") }
    var workerReply by remember { mutableStateOf("") }
    var taskTitle by remember { mutableStateOf("") }
    var taskDescription by remember { mutableStateOf("") }
    var taskPriority by remember { mutableStateOf("3") }
    var messageRecipientType by remember { mutableStateOf("head") }
    var messageRecipientId by remember { mutableStateOf("") }
    var messageTaskId by remember { mutableStateOf("") }
    var messageContent by remember { mutableStateOf("") }
    var memoryLayer by remember { mutableStateOf("workspace") }
    var memoryOwnerType by remember { mutableStateOf("workspace") }
    var memoryOwnerId by remember { mutableStateOf("") }
    var memoryKey by remember { mutableStateOf("") }
    var memoryContent by remember { mutableStateOf("") }
    var memoryShared by remember { mutableStateOf(false) }
    var gatewayName by remember { mutableStateOf("") }
    var providerType by remember { mutableStateOf("openai_compatible") }
    var gatewayBaseUrl by remember { mutableStateOf("") }
    var providerKey by remember { mutableStateOf("") }
    var realtimeBusy by remember { mutableStateOf(false) }
    val events = remember { mutableStateListOf<String>() }
    val scroll = rememberScrollState()

    val refresh: suspend () -> Unit = {
        if (session != null) {
            loading = true
            try {
                data = withContext(Dispatchers.IO) { client.loadSnapshot() }
                session = client.session()
                notice = "Workspace data refreshed · ${session?.workspaceId ?: "session expired"}"
            } catch (e: Exception) {
                session = client.session()
                notice = e.message ?: "Gateway sync failed"
            } finally { loading = false }
        }
    }
    fun action(label: String, request: () -> String, result: (String) -> Unit = {}, clearBusy: () -> Unit = {}, sensitive: Boolean = false) {
        scope.launch {
            try {
                val output = withContext(Dispatchers.IO) { request() }
                notice = output.ifBlank { "$label completed." }
                result(output)
                refresh()
            } catch (e: Exception) { notice = if (sensitive) "$label failed. Check gateway settings and try again." else "$label failed: ${e.message ?: "gateway request failed"}" }
            finally { clearBusy() }
        }
    }
    LaunchedEffect(session?.workspaceId) { if (session != null) refresh() }
    LaunchedEffect(session?.workspaceId, session?.accessToken) {
        val credentials = session ?: return@LaunchedEffect
        var delayMs = 1_000L
        while (isActive) {
            val closed = Channel<Unit>(Channel.CONFLATED)
            val socket = client.openSocket(credentials, { event ->
                scope.launch {
                    if (event.optString("workspace_id") == credentials.workspaceId) {
                        events.add(0, redactJson(event.toString()))
                        while (events.size > 30) events.removeAt(events.lastIndex)
                        notice = "Live gateway event · ${event.optString("event", "unknown")}"
                    }
                }
            }, { closed.trySend(Unit) })
            try { closed.receive() } finally { socket.cancel() }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("NOVA-GATEWAY", style = MaterialTheme.typography.headlineSmall)
        Text("$NOVA_GATEWAY_BASE · TLS only", style = MaterialTheme.typography.labelSmall)
        Text(notice, style = MaterialTheme.typography.bodySmall)
        if (session == null) {
            Text(if (register) "Create account and workspace" else "Sign in", style = MaterialTheme.typography.titleMedium)
            if (register) {
                OutlinedTextField(fullName, { fullName = it }, label = { Text("Full name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(workspaceName, { workspaceName = it }, label = { Text("Workspace name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val authenticated = withContext(Dispatchers.IO) { if (register) client.register(email, password, fullName, workspaceName) else client.login(email, password) }
                            session = authenticated; password = ""; notice = "Authenticated to workspace ${authenticated.workspaceId}."
                        } catch (e: Exception) { notice = e.message ?: "Gateway authentication failed" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Please wait…" else if (register) "Register" else "Login") }
                TextButton(onClick = { register = !register }) { Text(if (register) "Existing account? Login" else "Create account") }
            }
            Text("Only JWT access_token, refresh_token, and workspace_id are persisted. They are encrypted using Android Keystore. Provider API keys are never stored in the app.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Workspace: ${session!!.workspaceId}", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !loading, onClick = { scope.launch { refresh() } }) { Text(if (loading) "Syncing…" else "Refresh") }
                TextButton(onClick = { client.clearSession(); session = null; data = null; events.clear(); notice = "JWTs and workspace ID cleared from this device." }) { Text("Sign out") }
            }
            data?.let { s ->
                GatewayCard("Head status", s.head.toString(2))
                GatewayCard("Verified Claude-family pool for Head", s.headPool.toString(2))
                OutlinedTextField(headPrompt, { headPrompt = it }, label = { Text("Prompt for Head") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = headPrompt.isNotBlank(), onClick = { val prompt = headPrompt; action("Head orchestration", { client.request("/v1/head/orchestrate", "POST", JSONObject().put("prompt", prompt).put("require_reasoning", requireReasoning)) }, { headReply = formatReply(it); headPrompt = "" }) }) { Text("Orchestrate") }
                    TextButton(onClick = { requireReasoning = !requireReasoning }) { Text(if (requireReasoning) "Reasoning required" else "Reasoning optional") }
                }
                if (headReply.isNotBlank()) GatewayCard("Head response", headReply)
                GatewayCard("Specialized workers · ${s.workers.length()}", s.workers.pretty())
                OutlinedTextField(workerId, { workerId = it }, label = { Text("Worker ID (from roster)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(modelIdentity, { modelIdentity = it }, label = { Text("Verified model_identity_id") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(enabled = isSafeGatewayId(workerId.trim()) && isSafeGatewayId(modelIdentity.trim()), onClick = { val selectedWorker = workerId.trim(); val selectedModel = modelIdentity.trim(); action("Worker model assignment", { gatewayAssignWorkerModel(client, selectedWorker, selectedModel) }) }) { Text("Assign non-Claude model") }
                OutlinedTextField(workerMessage, { workerMessage = it }, label = { Text("Message worker") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(workerTaskId, { workerTaskId = it }, label = { Text("task_id (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(enabled = isSafeGatewayId(workerId.trim()) && workerMessage.isNotBlank(), onClick = { val text = workerMessage; val task = workerTaskId; action("Worker chat", { client.request("/v1/workers/${workerId.trim()}/chat", "POST", JSONObject().put("message", text).apply { if (task.isNotBlank()) put("task_id", task.trim()) }) }, { workerReply = formatReply(it); workerMessage = "" }) }) { Text("Chat with worker") }
                if (workerReply.isNotBlank()) GatewayCard("Worker reply", workerReply)
                GatewayCard("Verified non-Claude worker pool", s.workerPool.toString(2))
                OutlinedTextField(taskTitle, { taskTitle = it }, label = { Text("Task title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(taskDescription, { taskDescription = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(taskPriority, { taskPriority = it }, label = { Text("Priority") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(enabled = taskTitle.isNotBlank(), onClick = { val title = taskTitle; val description = taskDescription; val priority = taskPriority.toIntOrNull() ?: 3; val assigned = workerId; action("Create task", { client.request("/v1/tasks", "POST", JSONObject().put("title", title).put("description", description).put("priority", priority).apply { if (assigned.isNotBlank()) put("assigned_worker_id", assigned.trim()) }) }, { taskTitle = ""; taskDescription = "" }) }) { Text("Create task") }
                GatewayCard("Tasks", s.tasks.pretty())
                GatewayCard("Messages", s.messages.pretty())
                OutlinedTextField(messageRecipientType, { messageRecipientType = it }, label = { Text("recipient_type") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(messageRecipientId, { messageRecipientId = it }, label = { Text("recipient_id (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(messageTaskId, { messageTaskId = it }, label = { Text("task_id (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(messageContent, { messageContent = it }, label = { Text("Message content") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(enabled = messageContent.isNotBlank(), onClick = { val content = messageContent; action("Send message", { client.request("/v1/messages", "POST", JSONObject().put("recipient_type", messageRecipientType).put("content", content).put("message_type", "text").apply { if (messageRecipientId.isNotBlank()) put("recipient_id", messageRecipientId.trim()); if (messageTaskId.isNotBlank()) put("task_id", messageTaskId.trim()) }) }, { messageContent = "" }) }) { Text("Send workspace message") }
                GatewayCard("Memory", s.memory.pretty())
                OutlinedTextField(memoryLayer, { memoryLayer = it }, label = { Text("layer") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(memoryOwnerType, { memoryOwnerType = it }, label = { Text("owner_type") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(memoryOwnerId, { memoryOwnerId = it }, label = { Text("owner_id") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(memoryKey, { memoryKey = it }, label = { Text("key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(memoryContent, { memoryContent = it }, label = { Text("content") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(enabled = memoryKey.isNotBlank() && memoryContent.isNotBlank(), onClick = { val key = memoryKey; val content = memoryContent; action("Save memory", { client.request("/v1/memory", "POST", JSONObject().put("layer", memoryLayer).put("owner_type", memoryOwnerType).put("key", key).put("content", content).put("tags", JSONArray()).put("is_shared", memoryShared).apply { if (memoryOwnerId.isNotBlank()) put("owner_id", memoryOwnerId.trim()) }) }, { memoryContent = "" }) }) { Text("Save memory") }
                TextButton(onClick = { memoryShared = !memoryShared }) { Text(if (memoryShared) "Shared memory ✓" else "Private memory") }
                GatewayCard("Workspace activity", s.activity.pretty())
                GatewayCard("Connected gateways", s.gateways.pretty())
                OutlinedTextField(gatewayName, { gatewayName = it }, label = { Text("Gateway name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(providerType, { providerType = it }, label = { Text("provider_type") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(gatewayBaseUrl, { gatewayBaseUrl = it }, label = { Text("Provider base_url") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(providerKey, { providerKey = it }, label = { Text("Provider api_key · submitted directly to gateway") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button(enabled = !realtimeBusy && gatewayName.isNotBlank() && providerType.isNotBlank() && isSecureProviderBaseUrl(gatewayBaseUrl) && providerKey.isNotBlank(), onClick = {
                    val oneShotKey = providerKey
                    val payload = JSONObject().put("name", gatewayName.trim()).put("provider_type", providerType.trim()).put("base_url", gatewayBaseUrl.trim()).put("api_key", oneShotKey)
                    providerKey = ""
                    realtimeBusy = true
                    action("Register gateway", { client.request("/v1/gateways", "POST", payload); "Gateway registered. Provider credentials were sent only to NOVA-GATEWAY and were not saved by this app." }, clearBusy = { realtimeBusy = false }, sensitive = true)
                }) { Text(if (realtimeBusy) "Sending…" else "Register gateway") }
                Button(enabled = !realtimeBusy, onClick = { realtimeBusy = true; action("Model discovery", { client.request("/v1/models/discover", "POST", JSONObject()) }, clearBusy = { realtimeBusy = false }) }) { Text("Discover models") }
                Text("Recent WebSocket events", style = MaterialTheme.typography.titleMedium)
                if (events.isEmpty()) Text("Waiting for gateway workspace events…", style = MaterialTheme.typography.bodySmall)
                events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } ?: if (loading) CircularProgressIndicator() else Text("Refresh workspace to load gateway data.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GatewayCard(title: String, content: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(redactJson(content).take(5000), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun JSONArray.pretty(): String = runCatching { toString(2) }.getOrDefault(toString())

private fun redactJson(raw: String): String = runCatching { redactObject(JSONObject(raw)).toString(2) }.getOrElse {
    runCatching { redactArray(JSONArray(raw)).toString(2) }.getOrDefault(raw)
}

private fun redactObject(value: JSONObject): JSONObject {
    val clean = JSONObject()
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val normalized = key.lowercase()
        val item = value.opt(key)
        val redacted = normalized.contains("api_key") || normalized.contains("apikey") || normalized.contains("secret") || normalized.contains("credential") || normalized == "token" || normalized.endsWith("_token") || normalized == "authorization"
        clean.put(key, if (redacted) "[REDACTED]" else when (item) {
            is JSONObject -> redactObject(item)
            is JSONArray -> redactArray(item)
            else -> item
        })
    }
    return clean
}

private fun redactArray(value: JSONArray): JSONArray {
    val clean = JSONArray()
    for (index in 0 until value.length()) {
        val item = value.opt(index)
        clean.put(when (item) {
            is JSONObject -> redactObject(item)
            is JSONArray -> redactArray(item)
            else -> item
        })
    }
    return clean
}

private fun formatReply(raw: String): String = redactJson(raw)
