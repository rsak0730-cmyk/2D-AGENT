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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val NOVA_GATEWAY_BASE = "https://gateway-server-mea8.onrender.com"
private val gatewayHttp = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

/** Short-lived view of the install credential set; every value is persisted encrypted via SecureSettings. */
internal data class GatewaySession(
    val installationId: String,
    val workspaceId: String,
    val installationToken: String,
    val expiresAt: String,
)

internal data class GatewaySnapshot(
    val tasks: JSONArray,
    val messages: JSONArray,
    val memory: JSONArray,
    val activity: JSONArray,
    val models: JSONArray,
    val families: JSONArray,
    val permissions: JSONObject,
)

internal data class GatewayAudio(val bytes: ByteArray, val contentType: String)

/**
 * Anonymous device installation auth. No user login, account, provider key, or admin credential is used.
 * The gateway remains responsible for model routing, inference, and permission enforcement.
 */
internal class GatewayClient(private val secure: SecureSettings) {
    fun session(): GatewaySession? {
        val installationId = secure.get(KEY_INSTALLATION_ID)
        val workspaceId = secure.get(KEY_WORKSPACE_ID)
        val installationToken = secure.get(KEY_INSTALLATION_TOKEN)
        val expiresAt = secure.get(KEY_EXPIRES_AT)
        return if (installationId.isNotBlank() && workspaceId.isNotBlank() && installationToken.isNotBlank() && expiresAt.isNotBlank()) {
            GatewaySession(installationId, workspaceId, installationToken, expiresAt)
        } else null
    }

    /** Register on first launch; refresh shortly before expiry on subsequent launches. */
    @Synchronized
    fun ensureInstallation(): GatewaySession {
        val current = session()
        if (current == null) return registerInstallation()
        if (expiresSoon(current.expiresAt)) return refreshInstallation(current)
        return current
    }

    private fun deviceId(): String {
        secure.get(KEY_DEVICE_ID).takeIf(String::isNotBlank)?.let { return it }
        return UUID.randomUUID().toString().also { secure.put(KEY_DEVICE_ID, it) }
    }

    private fun registerInstallation(): GatewaySession {
        val body = JSONObject()
            .put("device_id", deviceId())
            .put("app_version", "1.0.0")
        val response = JSONObject(executeUnauthenticated("/v1/installations/register", body))
        return saveInstallation(response)
    }

