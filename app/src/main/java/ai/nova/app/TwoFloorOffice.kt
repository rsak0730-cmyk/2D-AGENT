package ai.nova.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private enum class HeadquartersFloor(val label: String, val subtitle: String) {
    UPPER("UPPER FLOOR", "COMMAND · HOLOGRAPHIC GLOBE"),
    LOWER("LOWER FLOOR", "OPERATIONS · TEN WORKSTATIONS"),
}

private val workerIdentityOrder = listOf("android", "research", "ui-ux", "web", "database", "qa", "security", "backend", "documentation", "game")

/** Two-floor pixel-art cutaway. Desk geometry is local environment art; character activity is backend-derived. */
@Composable
internal fun TwoFloorOfficeScene(
    agents: List<WorkforceAgent>,
    characterProfiles: List<CharacterProfile>,
    providerConnections: List<ProviderConnectionInfo>,
    tasks: List<WorkforceTask>,
    events: List<OfficeEvent>,
    messages: List<WorkforceMessage>,
    meetings: List<MeetingSummary>,
    reduceMotion: Boolean,
    onReduceMotionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onAgentSelected: (WorkforceAgent) -> Unit,
) {
    var floor by remember { mutableStateOf(HeadquartersFloor.UPPER) }
    var frame by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val camera = rememberUpdatedState(zoom to pan)
    val workers = remember(agents) { workerIdentityOrder.mapNotNull { identity -> agents.firstOrNull { it.identity == identity } } }
    val head = agents.firstOrNull { it.identity == "manager" }
    val live = agents.isNotEmpty()

    LaunchedEffect(reduceMotion, floor) {
        if (reduceMotion) return@LaunchedEffect
        while (isActive) {
            delay(600)
            frame = (frame + 1) % 24
        }
    }

    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF070B18))) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NOVA  /  HEADQUARTERS", color = Color(0xFF9FEAFF), style = MaterialTheme.typography.labelMedium)
                    Text(floor.label, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(floor.subtitle, color = Color(0xFFB7C8E3), style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (live) "LIVE ROSTER · ${agents.size}" else "EMPTY WORLD · PREVIEW", color = if (live) Color(0xFF8CF0C2) else Color(0xFFFFD48A), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Recenter", color = Color(0xFF9FEAFF)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeadquartersFloor.entries.forEach { option ->
                    FilterChip(
                        selected = floor == option,
                        onClick = { floor = option; pan = Offset.Zero; zoom = 1f },
                        label = { Text(if (option == HeadquartersFloor.UPPER) "Upper · Command" else "Lower · Operations") },
                    )
                }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                val sceneWidth = maxWidth
                val sceneHeight = maxHeight
                val density = LocalDensity.current
                val widthPx = with(density) { sceneWidth.toPx() }
                val heightPx = with(density) { sceneHeight.toPx() }
                val transform = rememberUpdatedState(zoom to pan)
                val scaled = Modifier.fillMaxSize().graphicsLayer {
                    val current = transform.value
                    scaleX = current.first
                    scaleY = current.first
                    translationX = current.second.x
                    translationY = current.second.y
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                Box(scaled) {
                    Canvas(Modifier.fillMaxSize()) { drawNOVAOfficeFloor(floor, frame, live) }
                    val positions = if (floor == HeadquartersFloor.UPPER) upperDeskPositions else lowerDeskPositions
                    workers.forEachIndexed { index, agent ->
                        if (index >= positions.size) return@forEachIndexed
                        val pos = positions[index]
                        val profile = characterProfiles.firstOrNull { it.key == agent.identity }
                        val display = if (profile == null) agent else agent.copy(name = profile.name)
                        val task = assignedTaskFor(agent, tasks)
                        // Floor coordinates are an art/layout choice; do not infer a move from generic activity events.
                        val movement = "idle"
                        val reportedPose = workerPose(
                            agent = agent,
                            task = task,
                            inMeeting = meetings.any { meeting -> meeting.status.equals("running", true) && meeting.invitations.any { it.agentId == agent.id && it.status != "declined" } },
                            communicating = freshCommunicators(events, messages).contains(agent.id),
                            movement = movement,
                        )
                        val pose = if (reportedPose == WorkerPose.IDLE) WorkerPose.SITTING else reportedPose
                        val model = verifiedModelIdentity(agent, providerConnections)
                        val status = modelConnectionStatus(agent, task, providerConnections)
                        PixelWorkerSprite(
                            agent = display,
                            pose = pose,
                            phase = frame,
                            selected = false,
                            modelIdentity = model,
                            modelStatus = status,
                            appearanceJson = profile?.appearanceJson,
                            reduceMotion = reduceMotion,
                            modifier = Modifier.offset(x = sceneWidth * pos.first - sceneWidth * 0.043f, y = sceneHeight * pos.second - sceneHeight * 0.115f)
                                .size(sceneWidth * 0.086f, sceneHeight * 0.23f)
                                .semantics { contentDescription = "${display.name}, ${display.title}, ${pose.name.lowercase()}, ${status.lowercase()}, tap for profile" }
                                .pointerInput(agent.id) { detectTapGestures(onTap = { onAgentSelected(display) }) },
                        )
                    }
                    if (floor == HeadquartersFloor.UPPER && head != null) {
                        val profile = characterProfiles.firstOrNull { it.key == "head" }
                        val display = if (profile == null) head else head.copy(name = profile.name)
                        val task = assignedTaskFor(head, tasks)
                        PixelWorkerSprite(
                            agent = display,
                            pose = workerPose(
                                agent = head,
                                task = task,
                                inMeeting = meetings.any { it.status.equals("running", true) },
                                communicating = freshCommunicators(events, messages).contains(head.id),
                                movement = "idle",
                            ).let { if (it == WorkerPose.IDLE) WorkerPose.SITTING else it },
                            phase = frame,
                            selected = false,
                            modelIdentity = verifiedModelIdentity(head, providerConnections),
                            modelStatus = modelConnectionStatus(head, task, providerConnections),
                            appearanceJson = profile?.appearanceJson,
                            reduceMotion = reduceMotion,
                            modifier = Modifier.offset(x = sceneWidth * 0.455f, y = sceneHeight * 0.485f)
                                .size(sceneWidth * 0.09f, sceneHeight * 0.24f)
                                .semantics { contentDescription = "${display.name}, Head, command position" }
                                .pointerInput(head.id) { detectTapGestures(onTap = { onAgentSelected(display) }) },
                        )
                    }
                }
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            val current = camera.value
                            zoom = (current.first * zoomChange).coerceIn(0.85f, 2.8f)
                            pan = (current.second + panChange).let { Offset(it.x.coerceIn(-widthPx * 0.5f, widthPx * 0.5f), it.y.coerceIn(-heightPx * 0.42f, heightPx * 0.42f)) }
                        }
                    },
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${if (floor == HeadquartersFloor.UPPER) "10 WORKER DESKS + HEAD COMMAND" else "10 WORKER DESKS"} · ${if (live) "status from connected records" else "no sample staff or activity"}", Modifier.weight(1f), color = Color(0xFFC1D4E8), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { onReduceMotionChange(!reduceMotion) }) { Text(if (reduceMotion) "Motion off" else "Motion on", color = Color(0xFF9FEAFF)) }
            }
            Text("Layout preview only: the current backend has no floor-location or inter-floor movement field. Task and model labels are shown only when returned by connected records.", color = Color(0xFF8FA7C5), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val upperDeskPositions = listOf(
    0.15f to 0.36f, 0.15f to 0.49f, 0.15f to 0.62f, 0.15f to 0.75f, 0.15f to 0.87f,
    0.85f to 0.36f, 0.85f to 0.49f, 0.85f to 0.62f, 0.85f to 0.75f, 0.85f to 0.87f,
)
private val lowerDeskPositions = listOf(
    0.27f to 0.36f, 0.27f to 0.48f, 0.27f to 0.60f, 0.27f to 0.72f, 0.27f to 0.84f,
    0.73f to 0.36f, 0.73f to 0.48f, 0.73f to 0.60f, 0.73f to 0.72f, 0.73f to 0.84f,
)

private fun DrawScope.drawNOVAOfficeFloor(floor: HeadquartersFloor, frame: Int, live: Boolean) {
    val w = size.width
    val h = size.height
    fun rect(x: Float, y: Float, width: Float, height: Float, color: Color) =
        drawRect(color, Offset(w * x, h * y), Size(w * width, h * height))
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, thickness: Float = w * 0.003f) =
        drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), thickness)
    fun text(value: String, x: Float, y: Float, size: Float, color: Color) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb(); textSize = h * size; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawContext.canvas.nativeCanvas.drawText(value, w * x, h * y, paint)
    }

    drawRect(Brush.verticalGradient(listOf(Color(0xFF070D22), Color(0xFF192949), Color(0xFF0A1120)), endY = h), size = size)
    // Night skyline beyond the panoramic glass wall.
    for (i in 0 until 19) {
        val x = i / 19f
        val bw = 0.025f + (i % 4) * 0.006f
        val bh = 0.10f + ((i * 17) % 19) / 100f
        rect(x, 0.27f - bh, bw, bh, if (i % 3 == 0) Color(0xFF111B38) else Color(0xFF0C1730))
        for (r in 0 until 4) for (c in 0 until 2) {
            if ((i + r * 2 + c) % 3 != 0) rect(x + 0.004f + c * 0.010f, 0.27f - bh + 0.02f + r * 0.026f, 0.003f, 0.007f, if ((i + r + frame / 8) % 5 == 0) Color(0xFFFFD58F) else Color(0xFF64D8F2))
        }
    }
    rect(0.045f, 0.075f, 0.91f, 0.235f, Color(0xFF213954))
    rect(0.052f, 0.082f, 0.896f, 0.219f, Color(0xB80B1B34))
    for (pane in 0..7) {
        val x = 0.06f + pane * 0.125f
        rect(x, 0.09f, 0.003f, 0.205f, Color(0xFF79CBEA).copy(alpha = 0.54f))
        rect(x + 0.006f, 0.10f, 0.102f, 0.175f, Color(0xFF5AB5DE).copy(alpha = 0.055f))
        line(x + 0.01f, 0.12f, x + 0.08f, 0.22f, Color(0xFF9FEAFF).copy(alpha = 0.12f), w * 0.0015f)
    }
    rect(0.053f, 0.304f, 0.894f, 0.008f, Color(0xFF70DDF4))
    // Two depth planes form a crisp, isometric-inspired cutaway.
    val floorTop = if (floor == HeadquartersFloor.UPPER) 0.32f else 0.34f
    val floorBottom = 0.91f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.07f, h * floorTop); lineTo(w * 0.89f, h * floorTop)
        lineTo(w * 0.97f, h * (floorBottom - 0.07f)); lineTo(w * 0.90f, h * floorBottom)
        lineTo(w * 0.10f, h * floorBottom); lineTo(w * 0.035f, h * (floorBottom - 0.07f)); close()
    }
    drawPath(path, Brush.verticalGradient(listOf(Color(0xFF283752), Color(0xFF111B2C), Color(0xFF070D17)), h * floorTop, h * floorBottom))
    drawPath(path, Color(0xFF61D5EF).copy(alpha = 0.55f), style = Stroke(w * 0.008f))
    // Reflective tile grid with narrow cyan inlays.
    for (row in 0..6) {
        val y = floorTop + (floorBottom - floorTop) * row / 6f
        line(0.06f + row * 0.006f, y, 0.92f - row * 0.004f, y, Color(0xFF9BC9E7).copy(alpha = if (row % 2 == 0) 0.15f else 0.07f), w * 0.002f)
    }
    for (column in 0..10) {
        val x = 0.08f + column * 0.084f
        line(x, floorTop + 0.01f, x + (x - 0.5f) * 0.11f, floorBottom - 0.015f, Color(0xFF8CBFE2).copy(alpha = 0.09f), w * 0.0015f)
    }
    // Rear wall, wall displays, glass guard rails and wall lights.
    rect(0.08f, 0.315f, 0.82f, 0.018f, Color(0xFF354B69))
    rect(0.42f, 0.325f, 0.16f, 0.06f, Color(0xFF121D37))
    rect(0.428f, 0.334f, 0.144f, 0.039f, Color(0xFF2B4270))
    text(if (floor == HeadquartersFloor.UPPER) "NOVA COMMAND  ·  LIVE ONLY" else "OPERATIONS  ·  WORK FLOOR", 0.50f, 0.361f, 0.021f, Color(0xFFBDEEFF))
    for (side in listOf(0.10f, 0.84f)) {
        rect(side, 0.40f, 0.06f, 0.12f, Color(0xFF15213A))
        rect(side + 0.008f, 0.41f, 0.044f, 0.014f, Color(0xFF61D8ED))
        rect(side + 0.008f, 0.438f, 0.034f, 0.008f, Color(0xFF9385FA))
        rect(side + 0.008f, 0.456f, 0.040f, 0.008f, Color(0xFF73E3D2))
    }
    // Workstations: exactly ten distinct desks on either selectable floor.
    val deskPositions = if (floor == HeadquartersFloor.UPPER) upperDeskPositions else lowerDeskPositions
    deskPositions.forEachIndexed { index, (cx, cy) -> drawPixelDesk(cx, cy, index, w, h) }
    if (floor == HeadquartersFloor.UPPER) drawCentralGlobe(w, h, frame)
    else {
        drawSofa(0.47f, 0.69f, w, h)
        drawKitchenette(0.12f, 0.72f, w, h)
        drawBookshelf(0.82f, 0.70f, w, h)
        drawScenePlant(0.10f, 0.60f, w, h)
        drawScenePlant(0.90f, 0.58f, w, h)
    }
    drawStaircase(w, h, floor)
    // Railing, glass highlights and warm task-light pools.
    for (x in 0..8) {
        val bx = 0.16f + x * 0.085f
        line(bx, 0.90f, bx + 0.018f, 0.82f, Color(0xFF74D7F1).copy(alpha = 0.65f), w * 0.003f)
    }
    line(0.13f, 0.90f, 0.88f, 0.90f, Color(0xFF92E7F8).copy(alpha = 0.55f), w * 0.005f)
    if (floor == HeadquartersFloor.UPPER) {
        drawSofa(0.33f, 0.77f, w, h)
        drawBookshelf(0.68f, 0.75f, w, h)
        drawScenePlant(0.91f, 0.79f, w, h)
        drawScenePlant(0.08f, 0.77f, w, h)
    }
    // Tiny overhead strip lights and periodic neon pulse.
    for (i in 0..5) {
        val alpha = if ((i + frame / 6) % 3 == 0) 0.95f else 0.55f
        rect(0.20f + i * 0.105f, 0.315f, 0.055f, 0.006f, Color(0xFFB28AFF).copy(alpha = alpha))
    }
    text(if (floor == HeadquartersFloor.UPPER) "UPPER  /  COMMAND" else "LOWER  /  OPERATIONS", 0.50f, 0.955f, 0.020f, Color(0xFFA8E8F8))
    if (!live) text("EMPTY DESKS · CONNECT A BACKEND FOR REAL STAFF", 0.50f, 0.985f, 0.014f, Color(0xFFB9CDE3))
}

