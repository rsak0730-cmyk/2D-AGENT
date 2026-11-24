package ai.nova.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Project work is shown from the gateway's existing task collection; no project API is fabricated. */
@Composable
internal fun GatewayProjectsScreen(client: GatewayClient, onOpenGateway: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<GatewaySnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("Sign in to NOVA-GATEWAY to load real task records.") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    val refresh: suspend () -> Unit = {
        if (client.session() == null) {
            snapshot = null
            notice = "Sign in to NOVA-GATEWAY to load workspace projects and tasks."
        } else {
            busy = true
            try {
                snapshot = withContext(Dispatchers.IO) { client.loadSnapshot() }
                notice = "Live NOVA-GATEWAY task records · ${snapshot?.tasks?.length() ?: 0} items"
            } catch (error: Exception) {
                notice = error.message ?: "Could not load gateway task data"
            } finally { busy = false }
        }
    }
    LaunchedEffect(client.session()?.workspaceId) { refresh() }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Projects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("NOVA-GATEWAY workspace execution", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text(if (busy) "Syncing…" else "Refresh") }
        }
        Surface(color = Color(0xFF211B36), shape = RoundedCornerShape(12.dp)) {
            Text(
                "The connected contract exposes tasks, not a separate project entity API. This view lists real gateway tasks; it does not fabricate projects, progress or execution state.",
                Modifier.fillMaxWidth().padding(11.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFD9D0FF),
            )
        }
        if (client.session() == null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF111A2B))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Workspace not connected", style = MaterialTheme.typography.titleMedium)
                    Text(notice, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onOpenGateway) { Text("Sign in to NOVA-GATEWAY") }
                }
            }
        } else {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("New task") }, singleLine = true, enabled = !busy)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Task details") }, minLines = 2, maxLines = 4, enabled = !busy)
            Button(enabled = title.isNotBlank() && !busy, onClick = {
                val taskTitle = title.trim()
                val taskDescription = description.trim()
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            client.request("/v1/tasks", "POST", JSONObject().put("title", taskTitle).put("description", taskDescription).put("priority", 3))
                        }
                        title = ""; description = ""
                        notice = "Task created through NOVA-GATEWAY. Execution state will update only when reported by the gateway."
                        refresh()
                    } catch (error: Exception) {
                        notice = error.message ?: "Gateway did not confirm task creation"
                        busy = false
                    }
                }
            }) { Text("Create gateway task") }
            Text(notice, style = MaterialTheme.typography.labelSmall)
            if (busy && snapshot == null) CircularProgressIndicator()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tasks = snapshot?.tasks ?: JSONArray()
                items((0 until tasks.length()).map { tasks.optJSONObject(it) ?: JSONObject().put("record", tasks.opt(it)) }, key = { it.optString("id", "task-$it") }) { task ->
                    val taskTitle = task.optString("title").takeIf(String::isNotBlank) ?: task.optString("name").takeIf(String::isNotBlank) ?: "Gateway task"
                    val state = task.optString("state").takeIf(String::isNotBlank) ?: task.optString("status").takeIf(String::isNotBlank) ?: "State not provided"
                    val progress = task.optInt("progress_percent", -1)
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF111A2B))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(taskTitle, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(state, style = MaterialTheme.typography.labelMedium, color = when (state.uppercase()) {
                                    "COMPLETED", "DONE", "SUCCEEDED" -> Color(0xFF8CE8C2)
                                    "FAILED", "ERROR" -> Color(0xFFFF8F9E)
                                    else -> Color(0xFFFFD48A)
                                })
                            }
                            val assigned = task.optString("assigned_worker_id").takeIf(String::isNotBlank)
                            if (assigned != null) Text("Assigned by gateway · $assigned", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB5C9E2))
                            if (progress >= 0) {
                                Text("${progress.coerceIn(0, 100)}% · ${task.optString("progress_stage", "gateway-reported progress")}", style = MaterialTheme.typography.labelSmall)
                                LinearProgressIndicator(progress = progress.coerceIn(0, 100) / 100f, modifier = Modifier.fillMaxWidth())
                            }
                            val result = task.optString("result").takeIf(String::isNotBlank)
                            val error = task.optString("error").takeIf(String::isNotBlank)
                            if (result != null) Text(result, style = MaterialTheme.typography.bodySmall)
                            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Text("Backend record · ${task.toString().take(1100)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF879DBA))
                        }
                    }
                }
                if (snapshot != null && snapshot!!.tasks.length() == 0) item { Text("No tasks returned by NOVA-GATEWAY.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