    @Synchronized
    private fun refreshInstallation(old: GatewaySession): GatewaySession {
        // Another concurrent request may already have refreshed the persistent credential.
        session()?.takeIf { it.installationToken != old.installationToken }?.let { return it }
        val body = JSONObject()
            .put("installation_id", old.installationId)
            .put("installation_token", old.installationToken)
        rawRequest("/v1/installations/refresh", body.toString(), old, "POST").use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw gatewayError(response.code, text)
            return saveInstallation(JSONObject(text), old)
        }
    }

    private fun saveInstallation(json: JSONObject, old: GatewaySession? = null): GatewaySession {
        val installationId = json.optString("installation_id").takeIf(String::isNotBlank)
            ?: old?.installationId ?: throw IOException("NOVA-GATEWAY installation response omitted installation_id")
        val workspaceId = json.optString("workspace_id").takeIf(String::isNotBlank)
            ?: old?.workspaceId ?: throw IOException("NOVA-GATEWAY installation response omitted workspace_id")
        val token = json.optString("installation_token").takeIf(String::isNotBlank)
            ?: old?.installationToken ?: throw IOException("NOVA-GATEWAY installation response omitted installation_token")
        val expiresAt = json.optString("expires_at").takeIf(String::isNotBlank)
            ?: throw IOException("NOVA-GATEWAY installation response omitted expires_at")
        val session = GatewaySession(installationId, workspaceId, token, expiresAt)
        secure.put(KEY_INSTALLATION_ID, installationId)
        secure.put(KEY_WORKSPACE_ID, workspaceId)
        secure.put(KEY_INSTALLATION_TOKEN, token)
        secure.put(KEY_EXPIRES_AT, expiresAt)
        return session
    }

    private fun expiresSoon(expiresAt: String): Boolean = isInstallationExpiring(expiresAt, System.currentTimeMillis(), REFRESH_SKEW_MS)

    private fun executeUnauthenticated(path: String, body: JSONObject): String =
        rawRequest(path, body.toString(), null).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw gatewayError(response.code, text)
            text.ifBlank { "{}" }
        }

    fun request(path: String, method: String = "GET", body: JSONObject? = null): String {
        val current = ensureInstallation()
        return try {
            requestWithInstallation(path, method, body, current, retry401 = true)
        } catch (unauthorized: GatewayUnauthorized) {
            throw IOException("NOVA-GATEWAY rejected this installation. The installation refresh failed; retry after reconnecting.", unauthorized)
        }
    }

    private fun requestWithInstallation(
        path: String,
        method: String,
        body: JSONObject?,
        credentials: GatewaySession,
        retry401: Boolean,
    ): String {
        val payload = body?.toString()
        rawRequest(path, payload, credentials, method).use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 && retry401) {
                val refreshed = try { refreshInstallation(credentials) } catch (error: Exception) { throw GatewayUnauthorized(error) }
                return requestWithInstallation(path, method, body, refreshed, retry401 = false)
            }
            if (!response.isSuccessful) {
                if (response.code == 401) throw GatewayUnauthorized(IOException("Installation token was rejected"))
                throw gatewayError(response.code, text)
            }
            return text.ifBlank { "{}" }
        }
    }

    private fun rawRequest(
        path: String,
        payload: String?,
        credentials: GatewaySession?,
        method: String = "POST",
    ): okhttp3.Response {
        if (!path.startsWith("/v1/") || path.contains("..") || path.startsWith("//")) throw IOException("Invalid NOVA-GATEWAY API path")
        val accept = if (path == "/v1/voice/tts") "audio/*, application/json" else "application/json"
        val builder = Request.Builder().url(NOVA_GATEWAY_BASE + path).header("Accept", accept)
        credentials?.let {
            builder.header("X-Workspace-Id", it.workspaceId)
            builder.header("X-Installation-Token", it.installationToken)
        }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((payload ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()))
            else -> throw IOException("Unsupported NOVA-GATEWAY method")
        }
        return gatewayHttp.newCall(builder.build()).execute()
    }

    fun requestAudio(path: String, body: JSONObject): GatewayAudio? {
        val credentials = ensureInstallation()
        repeat(2) { attempt ->
            rawRequest(path, body.toString(), credentials, "POST").use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                val bytes = response.body?.bytes() ?: byteArrayOf()
                if (response.code == 401 && attempt == 0) {
                    val renewed = try { refreshInstallation(credentials) } catch (_: Exception) { return null }
                    return requestAudio(path, body, renewed, retry = false)
                }
                if (!response.isSuccessful || bytes.isEmpty() || contentType.contains("json", true)) return null
                return GatewayAudio(bytes, contentType)
            }
        }
        return null
    }

    private fun requestAudio(path: String, body: JSONObject, credentials: GatewaySession, retry: Boolean): GatewayAudio? {
        rawRequest(path, body.toString(), credentials, "POST").use { response ->
            val contentType = response.header("Content-Type").orEmpty()
            val bytes = response.body?.bytes() ?: byteArrayOf()
            if (response.code == 401 && retry) {
                val renewed = try { refreshInstallation(credentials) } catch (_: Exception) { return null }
                return requestAudio(path, body, renewed, retry = false)
            }
            if (!response.isSuccessful || bytes.isEmpty() || contentType.contains("json", true)) return null
            return GatewayAudio(bytes, contentType)
        }
    }

    fun loadTasks(): JSONArray = readArray(request("/v1/tasks"), "tasks", "items", "data")

    fun loadSnapshot(): GatewaySnapshot {
        val permissions = JSONObject()
        AGENT_KEYS.forEach { key ->
            val value = runCatching { JSONObject(request("/v1/agents/$key/permissions")) }
                .getOrElse { JSONObject().put("unavailable", true) }
            permissions.put(key, value)
        }
        return GatewaySnapshot(
            tasks = loadTasks(),
            messages = readArray(request("/v1/messages"), "messages", "items", "data"),
            memory = readArray(request("/v1/memory"), "memory", "memories", "items", "data"),
            activity = readArray(request("/v1/activity"), "activity", "items", "data"),
            models = readArray(request("/v1/models"), "models", "items", "data"),
            families = readArray(request("/v1/models/families"), "families", "items", "data"),
            permissions = permissions,
        )
    }

    /** Select only an assignment actually returned by the agent permission response. */
    fun assignedModel(agentKey: String): String {
        if (agentKey !in AGENT_KEYS) throw IOException("Unknown NOVA agent key")
        val permissions = JSONObject(request("/v1/agents/$agentKey/permissions"))
        val candidate = findAssignedModel(permissions)
            ?: throw IOException("NOVA-GATEWAY did not return a model assignment for $agentKey. No model was selected or routed by the app.")
        val models = readArray(request("/v1/models"), "models", "items", "data")
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            val values = listOf("model_identity_id", "id", "canonical_name", "name").map { model.optString(it) }
            if (candidate in values) return model.optString("model_identity_id").takeIf(String::isNotBlank)
                ?: model.optString("canonical_name").takeIf(String::isNotBlank) ?: candidate
        }
        // The assignment itself is authoritative; the models catalogue may be permission-filtered.
        return candidate
    }

    fun chat(agentKey: String, prompt: String, model: String = assignedModel(agentKey)): String {
        if (agentKey !in AGENT_KEYS || prompt.isBlank()) throw IOException("Invalid NOVA chat request")
        val payload = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", 512)
            .put("agent_key", agentKey)
        return extractAssistantText(request("/v1/chat/completions", "POST", payload))
    }

    fun speak(text: String, voiceId: String? = null): GatewayAudio? {
        val body = JSONObject().put("text", text.take(4000))
        if (!voiceId.isNullOrBlank()) body.put("voice_id", voiceId)
        return requestAudio("/v1/voice/tts", body)
    }

    fun clearInstallation() {
        listOf(KEY_INSTALLATION_ID, KEY_WORKSPACE_ID, KEY_INSTALLATION_TOKEN, KEY_EXPIRES_AT).forEach { secure.put(it, "") }
    }

    private fun gatewayError(code: Int, @Suppress("UNUSED_PARAMETER") body: String): IOException {
        // Keep response bodies out of UI errors; a misconfigured server must not echo credentials to the screen.
        return IOException("NOVA-GATEWAY HTTP $code")
    }

    private fun findAssignedModel(value: JSONObject): String? {
        val directKeys = listOf("assigned_model_identity_id", "assigned_model_id", "model_identity_id", "canonical_name", "assigned_model", "model")
        directKeys.forEach { key ->
            val item = value.opt(key)
            if (item is String && item.isNotBlank()) return item
            if (item is JSONObject) {
                listOf("model_identity_id", "canonical_name", "id", "name").forEach { nested ->
                    item.optString(nested).takeIf(String::isNotBlank)?.let { return it }
                }
            }
        }
        // Do not choose an arbitrary model out of an allowed-models list; only follow an explicit assignment object.
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.contains("assignment", ignoreCase = true) || key.equals("agent", true) || key.equals("permissions", true) || key.equals("data", true)) {
                (value.opt(key) as? JSONObject)?.let { findAssignedModel(it)?.let { model -> return model } }
            }
        }
        return null
    }

    companion object {
        private const val KEY_DEVICE_ID = "nova_device_id"
        private const val KEY_INSTALLATION_ID = "nova_installation_id"
        private const val KEY_WORKSPACE_ID = "nova_installation_workspace_id"
        private const val KEY_INSTALLATION_TOKEN = "nova_installation_token"
        private const val KEY_EXPIRES_AT = "nova_installation_expires_at"
        private const val REFRESH_SKEW_MS = 5 * 60 * 1000L
    }
}