private fun DrawScope.drawPixelDesk(cx: Float, cy: Float, index: Int, w: Float, h: Float) {
    val deskW = 0.105f
    val deskH = 0.042f
    val x = cx - deskW * 0.5f
    val y = cy - deskH * 0.25f
    drawRect(Color(0xFF080D19).copy(alpha = 0.8f), Offset(w * (x - 0.016f), h * (y + 0.045f)), Size(w * 0.135f, h * 0.024f))
    drawRect(Color(0xFF76543D), Offset(w * x, h * y), Size(w * deskW, h * deskH))
    drawRect(Color(0xFFC08A5C), Offset(w * x, h * y), Size(w * deskW, h * 0.008f))
    drawRect(Color(0xFF352A2A), Offset(w * (x + 0.01f), h * (y + deskH),), Size(w * 0.008f, h * 0.045f))
    drawRect(Color(0xFF352A2A), Offset(w * (x + deskW - 0.018f), h * (y + deskH)), Size(w * 0.008f, h * 0.045f))
    drawRect(Color(0xFF151E31), Offset(w * (cx - 0.027f), h * (y - 0.040f)), Size(w * 0.054f, h * 0.037f))
    drawRect(if (index % 3 == 0) Color(0xFF8F78F8) else Color(0xFF63D7F1), Offset(w * (cx - 0.023f), h * (y - 0.035f)), Size(w * 0.046f, h * 0.026f))
    drawRect(Color(0xFFCBF8FF).copy(alpha = 0.72f), Offset(w * (cx - 0.019f), h * (y - 0.032f)), Size(w * 0.036f, h * 0.003f))
    drawRect(Color(0xFF9FB7D2), Offset(w * (cx - 0.020f), h * (y + 0.012f)), Size(w * 0.034f, h * 0.004f))
    drawRect(Color(0xFF080D19), Offset(w * (cx + 0.032f), h * (y + 0.007f)), Size(w * 0.012f, h * 0.010f))
    drawRect(Color(0xFF3C4961), Offset(w * (cx - 0.021f), h * (y + deskH + 0.050f)), Size(w * 0.042f, h * 0.009f))
    drawRect(Color(0xFF26344B), Offset(w * (cx - 0.018f), h * (y + deskH + 0.035f)), Size(w * 0.036f, h * 0.020f))
    drawCircle(Color(0xFF71DDEE).copy(alpha = 0.10f), w * 0.055f, Offset(w * cx, h * (y - 0.004f)))
}

