package ai.nova.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class AssistantMood { IDLE, LISTENING, THINKING, RESPONDING }

/** A lightweight, animated 2.5D particle orb drawn with Compose Canvas (no GL surface). */
@Composable
internal fun AssistantOrb(
    mood: AssistantMood,
    modifier: Modifier = Modifier,
    diameter: Dp = 54.dp,
    onClick: (() -> Unit)? = null,
) {
    val transition = rememberInfiniteTransition(label = "nova-orb")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7200), repeatMode = RepeatMode.Restart),
        label = "orb-phase",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.93f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(1700), repeatMode = RepeatMode.Reverse),
        label = "orb-pulse",
    )
    val energy by animateFloatAsState(
        targetValue = when (mood) {
            AssistantMood.IDLE -> 0.72f
            AssistantMood.LISTENING -> 1.12f
            AssistantMood.THINKING -> 1.28f
            AssistantMood.RESPONDING -> 1.02f
        },
        animationSpec = tween(420),
        label = "orb-energy",
    )
    val tint = when (mood) {
        AssistantMood.IDLE -> Color(0xFF65D9D1)
        AssistantMood.LISTENING -> Color(0xFF80B8FF)
        AssistantMood.THINKING -> Color(0xFFB795FF)
        AssistantMood.RESPONDING -> Color(0xFF62E4B1)
    }
    val semantics = Modifier.semantics { contentDescription = "NOVA assistant, ${mood.name.lowercase()}" }
    val tap = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Canvas(modifier.then(semantics).then(tap).size(diameter)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.43f
        drawCircle(
            brush = Brush.radialGradient(
                0f to tint.copy(alpha = 0.30f * energy),
                0.55f to tint.copy(alpha = 0.13f * energy),
                1f to Color.Transparent,
                center = c,
                radius = r * 1.72f * pulse,
            ),
            radius = r * 1.72f * pulse,
            center = c,
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFFF5FFFF),
                0.18f to tint.copy(alpha = 0.95f),
                0.68f to Color(0xFF273B64),
                1f to Color(0xFF101829),
                center = Offset(c.x - r * 0.24f, c.y - r * 0.30f),
                radius = r * 1.42f,
            ),
            radius = r * pulse,
            center = c,
        )
        // Three translucent orbital tracks suggest depth while remaining cheap to render.
        for (track in 0..2) {
            val tilt = (track - 1) * 0.56f
            val points = 72
            val path = androidx.compose.ui.graphics.Path()
            for (i in 0..points) {
                val angle = (i.toFloat() / points * 2f * PI).toFloat() + phase * (if (track == 1) -0.22f else 0.22f)
                val x = c.x + cos(angle) * r * 0.96f
                val y = c.y + sin(angle) * r * (0.30f + 0.09f * track) + cos(angle + tilt) * r * 0.12f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, tint.copy(alpha = 0.26f + 0.10f * energy), style = Stroke(width = size.minDimension * 0.012f))
        }
        // Deterministic particles move on a tilted spherical shell; no random allocations per frame.
        repeat(30) { index ->
            val angle = index * 2.3999632f + phase * (if (index % 2 == 0) 0.34f else -0.25f)
            val depth = sin(index * 1.71f + phase * 0.7f)
            val ring = r * (0.20f + 0.70f * ((index % 7) / 6f))
            val x = c.x + cos(angle) * ring * (0.58f + 0.40f * kotlin.math.abs(depth))
            val y = c.y + sin(angle * 1.13f) * ring * 0.64f
            drawCircle(
                color = Color.White.copy(alpha = (0.20f + 0.62f * ((depth + 1f) / 2f)) * energy.coerceAtMost(1.2f)),
                radius = size.minDimension * (0.008f + 0.012f * ((index % 3) / 2f)),
                center = Offset(x, y),
            )
        }
    }
}