internal fun isInstallationExpiring(expiresAt: String, nowMillis: Long, skewMillis: Long = 5 * 60 * 1000L): Boolean {
    val expiry = runCatching { Instant.parse(expiresAt).toEpochMilli() }
        .getOrElse { expiresAt.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000L else it } ?: 0L }
    return expiry <= nowMillis + skewMillis
}

private class GatewayUnauthorized(cause: Throwable? = null) : IOException("NOVA-GATEWAY installation unauthorized", cause)

private val AGENT_KEYS = listOf("head") + (1..10).map { "worker_$it" }

internal fun gatewayAgentKey(identity: String): String {
    val index = listOf("android", "research", "ui-ux", "web", "database", "qa", "security", "backend", "documentation", "game").indexOf(identity)
    if (index < 0) throw IOException("No NOVA-GATEWAY agent key is mapped for '$identity'")
    return "worker_${index + 1}"
}

internal fun gatewayHeadOrchestrate(client: GatewayClient, prompt: String): String = client.chat("head", prompt)

internal fun gatewayWorkerChat(client: GatewayClient, agentKey: String, message: String): String = client.chat(agentKey, message)

private fun readArray(raw: String, vararg keys: String): JSONArray {
    runCatching { JSONArray(raw) }.getOrNull()?.let { return it }
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return JSONArray()
    keys.forEach { key ->
        root.optJSONArray(key)?.let { return it }
        root.optJSONObject(key)?.let { nested -> keys.forEach { child -> nested.optJSONArray(child)?.let { return it } } }
    }
    return JSONArray()
}