private fun DrawScope.drawCentralGlobe(w: Float, h: Float, frame: Int) {
    val center = Offset(w * 0.50f, h * 0.59f)
    val radius = w * 0.093f
    drawCircle(Color(0xFF101B33), radius * 1.55f, center)
    drawCircle(Color(0xFF8076E9).copy(alpha = 0.18f), radius * 1.42f, center)
    drawCircle(Color(0xFF5BDCF4).copy(alpha = 0.78f), radius * 1.30f, center, style = Stroke(w * 0.004f))
    drawCircle(Color(0xFFB69AFF).copy(alpha = 0.60f), radius * (1.06f + (frame % 3) * 0.02f), center, style = Stroke(w * 0.0025f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF9CF3FF), Color(0xFF398FCA), Color(0xFF172A65), Color(0xFF111C3A)), radius = radius), radius, center)
    // Pixel continents and latitudes, purposefully drawn as square-edged cells.
    val land = Color(0xFF82E5B9)
    val continents = listOf(
        listOf(-0.36f, -0.22f, 0.34f, 0.22f), listOf(0.05f, -0.34f, 0.25f, 0.18f),
        listOf(-0.12f, 0.06f, 0.22f, 0.35f), listOf(0.28f, 0.10f, 0.17f, 0.16f),
    )
    continents.forEach { shape ->
        drawRect(land.copy(alpha = 0.78f), Offset(center.x + radius * shape[0], center.y + radius * shape[1]), Size(radius * shape[2], radius * shape[3]))
    }
    for (lat in listOf(-0.43f, -0.12f, 0.20f, 0.47f)) drawLine(Color(0xFFA8EFFF).copy(alpha = 0.38f), Offset(center.x - radius * 0.86f, center.y + radius * lat), Offset(center.x + radius * 0.86f, center.y + radius * lat), w * 0.0018f)
    drawCircle(Color(0xFFE4FFFF), w * 0.005f, Offset(center.x - radius * 0.25f, center.y - radius * 0.28f))
    // Circular command dais and control desk.
    drawOval(Color(0xFF080D1A), Offset(w * 0.35f, h * 0.675f), Size(w * 0.30f, h * 0.075f))
    drawOval(Color(0xFF334565), Offset(w * 0.36f, h * 0.662f), Size(w * 0.28f, h * 0.067f))
    drawOval(Color(0xFF64DFF0), Offset(w * 0.375f, h * 0.663f), Size(w * 0.25f, h * 0.042f), style = Stroke(w * 0.004f))
    for (i in 0..7) drawRect(if (i % 2 == 0) Color(0xFF7CE5F8) else Color(0xFFA992FF), Offset(w * (0.395f + i * 0.027f), h * 0.679f), Size(w * 0.016f, h * 0.008f))
    // Floating globe rings and command-position marker.
    drawOval(Color(0xFFB2F4FF).copy(alpha = 0.35f), Offset(w * 0.405f, h * 0.49f), Size(w * 0.19f, h * 0.18f), style = Stroke(w * 0.002f))
    drawRect(Color(0xFFFFD98C), Offset(w * 0.485f, h * 0.752f), Size(w * 0.03f, h * 0.008f))
}

