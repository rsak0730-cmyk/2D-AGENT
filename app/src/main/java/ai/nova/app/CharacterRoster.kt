package ai.nova.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class CharacterCanon(
    val identity: String,
    val name: String,
    val role: String,
    val personality: String,
    val specialty: String,
)

private val novaCharacterCanon = listOf(
    CharacterCanon("manager", "Nova", "Head · Leader", "Warm, composed and candid; brings the team together without overstating certainty.", "Command coordination, planning and synthesis."),
    CharacterCanon("android", "Aiden", "Developer", "Practical and patient; explains implementation trade-offs clearly.", "Software development and engineering delivery."),
    CharacterCanon("research", "Mira", "Researcher", "Curious and evidence-minded; separates verified findings from open questions.", "Research, source review and synthesis."),
    CharacterCanon("ui-ux", "Luna", "Designer", "Observant and empathetic; makes clarity and accessibility part of the design.", "Visual systems and user experience."),
    CharacterCanon("web", "Zen", "Web Developer", "Calm and exploratory; prefers small experiments and useful feedback.", "Web engineering and frontend systems."),
    CharacterCanon("database", "Riya", "Analyst", "Measured and analytical; makes assumptions and data limitations explicit.", "Data analysis, schemas and information quality."),
    CharacterCanon("qa", "Kian", "Tester", "Methodical and inquisitive; turns edge cases into reproducible checks.", "Testing strategy and quality assurance."),
    CharacterCanon("security", "Sia", "Security Expert", "Steady and threat-aware; focuses on practical, proportionate mitigations.", "Security review and trust boundaries."),
    CharacterCanon("backend", "Aron", "Data Scientist", "Structured and curious; favors explainable evidence over confident guesses.", "Data science and backend analysis."),
    CharacterCanon("documentation", "Evelyn", "Documentalist", "Clear and thoughtful; makes complex procedures approachable.", "Documentation and information architecture."),
    CharacterCanon("game", "Rex", "Creative Artist", "Imaginative and playful; turns concepts into coherent visual ideas.", "Creative direction and interactive concepts."),
)

/** Canonical fictional identity sheet; operational status and model data are never fabricated here. */
@Composable
internal fun CharacterRosterScreen(
    agents: List<WorkforceAgent>,
    profiles: List<CharacterProfile>,
    tasks: List<WorkforceTask>,
    onOpenGateway: () -> Unit,
    onAgentSelected: (WorkforceAgent) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Agents", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("11 character identities · operational fields are backend-only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onOpenGateway) { Icon(Icons.Default.Hub, null); Text("Gateway") }
        }
        Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFF211B36)) {
            Text(
                "Character identity catalog · models, tasks, memory, voice readiness and availability are shown only when a connected backend returns them. No model assignment is inferred from a name or role.",
                Modifier.fillMaxWidth().padding(11.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFD9D0FF),
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(novaCharacterCanon, key = { it.identity }) { canon ->
                val agent = agents.firstOrNull { it.identity == canon.identity }
                val profile = profiles.firstOrNull { it.key == canon.identity || (canon.identity == "manager" && it.key == "head") }
                val task = agent?.let { assignedTaskFor(it, tasks) }
                val liveStatus = when {
                    agent == null -> "Awaiting backend"
                    task?.state == "RUNNING" -> "Working · ${task.title}"
                    task?.state in setOf("PLANNING", "ASSIGNED") -> "Planning · ${task.title}"
                    else -> agent.status
                }
                val model = agent?.let { verifiedModelIdentity(it, emptyList()) }
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111A2B)),
                ) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(canon.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(canon.role, style = MaterialTheme.typography.labelMedium, color = Color(0xFFB5C9E2))
                            }
                            Text(liveStatus, style = MaterialTheme.typography.labelSmall, color = if (agent == null) Color(0xFFFFD48A) else Color(0xFF8CE8C2))
                        }
                        Text(profile?.personality?.takeIf(String::isNotBlank) ?: canon.personality, style = MaterialTheme.typography.bodySmall)
                        Text("Specialty · ${profile?.specialization?.takeIf(String::isNotBlank) ?: canon.specialty}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB8D6E8))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Model · ${model ?: "unverified / unavailable"}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFD48A))
                            if (agent != null) TextButton(onClick = { onAgentSelected(agent) }) { Text("Open profile"); Icon(Icons.Default.ArrowForward, null) }
                        }
                        val appearance = profile?.appearance?.takeIf(String::isNotBlank)
                        if (!appearance.isNullOrBlank()) Text("Appearance · $appearance", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8FA7C5))
                        Text("Voice · ${if (profile?.voiceReady == true) "backend reports configured" else "not verified"}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8FA7C5))
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF26364E))
        Text("Art reference images were not present in this checkout; identity descriptions are preserved as a roster catalog, not claimed as exact sprite reproductions.", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8FA7C5))
    }
}