private fun extractAssistantText(raw: String): String {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
    val choices = json.optJSONArray("choices")
    val choice = choices?.optJSONObject(0)
    val content = choice?.optJSONObject("message")?.opt("content")
        ?: choice?.opt("text")
        ?: json.opt("response")
        ?: json.opt("message")
        ?: json.opt("content")
        ?: json.opt("output")
    return when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text").orEmpty() }
        is JSONObject -> content.toString()
        else -> json.toString(2)
    }
}

@Composable
internal fun GatewayWorkspaceScreen(client: GatewayClient, modifier: Modifier = Modifier, onSessionChanged: (GatewaySession?) -> Unit) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(client.session()) }
    var snapshot by remember { mutableStateOf<GatewaySnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("Connecting securely to NOVA-GATEWAY…") }
    var taskTitle by remember { mutableStateOf("") }
    var taskDescription by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var memoryKey by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf("") }

    val refresh: suspend () -> Unit = {
        busy = true
        try {
            session = withContext(Dispatchers.IO) { client.ensureInstallation() }
            snapshot = withContext(Dispatchers.IO) { client.loadSnapshot() }
            notice = "Connected · workspace ${session?.workspaceId} · automatic device installation"
        } catch (error: Exception) {
            notice = error.message ?: "NOVA-GATEWAY could not be reached. Retry when online."
        } finally { busy = false }
    }
    LaunchedEffect(Unit) { refresh(); onSessionChanged(session) }
    LaunchedEffect(session?.installationId, session?.workspaceId) { onSessionChanged(session) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("NOVA · CENTRAL SERVER", style = MaterialTheme.typography.titleLarge)
                Text("INSTALL → OPEN → USE NOVA · no account or provider keys", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text(if (busy) "Syncing…" else "Refresh") }
        }
        Text(notice, style = MaterialTheme.typography.bodyMedium)
        if (busy && snapshot == null) CircularProgressIndicator()
        snapshot?.let { data ->
            GatewayInfoCard("Models", data.models.toPretty())
            GatewayInfoCard("Model families", data.families.toPretty())
            GatewayInfoCard("Agent permissions · authoritative gateway response", data.permissions.toPretty())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(taskTitle, { taskTitle = it }, Modifier.weight(1f), label = { Text("Task title") }, singleLine = true)
                Button(enabled = taskTitle.isNotBlank() && !busy, onClick = {
                    val title = taskTitle.trim(); val description = taskDescription.trim(); busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.request("/v1/tasks", "POST", JSONObject().put("title", title).put("description", description)) }
                            taskTitle = ""; taskDescription = ""; notice = "NOVA-GATEWAY confirmed task creation."; refresh()
                        } catch (error: Exception) { notice = error.message ?: "Task was not confirmed"; busy = false }
                    }
                }) { Text("Add task") }
            }
            OutlinedTextField(taskDescription, { taskDescription = it }, Modifier.fillMaxWidth(), label = { Text("Task details") }, minLines = 2)
            GatewayInfoCard("Tasks · backend records only", data.tasks.toPretty())
            OutlinedTextField(messageText, { messageText = it }, Modifier.fillMaxWidth(), label = { Text("Send workspace message") }, minLines = 2)
            Button(enabled = messageText.isNotBlank() && !busy, onClick = {
                val text = messageText.trim(); busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.request("/v1/messages", "POST", JSONObject().put("recipient_type", "workspace").put("message_type", "text").put("content", text)) }
                        messageText = ""; notice = "NOVA-GATEWAY confirmed message delivery."; refresh()
                    } catch (error: Exception) { notice = error.message ?: "Message was not confirmed"; busy = false }
                }
            }) { Text("Send message") }
            GatewayInfoCard("Messages", data.messages.toPretty())
            OutlinedTextField(memoryKey, { memoryKey = it }, Modifier.fillMaxWidth(), label = { Text("Memory key") }, singleLine = true)
            OutlinedTextField(memoryText, { memoryText = it }, Modifier.fillMaxWidth(), label = { Text("Save workspace memory") }, minLines = 2)
            Button(enabled = memoryKey.isNotBlank() && memoryText.isNotBlank() && !busy, onClick = {
                val key = memoryKey.trim(); val content = memoryText.trim(); busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.request("/v1/memory", "POST", JSONObject().put("layer", "workspace").put("owner_type", "workspace").put("key", key).put("content", content).put("tags", JSONArray()).put("is_shared", true)) }
                        memoryText = ""; notice = "NOVA-GATEWAY confirmed memory save."; refresh()
                    } catch (error: Exception) { notice = error.message ?: "Memory save was not confirmed"; busy = false }
                }
            }) { Text("Save memory") }
            GatewayInfoCard("Memory", data.memory.toPretty())
            GatewayInfoCard("Activity", data.activity.toPretty())
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GatewayInfoCard(title: String, content: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(redactGatewayJson(content).take(6000), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun JSONArray.toPretty(): String = runCatching { redactArray(this).toString(2) }.getOrDefault(toString())
private fun JSONObject.toPretty(): String = runCatching { redactObject(this).toString(2) }.getOrDefault(toString())

internal fun redactGatewayJson(raw: String): String {
    runCatching { JSONObject(raw) }.getOrNull()?.let { return redactObject(it).toString(2) }
    runCatching { JSONArray(raw) }.getOrNull()?.let { return redactArray(it).toString(2) }
    return raw
}

private fun redactObject(value: JSONObject): JSONObject {
    val result = JSONObject()
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val normalized = key.lowercase()
        val secret = normalized.contains("api_key") || normalized.contains("apikey") || normalized.contains("secret") || normalized.contains("credential") || normalized.contains("password") || normalized == "token" || normalized.endsWith("_token") || normalized == "authorization"
        val item = value.opt(key)
        result.put(key, if (secret) "[REDACTED]" else when (item) {
            is JSONObject -> redactObject(item)
            is JSONArray -> redactArray(item)
            else -> item
        })
    }
    return result
}

private fun redactArray(value: JSONArray): JSONArray {
    val result = JSONArray()
    for (index in 0 until value.length()) {
        when (val item = value.opt(index)) {
            is JSONObject -> result.put(redactObject(item))
            is JSONArray -> result.put(redactArray(item))
            else -> result.put(item)
        }
    }
    return result
}