private fun DrawScope.drawSofa(x: Float, y: Float, w: Float, h: Float) {
    drawRect(Color(0xFF080D18).copy(alpha = 0.6f), Offset(w * x, h * (y + 0.046f)), Size(w * 0.15f, h * 0.02f))
    drawRect(Color(0xFF514375), Offset(w * (x + 0.01f), h * y), Size(w * 0.13f, h * 0.038f))
    drawRect(Color(0xFF987ACB), Offset(w * (x + 0.02f), h * (y + 0.005f)), Size(w * 0.11f, h * 0.024f))
    drawRect(Color(0xFF69558B), Offset(w * x, h * (y + 0.012f)), Size(w * 0.018f, h * 0.034f))
    drawRect(Color(0xFF69558B), Offset(w * (x + 0.14f), h * (y + 0.012f)), Size(w * 0.018f, h * 0.034f))
}

private fun DrawScope.drawKitchenette(x: Float, y: Float, w: Float, h: Float) {
    drawRect(Color(0xFF25324A), Offset(w * x, h * y), Size(w * 0.14f, h * 0.06f))
    drawRect(Color(0xFF70D8EA), Offset(w * x, h * y), Size(w * 0.14f, h * 0.008f))
    drawRect(Color(0xFF101A2B), Offset(w * (x + 0.025f), h * (y - 0.035f)), Size(w * 0.036f, h * 0.035f))
    drawRect(Color(0xFF68A1BC), Offset(w * (x + 0.032f), h * (y - 0.027f)), Size(w * 0.022f, h * 0.020f))
    drawRect(Color(0xFFC9DBE5), Offset(w * (x + 0.09f), h * (y - 0.018f)), Size(w * 0.018f, h * 0.018f))
}

private fun DrawScope.drawBookshelf(x: Float, y: Float, w: Float, h: Float) {
    drawRect(Color(0xFF382A34), Offset(w * x, h * y), Size(w * 0.075f, h * 0.09f))
    drawRect(Color(0xFF8A684C), Offset(w * (x + 0.006f), h * (y + 0.04f)), Size(w * 0.063f, h * 0.005f))
    for (i in 0..3) drawRect(listOf(Color(0xFF9F82D3), Color(0xFF72C4D5), Color(0xFFBE8D59), Color(0xFF85B77B))[i], Offset(w * (x + 0.012f + i * 0.014f), h * (y + 0.009f)), Size(w * 0.010f, h * 0.027f))
    for (i in 0..2) drawRect(listOf(Color(0xFF75C1DB), Color(0xFFC2839E), Color(0xFFB99A65))[i], Offset(w * (x + 0.015f + i * 0.018f), h * (y + 0.047f)), Size(w * 0.012f, h * 0.028f))
}

private fun DrawScope.drawScenePlant(x: Float, y: Float, w: Float, h: Float) {
    drawRect(Color(0xFF9A5F63), Offset(w * x, h * (y + 0.045f)), Size(w * 0.055f, h * 0.045f))
    drawRect(Color(0xFFCE8E82), Offset(w * (x - 0.005f), h * (y + 0.042f)), Size(w * 0.065f, h * 0.009f))
    for (i in 0..4) {
        val lx = x + 0.006f + i * 0.011f
        drawRect(if (i % 2 == 0) Color(0xFF4EBD8C) else Color(0xFF73D79B), Offset(w * lx, h * (y + (i % 2) * 0.008f)), Size(w * 0.011f, h * (0.040f - (i % 2) * 0.006f)))
    }
}

private fun DrawScope.drawStaircase(w: Float, h: Float, floor: HeadquartersFloor) {
    val x = if (floor == HeadquartersFloor.UPPER) 0.76f else 0.76f
    val y = if (floor == HeadquartersFloor.UPPER) 0.67f else 0.68f
    for (step in 0..5) {
        drawRect(if (step % 2 == 0) Color(0xFF7180A4) else Color(0xFF566987), Offset(w * (x + step * 0.018f), h * (y + step * 0.024f)), Size(w * 0.065f, h * 0.018f))
        drawRect(Color(0xFF82E6F4).copy(alpha = 0.7f), Offset(w * (x + step * 0.018f), h * (y + step * 0.024f)), Size(w * 0.065f, h * 0.003f))
    }
    drawLine(Color(0xFFB5EFFF).copy(alpha = 0.7f), Offset(w * (x + 0.01f), h * (y + 0.01f)), Offset(w * (x + 0.12f), h * (y + 0.14f)), w * 0.003f)
}
