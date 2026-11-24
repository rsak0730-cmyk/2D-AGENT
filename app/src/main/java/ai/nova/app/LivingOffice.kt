package ai.nova.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Native Compose Canvas headquarters. Everything in the room is drawn from persisted office data. */
@Composable
internal fun LivingOfficeScene(
    agents: List<WorkforceAgent>,
    characterProfiles: List<CharacterProfile>,
    providerConnections: List<ProviderConnectionInfo>,
    tasks: List<WorkforceTask>,
    events: List<OfficeEvent>,
    messages: List<WorkforceMessage>,
    meetings: List<MeetingSummary>,
    savedWorld: OfficeWorld?,
    reduceMotion: Boolean,
    backendStatus: String,
    onReduceMotionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onAgentSelected: (WorkforceAgent) -> Unit,
) {
    val world = savedWorld ?: remember { previewOfficeWorld() }
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var sceneResumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) sceneResumed = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) sceneResumed = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var animationFrame by remember { mutableIntStateOf(0) }
    val activeVisualActivity = agents.any { it.status.equals("working", true) } || tasks.any { it.state in setOf("PLANNING", "ASSIGNED", "RUNNING") } ||
        meetings.any { it.status.equals("running", true) } || events.any { isRecent(it.createdAt) && it.type in setOf("agent.message", "employee.movement.step") } ||
        messages.any { isRecent(it.createdAt) } || world.characters.values.any { it.movementState == "walking" }
    LaunchedEffect(sceneResumed, reduceMotion, activeVisualActivity) {
        if (!sceneResumed || reduceMotion || !activeVisualActivity) return@LaunchedEffect
        while (isActive) {
            delay(520L)
            animationFrame = (animationFrame + 1) % 24
        }
    }

    var camera by remember { mutableStateOf(OfficeCamera()) }
    var focusedRoomId by remember { mutableStateOf<String?>(null) }
    val cameraSnapshot = rememberUpdatedState(camera)

    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF070D19))) {
        BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))) {
            val viewportWidthPx = with(density) { maxWidth.toPx() }
            val viewportHeightPx = with(density) { maxHeight.toPx() }
            val cellPx = min(viewportWidthPx * 0.94f / world.width, viewportHeightPx * 0.49f / world.height)
            val cellDp = with(density) { cellPx.toDp() }
            val floorLeftPx = (viewportWidthPx - world.width * cellPx) / 2f
            val floorTopPx = viewportHeightPx * 0.31f
            val floorLeftDp = with(density) { floorLeftPx.toDp() }
            val floorTopDp = with(density) { floorTopPx.toDp() }
            val communicationAgents = remember(events.toList(), messages.toList()) { freshCommunicators(events, messages) }

            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val current = cameraSnapshot.value
                        camera = clampOfficeCamera(
                            OfficeCamera(current.zoom * zoomChange, current.x + pan.x, current.y + pan.y),
                            viewportWidthPx,
                            viewportHeightPx,
                        )
                    }
                },
            ) {
                Canvas(Modifier.fillMaxSize()) { drawNightCity(animationFrame) }
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = camera.zoom
                        scaleY = camera.zoom
                        translationX = camera.x
                        translationY = camera.y
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawHeadquarters(world, floorLeftPx, floorTopPx, cellPx, focusedRoomId, animationFrame, savedWorld == null, messages, characterProfiles)
                    }
                    world.rooms.forEach { room ->
                        Box(
                            Modifier.offset(
                                x = floorLeftDp + cellDp * room.x,
                                y = floorTopDp + cellDp * room.y,
                            ).size(cellDp * room.width, cellDp * room.height)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { focusedRoomId = room.id },
                        )
                    }
                    val meetingAgents = meetings.filter { it.status.equals("running", true) }
                        .flatMap { meeting -> meeting.invitations.filter { it.status != "declined" }.map { it.agentId } }.toSet()
                    world.characters.values.forEach { character ->
                        val agent = agents.firstOrNull { it.id == character.agentId } ?: return@forEach
                        val task = assignedTaskFor(agent, tasks)
                        val profileKey = if (agent.identity == "manager") "head" else agent.identity
                        val profile = characterProfiles.firstOrNull { it.key == profileKey }
                        val displayAgent = if (profile == null) agent else agent.copy(name = profile.name)
                        val modelLabel = verifiedModelIdentity(agent, providerConnections)
                        val modelStatus = modelConnectionStatus(agent, task, providerConnections)
                        val pose = workerPose(agent, task, agent.id in meetingAgents, agent.id in communicationAgents, character.movementState)
                        val spriteWidth = cellDp * 0.88f
                        val spriteHeight = cellDp * 1.46f
                        val destination = Offset(
                            with(density) { (floorLeftDp + cellDp * (character.x + 0.5f) - spriteWidth * 0.5f).toPx() },
                            with(density) { (floorTopDp + cellDp * (character.y + 0.67f) - spriteHeight * 0.69f).toPx() },
                        )
                        val animated by animateOffsetAsState(destination, animationSpec = if (reduceMotion) snap() else tween(230), label = "worker-${agent.id}-step")
                        PixelWorkerSprite(
                            agent = displayAgent,
                            pose = pose,
                            phase = animationFrame,
                            selected = focusedRoomId?.let { roomId -> world.rooms.firstOrNull { it.id == roomId }?.let { agentCellInside(agent.id, character, it) } } ?: false,
                            modelIdentity = modelLabel,
                            modelStatus = modelStatus,
                            appearanceJson = profile?.appearanceJson,
                            reduceMotion = reduceMotion,
                            modifier = Modifier.offset { IntOffset(animated.x.roundToInt(), animated.y.roundToInt()) }
                                .size(spriteWidth, spriteHeight)
                                .semantics { contentDescription = "${displayAgent.name}, ${agent.title}, ${pose.name.lowercase()}, model ${modelLabel ?: "unverified"}, ${modelStatus}, tap to open worker profile" }
                                .clickable { onAgentSelected(displayAgent) },
                        )
                    }
                }

                Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 12.dp, top = 9.dp, end = 10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("NOVA  /  NIGHTSHIFT", color = Color(0xFFB5F2FF), style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("LIVING HEADQUARTERS", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                            Text(if (savedWorld == null) "OFFLINE PREVIEW · LIVE WORKERS LOAD FROM YOUR BACKEND" else "$backendStatus · ${world.rooms.size} saved rooms", color = Color(0xFFA8C5D7), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { camera = clampOfficeCamera(OfficeCamera((camera.zoom - 0.25f).coerceAtLeast(1f), camera.x, camera.y), viewportWidthPx, viewportHeightPx) }) { Text("−", color = Color.White, fontSize = 22.sp) }
                                Text("${(camera.zoom * 100).roundToInt()}%", color = Color(0xFFDAEAFF), style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { camera = clampOfficeCamera(OfficeCamera((camera.zoom + 0.25f).coerceAtMost(3.5f), camera.x, camera.y), viewportWidthPx, viewportHeightPx) }) { Text("+", color = Color.White, fontSize = 22.sp) }
                            }
                            TextButton(onClick = { camera = OfficeCamera(); focusedRoomId = null }) { Text("Recenter", color = Color(0xFFA6E7F2), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        novaDepartments.forEach { department ->
                            val room = world.rooms.firstOrNull { it.id == department.roomId }
                            AssistChip(
                                enabled = room != null,
                                onClick = {
                                    if (room != null) {
                                        focusedRoomId = room.id
                                        val nextZoom = 2.15f
                                        camera = clampOfficeCamera(
                                            OfficeCamera(
                                                nextZoom,
                                                viewportWidthPx * 0.5f - (floorLeftPx + (room.x + room.width * 0.5f) * cellPx) * nextZoom,
                                                viewportHeightPx * 0.57f - (floorTopPx + (room.y + room.height * 0.5f) * cellPx) * nextZoom,
                                            ),
                                            viewportWidthPx,
                                            viewportHeightPx,
                                        )
                                    }
                                },
                                label = { Text(department.name, maxLines = 1) },
                            )
                        }
                    }
                }

                val selectedRoom = world.rooms.firstOrNull { it.id == focusedRoomId }
                val roomDepartment = selectedRoom?.let { departmentForRoom(it) ?: OfficeDepartment(it.name, it.id, "Workspace area") }
                val roomWorkers = selectedRoom?.let { room ->
                    world.characters.values.filter { it.x in room.x until room.x + room.width && it.y in room.y until room.y + room.height }
                        .mapNotNull { character -> agents.firstOrNull { it.id == character.agentId } }
                }.orEmpty()
                val selectedFurnitureCount = selectedRoom?.let { room -> world.furniture.count { it.x in room.x until room.x + room.width && it.y in room.y until room.y + room.height } } ?: 0
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xE90B1424),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (selectedRoom != null && roomDepartment != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(roomDepartment.name, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text("${roomDepartment.subtitle} · ${selectedRoom.width}×${selectedRoom.height} tiles · $selectedFurnitureCount furniture pieces", color = Color(0xFFB4C9DC), style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { focusedRoomId = null }) { Text("Clear") }
                            }
                            Text(if (roomWorkers.isEmpty()) "No worker is positioned here in the latest saved office snapshot." else "Here now: ${roomWorkers.joinToString { "${it.name} · ${agentActivityLabel(it, tasks, meetings, events, messages)}" }}", color = Color(0xFFD2E5F4), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        } else {
                            val online = agents.count { it.status.lowercase() !in setOf("disabled", "offline", "error") }
                            val offline = (agents.size - online).coerceAtLeast(0)
                            val working = agents.count { agent -> agent.status.equals("working", true) || assignedTaskFor(agent, tasks)?.state == "RUNNING" }
                            val activeTasks = tasks.count { it.state in setOf("RECEIVED", "PLANNING", "ASSIGNED", "RUNNING") }
                            val completedTasks = tasks.count { it.state in setOf("DONE", "COMPLETED", "SUCCEEDED") }
                            Text(if (savedWorld == null) "Connect your NOVA API to enter the live world · no sample workers or task states are shown." else "${online} online · ${offline} offline · ${working} working · ${communicationAgents.size} recently communicating", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(if (savedWorld == null) "Departments are an environment preview. Live worker positions, projects and activity come from your backend." else "$activeTasks active tasks · $completedTasks completed tasks · saved office movement and task states", color = Color(0xFFB4C9DC), style = MaterialTheme.typography.labelSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("MOTION", color = Color(0xFF8FAABD), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onReduceMotionChange(!reduceMotion) }) { Text(if (reduceMotion) "Reduced · paused" else "Ambient · on", color = Color(0xFFA6E7F2), style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun agentCellInside(agentId: String, character: OfficeCharacterState, room: OfficeRoomInfo): Boolean =
    character.agentId == agentId && character.x in room.x until room.x + room.width && character.y in room.y until room.y + room.height

private fun isRecent(timestamp: String): Boolean =
    runCatching { Instant.parse(timestamp).isAfter(Instant.now().minus(90, ChronoUnit.SECONDS)) }.getOrDefault(false)

private fun freshCommunicators(events: List<OfficeEvent>, messages: List<WorkforceMessage>): Set<String> {
    val eventAgents = events.asSequence().filter { it.type == "agent.message" && isRecent(it.createdAt) }.flatMap { event ->
        listOfNotNull(event.agentId, runCatching { JSONObject(event.payload).optString("recipient_agent_id").takeIf { it.isNotBlank() } }.getOrNull()).asSequence()
    }
    val messageAgents = messages.asSequence().filter { isRecent(it.createdAt) }.flatMap { sequenceOf(it.senderAgentId, it.recipientAgentId).filterNotNull() }
    return eventAgents.toSet() + messageAgents.toSet()
}

private fun agentActivityLabel(agent: WorkforceAgent, tasks: List<WorkforceTask>, meetings: List<MeetingSummary>, events: List<OfficeEvent>, messages: List<WorkforceMessage>): String {
    val task = assignedTaskFor(agent, tasks)
    return when {
        meetings.any { meeting -> meeting.status == "running" && meeting.invitations.any { it.agentId == agent.id && it.status != "declined" } } -> "in a live meeting"
        freshCommunicators(events, messages).contains(agent.id) -> "communicating"
        task?.state == "PLANNING" -> "planning"
        task?.state == "ASSIGNED" -> "starting ${task.title}"
        task?.state == "RUNNING" -> "working on ${task.title}"
        agent.status.equals("working", true) -> "working"
        else -> agent.status
    }
}

@Composable
private fun PixelWorkerSprite(agent: WorkforceAgent, pose: WorkerPose, phase: Int, selected: Boolean, modelIdentity: String?, modelStatus: String, appearanceJson: String?, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val look = remember(agent.identity, appearanceJson) { officeLook(agent.identity, appearanceJson) }
    val floatOffset by androidx.compose.animation.core.animateFloatAsState(if (!reduceMotion && phase % 2 == 0) -1.5f else if (reduceMotion) 0f else 1.5f, animationSpec = tween(1500), label = "hologram-float")
    val glowAlpha by androidx.compose.animation.core.animateFloatAsState(if (!reduceMotion && phase % 3 != 0) 0.78f else 0.94f, animationSpec = tween(900), label = "hologram-glow")
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun p(x: Float, y: Float, width: Float, height: Float, color: Color) =
                drawRect(color, Offset(x * w, y * h), Size(width * w, height * h))
            fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, stroke: Float = w * 0.035f) =
                drawLine(color, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), stroke)
            val shirt = Color(look.shirtArgb)
            val hair = Color(look.hairArgb)
            val skin = Color(look.skinArgb)
            val accent = Color(look.accentArgb)
            val eyes = Color(look.eyeArgb)
            val walk = if (pose == WorkerPose.WALKING) if (phase % 2 == 0) 0.055f else -0.055f else 0f
            val hand = if (pose in setOf(WorkerPose.TYPING, WorkerPose.TALKING, WorkerPose.MEETING) && phase % 2 == 0) 0.035f else 0f
            val idleBob = if (pose in setOf(WorkerPose.IDLE, WorkerPose.RESTING) && phase % 2 == 0) 0.012f else 0f
            val headShift = if (pose == WorkerPose.IDLE && phase % 7 in 0..1) 0.012f else if (pose == WorkerPose.IDLE && phase % 7 in 4..5) -0.012f else 0f
            val blinkStart = Math.floorMod(agent.identity.hashCode(), 22)
            val blinking = pose != WorkerPose.WALKING && phase % 24 in blinkStart..(blinkStart + 1)
            val tired = pose == WorkerPose.RESTING
            val alert = pose == WorkerPose.ALERT
            val sitting = pose in setOf(WorkerPose.TYPING, WorkerPose.THINKING, WorkerPose.MEETING)
            drawOval(Color(0x70000000), Offset(w * 0.12f, h * 0.79f), Size(w * 0.76f, h * 0.13f))
            if (sitting) {
                p(0.22f, 0.58f, 0.56f, 0.08f, Color(0xFF263C58))
                p(0.29f, 0.61f, 0.09f, 0.21f, Color(0xFF263C58))
                p(0.63f, 0.61f, 0.09f, 0.21f, Color(0xFF263C58))
            }
            val bodyTop = if (tired) 0.36f + idleBob else 0.34f - idleBob
            val headWidth = when (look.faceShape) { 1 -> 0.34f; 2 -> 0.30f; 4 -> 0.27f; 5 -> 0.31f; else -> 0.32f }
            val headLeft = (1f - headWidth) * 0.5f + headShift
            val headY = 0.12f - idleBob
            p(headLeft + headWidth * 0.08f, headY + 0.035f, headWidth * 0.84f, 0.19f, skin)
            if (look.faceShape == 3) { p(headLeft + 0.06f, headY + 0.18f, 0.10f, 0.045f, skin); p(headLeft + headWidth - 0.16f, headY + 0.18f, 0.10f, 0.045f, skin) }
            if (look.faceShape == 4) { p(headLeft + 0.07f, headY + 0.20f, headWidth - 0.14f, 0.025f, skin) }
            val crownX = headLeft - 0.035f
            val crownW = headWidth + 0.07f
            when (look.hairStyle) {
                0 -> { p(crownX, 0.075f - idleBob, crownW, 0.10f, hair); p(crownX, 0.14f - idleBob, 0.075f, 0.14f, hair); p(crownX + crownW * 0.78f, 0.13f - idleBob, 0.07f, 0.08f, hair) }
                1 -> { p(crownX, 0.075f - idleBob, crownW, 0.095f, hair); p(crownX + crownW * 0.65f, 0.12f - idleBob, 0.12f, 0.20f, hair); p(crownX + 0.06f, 0.08f - idleBob, 0.22f, 0.06f, hair) }
                2 -> { p(crownX, 0.075f - idleBob, crownW, 0.09f, hair); p(crownX - 0.005f, 0.14f - idleBob, 0.07f, 0.30f, hair); p(crownX + crownW - 0.06f, 0.14f - idleBob, 0.07f, 0.31f, hair); p(crownX + 0.12f, 0.045f - idleBob, 0.13f, 0.045f, hair); p(crownX + 0.29f, 0.045f - idleBob, 0.13f, 0.045f, hair) }
                3 -> { p(crownX + 0.02f, 0.08f - idleBob, crownW - 0.04f, 0.07f, hair); p(crownX + 0.04f, 0.12f - idleBob, crownW - 0.08f, 0.035f, hair) }
                4 -> { p(crownX + 0.07f, 0.045f - idleBob, crownW - 0.10f, 0.09f, hair); p(crownX, 0.12f - idleBob, 0.065f, 0.14f, hair); p(crownX + crownW - 0.055f, 0.12f - idleBob, 0.055f, 0.07f, hair) }
                5 -> { p(crownX, 0.075f - idleBob, crownW, 0.09f, hair); p(crownX, 0.13f - idleBob, 0.065f, 0.30f, hair); p(crownX + crownW - 0.06f, 0.13f - idleBob, 0.06f, 0.27f, hair); p(crownX - 0.035f, 0.38f - idleBob, 0.07f, 0.055f, accent); p(crownX - 0.03f, 0.44f - idleBob, 0.06f, 0.055f, hair) }
                6 -> { p(crownX, 0.07f - idleBob, crownW, 0.10f, hair); p(crownX - 0.01f, 0.14f - idleBob, 0.075f, 0.24f, hair); p(crownX + crownW - 0.065f, 0.14f - idleBob, 0.075f, 0.24f, hair) }
                7 -> { p(crownX, 0.075f - idleBob, crownW, 0.085f, hair); p(crownX + 0.18f, 0.085f - idleBob, 0.20f, 0.055f, hair); p(crownX + crownW - 0.07f, 0.12f - idleBob, 0.07f, 0.16f, hair) }
                8 -> { p(crownX, 0.085f - idleBob, crownW, 0.075f, hair); p(crownX + 0.05f, 0.035f - idleBob, 0.10f, 0.08f, hair); p(crownX + 0.19f, 0.025f - idleBob, 0.10f, 0.09f, hair); p(crownX + 0.32f, 0.045f - idleBob, 0.10f, 0.08f, hair); p(crownX - 0.025f, 0.16f - idleBob, 0.07f, 0.18f, hair); p(crownX + crownW - 0.04f, 0.16f - idleBob, 0.06f, 0.18f, hair) }
                else -> { p(crownX, 0.075f - idleBob, crownW, 0.09f, hair); p(crownX + 0.08f, 0.04f - idleBob, 0.16f, 0.05f, hair); p(crownX + 0.29f, 0.045f - idleBob, 0.14f, 0.05f, hair); p(crownX + 0.02f, 0.15f - idleBob, 0.06f, 0.18f, hair) }
            }
            val eyeY = 0.235f - idleBob
            val eyeColor = if (blinking) skin else eyes
            val eyeHeight = if (blinking) 0.012f else 0.035f
            p(headLeft + headWidth * 0.25f, eyeY, headWidth * 0.13f, eyeHeight, eyeColor)
            p(headLeft + headWidth * 0.64f, eyeY, headWidth * 0.13f, eyeHeight, eyeColor)
            val browLift = if (pose == WorkerPose.ALERT) -0.012f else if (pose == WorkerPose.THINKING) -0.005f else 0f
            p(headLeft + headWidth * 0.20f, eyeY - 0.025f + browLift, headWidth * 0.20f, 0.018f, hair)
            p(headLeft + headWidth * 0.60f, eyeY - 0.025f - browLift, headWidth * 0.20f, 0.018f, hair)
            p(headLeft + headWidth * 0.49f, eyeY + 0.025f, 0.025f, 0.035f, skin.copy(red = (skin.red * 0.88f).coerceIn(0f, 1f)))
            if (pose == WorkerPose.TALKING || pose == WorkerPose.MEETING) p(headLeft + headWidth * 0.43f, eyeY + 0.07f, headWidth * 0.16f, if (phase % 2 == 0) 0.045f else 0.025f, Color(0xFF7D3948))
            else p(headLeft + headWidth * 0.43f, eyeY + 0.075f, headWidth * 0.16f, 0.018f, Color(0xFF7D3948))
            if (look.accessoryName.contains("glasses", true)) {
                p(headLeft + headWidth * 0.14f, eyeY - 0.015f, headWidth * 0.31f, 0.07f, Color(0x668FEAFF)); p(headLeft + headWidth * 0.55f, eyeY - 0.015f, headWidth * 0.31f, 0.07f, Color(0x668FEAFF)); p(headLeft + headWidth * 0.44f, eyeY + 0.005f, headWidth * 0.13f, 0.014f, accent)
            } else if (look.accessoryName.contains("clip", true) || look.accessoryName.contains("pin", true)) {
                p(crownX + crownW * 0.72f, 0.09f, 0.07f, 0.07f, accent)
            }
            val shoulders = when (look.bodyType) { 1, 3, 4 -> 0.42f; 2, 5 -> 0.33f; else -> 0.38f }
            val torsoLeft = (1f - shoulders) * 0.5f
            p(torsoLeft, bodyTop, shoulders, 0.28f, shirt)
            when (look.outfitStyle) {
                0 -> { p(torsoLeft + shoulders * 0.43f, bodyTop, shoulders * 0.14f, 0.27f, accent.copy(alpha = 0.85f)); p(torsoLeft + shoulders * 0.15f, bodyTop + 0.05f, shoulders * 0.18f, 0.04f, Color.White.copy(alpha = 0.45f)) }
                1 -> { p(torsoLeft + shoulders * 0.12f, bodyTop + 0.04f, shoulders * 0.76f, 0.045f, accent.copy(alpha = 0.9f)); p(torsoLeft + shoulders * 0.45f, bodyTop + 0.10f, shoulders * 0.1f, 0.18f, Color.White.copy(alpha = 0.4f)) }
                2 -> { p(torsoLeft + shoulders * 0.12f, bodyTop + 0.04f, shoulders * 0.76f, 0.04f, accent); p(torsoLeft + shoulders * 0.12f, bodyTop + 0.11f, shoulders * 0.76f, 0.035f, accent.copy(alpha = 0.6f)) }
                3 -> { p(torsoLeft + shoulders * 0.10f, bodyTop + 0.10f, shoulders * 0.18f, 0.14f, accent.copy(alpha = 0.8f)); p(torsoLeft + shoulders * 0.72f, bodyTop + 0.10f, shoulders * 0.18f, 0.14f, accent.copy(alpha = 0.8f)) }
                else -> { p(torsoLeft + shoulders * 0.08f, bodyTop + 0.18f, shoulders * 0.84f, 0.04f, accent.copy(alpha = 0.8f)) }
            }
            when (pose) {
                WorkerPose.THINKING -> {
                    p(0.68f, 0.39f, 0.12f, 0.17f, skin); p(0.63f, 0.30f, 0.12f, 0.11f, skin)
                }
                WorkerPose.TALKING, WorkerPose.MEETING -> {
                    p(0.19f, 0.38f - hand, 0.16f, 0.08f, skin); p(0.66f, 0.40f + hand, 0.15f, 0.08f, skin)
                }
                WorkerPose.TYPING -> {
                    p(0.18f, 0.49f + hand, 0.24f, 0.06f, skin); p(0.58f, 0.49f - hand, 0.24f, 0.06f, skin)
                }
                else -> {
                    p(0.20f, 0.40f + walk, 0.12f, 0.08f, skin); p(0.68f, 0.40f - walk, 0.12f, 0.08f, skin)
                }
            }
            val trouser = Color(0xFF293B59)
            p(0.37f, 0.62f, 0.11f, 0.16f + walk, trouser); p(0.53f, 0.62f, 0.11f, 0.16f - walk, trouser)
            if (pose == WorkerPose.WALKING) { line(0.42f, 0.78f + walk, 0.35f, 0.84f, Color(0xFF182438)); line(0.59f, 0.78f - walk, 0.67f, 0.84f, Color(0xFF182438)) }
            else { p(0.32f, 0.79f, 0.18f, 0.055f, Color(0xFF182438)); p(0.51f, 0.79f, 0.18f, 0.055f, Color(0xFF182438)) }
            if (pose == WorkerPose.TALKING || pose == WorkerPose.MEETING) {
                drawRoundRect(Color(0xF0192C43), Offset(w * 0.63f, h * 0.0f), Size(w * 0.33f, h * 0.15f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
                drawCircle(accent, w * 0.018f, Offset(w * 0.72f, h * 0.075f)); drawCircle(accent, w * 0.018f, Offset(w * 0.80f, h * 0.075f)); drawCircle(accent, w * 0.018f, Offset(w * 0.88f, h * 0.075f))
            } else if (pose == WorkerPose.THINKING) {
                drawCircle(accent.copy(alpha = if (phase % 2 == 0) 0.95f else 0.58f), w * (0.065f + (phase % 2) * 0.008f), Offset(w * 0.86f, h * 0.08f), style = Stroke(w * 0.018f))
                drawCircle(accent.copy(alpha = 0.8f), w * 0.025f, Offset(w * 0.76f, h * 0.17f))
            }
            val statusColor = when {
                alert -> Color(0xFFFF6F72)
                agent.status.equals("working", true) -> Color(0xFF69F0B2)
                agent.status.equals("paused", true) || agent.status.equals("disabled", true) -> Color(0xFF8392A8)
                else -> Color(0xFF69C9FF)
            }
            drawCircle(statusColor, w * 0.058f, Offset(w * 0.83f, h * 0.27f))
            if (selected) drawRect(Color(0xCC7CE9FF), Offset(w * 0.07f, h * 0.04f), Size(w * 0.86f, h * 0.84f), style = Stroke(w * 0.022f))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color(0xFFF4F8FF).toArgb(); textSize = h * 0.09f; textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setShadowLayer(h * 0.025f, 0f, h * 0.012f, Color.Black.toArgb())
            }
            drawContext.canvas.nativeCanvas.drawText(agent.name.take(14), w * 0.5f, h * 0.99f, paint)
        }
        androidx.compose.material3.Surface(
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-15).dp + floatOffset.dp)
                .widthIn(max = 112.dp).border(0.7.dp, Color(0x9F8CEEFF).copy(alpha = glowAlpha), RoundedCornerShape(6.dp))
                .graphicsLayer { alpha = glowAlpha },
            shape = RoundedCornerShape(6.dp),
            color = Color(0xE60A1728),
            shadowElevation = 5.dp,
        ) {
            Column(Modifier.padding(horizontal = 5.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (modelIdentity == null) "[ MODEL UNVERIFIED ]" else "[ $modelIdentity ]", color = Color(0xFFF0FBFF), fontSize = 7.sp, maxLines = 1, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.size(4.dp)) { drawCircle(if (modelStatus in setOf("Working", "Thinking", "Connected")) Color(0xFF70E8C2) else Color(0xFFFFC477)) }
                    Text(modelStatus, color = Color(0xFFB7D9EC), fontSize = 6.sp, maxLines = 1)
                }
            }
        }
    }
}

private fun DrawScope.drawNightCity(frame: Int) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF050A17), Color(0xFF111A38), Color(0xFF283058)), endY = size.height), size = size)
    val width = size.width
    val height = size.height
    drawCircle(Brush.radialGradient(listOf(Color(0x6682A9FF), Color.Transparent), radius = width * 0.21f), width * 0.16f, Offset(width * 0.78f, height * 0.19f))
    drawCircle(Color(0xFFCEE8FF), width * 0.035f, Offset(width * 0.78f, height * 0.19f))
    drawCircle(Color(0xFF101A35), width * 0.031f, Offset(width * 0.795f, height * 0.183f))
    val detail = if (width < 720f) 0 else if (width < 1200f) 1 else 2
    val stars = if (detail == 0) 24 else if (detail == 1) 36 else 50
    for (index in 0 until stars) {
        val x = ((index * 7919) % 997) / 997f * width
        val y = ((index * 4217) % 197) / 197f * height * 0.29f
        val alpha = if (index % 5 == frame % 5) 0.75f else 0.3f
        drawCircle(Color(0xFFBDEAFF).copy(alpha = alpha), max(1.2f, width * 0.0025f), Offset(x, y))
    }
    val skylineTop = height * 0.16f
    val skylineBottom = height * 0.53f
    for (building in 0 until 13) {
        val x = width * building / 13f
        val w = width / 13f + 2f
        val buildingHeight = height * (0.13f + ((building * 37) % 17) / 100f)
        val y = skylineBottom - buildingHeight
        drawRect(if (building % 3 == 0) Color(0xFF131D39) else Color(0xFF0B142A), Offset(x, y), Size(w, buildingHeight))
        drawRect(Color(0xFF35517B).copy(alpha = 0.27f), Offset(x, y), Size(w, max(2f, height * 0.004f)))
        val rows = min(if (detail == 0) 10 else if (detail == 1) 18 else 30, max(1, (buildingHeight / max(8f, height * 0.022f)).toInt()))
        val columns = if (detail == 0) 2 else 3
        for (row in 0 until rows) for (column in 0 until columns) {
            val seed = building * 31 + row * 13 + column * 7
            if (seed % 4 != 0) {
                val light = if ((seed + frame) % 9 == 0) Color(0xFFFFCF85) else if (seed % 3 == 0) Color(0xFF69D7F0) else Color(0xFF8E9BFA)
                drawRect(light.copy(alpha = if (seed % 5 == frame % 5) 0.9f else 0.42f), Offset(x + w * (0.18f + column * 0.27f), y + height * 0.018f + row * height * 0.022f), Size(w * 0.12f, height * 0.009f))
            }
        }
    }
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA080D1C)), startY = skylineTop, endY = skylineBottom), Offset(0f, skylineTop), Size(width, skylineBottom - skylineTop))
    drawLine(Color(0xFF4DD7F5).copy(alpha = 0.30f), Offset(0f, skylineBottom), Offset(width, skylineBottom), width * 0.004f)
    for (reflection in 0 until 18) {
        val x = width * ((reflection * 67) % 101) / 101f
        val y = skylineBottom + height * (reflection % 9) * 0.012f
        drawLine(Color(0xFF65BCEB).copy(alpha = 0.035f), Offset(x, y), Offset(x + width * (0.02f + reflection % 4 * 0.01f), y + height * 0.09f), width * 0.003f)
    }
    // NOVA's glass headquarters rises behind the walkable floor as a layered multi-storey tower.
    val towerLeft = width * 0.035f
    val towerTop = height * 0.17f
    val towerWidth = width * 0.93f
    val towerHeight = height * 0.73f
    drawRect(Color(0xB50B162B), Offset(towerLeft, towerTop), Size(towerWidth, towerHeight))
    drawRect(Brush.horizontalGradient(listOf(Color(0xFF435C96), Color(0xFF18294D), Color(0xFF664C97))), Offset(towerLeft, towerTop), Size(towerWidth, max(2f, height * 0.009f)))
    val floors = 6
    for (floor in 0 until floors) {
        val floorY = towerTop + towerHeight * floor / floors
        drawRect(Color(0xFF8BC6EF).copy(alpha = 0.13f), Offset(towerLeft, floorY), Size(towerWidth, max(1.5f, height * 0.004f)))
        for (column in 0 until 9) {
            val px = towerLeft + towerWidth * (column + 0.18f) / 9f
            val light = if ((floor * 7 + column * 3) % 5 == 0) Color(0xFFFFCF8A) else Color(0xFF72CFF5)
            drawRect(light.copy(alpha = if ((floor + column + frame) % 7 == 0) 0.62f else 0.22f), Offset(px, floorY + height * 0.018f), Size(towerWidth * 0.052f, towerHeight / floors * 0.48f))
        }
    }
    for (beam in 0..8) {
        val beamX = towerLeft + towerWidth * beam / 8f
        drawLine(Color(0xFF76CFF0).copy(alpha = 0.14f), Offset(beamX, towerTop), Offset(beamX, towerTop + towerHeight), max(1f, width * 0.002f))
    }
    drawRect(Color(0xFF75DFF8).copy(alpha = 0.43f), Offset(towerLeft, towerTop), Size(towerWidth, towerHeight), style = Stroke(max(1.5f, width * 0.004f)))
}

private fun DrawScope.drawHeadquarters(world: OfficeWorld, left: Float, top: Float, cell: Float, selectedRoom: String?, frame: Int, preview: Boolean, messages: List<WorkforceMessage>, characterProfiles: List<CharacterProfile>) {
    val x0 = left
    val y0 = top
    val floorW = world.width * cell
    val floorH = world.height * cell
    drawRect(Color(0xCC050A16), Offset(x0 - cell * 0.6f, y0 - cell * 0.5f), Size(floorW + cell * 1.2f, floorH + cell * 1.2f))
    drawRect(Brush.verticalGradient(listOf(Color(0xFF263957), Color(0xFF121E35)), y0, y0 + floorH), Offset(x0, y0), Size(floorW, floorH))
    val tileStride = if (cell < 18f) 2 else 1
    for (row in 0 until world.height step tileStride) for (column in 0 until world.width step tileStride) {
        val tile = if ((row + column) % 2 == 0) Color(0xFF1C2A42) else Color(0xFF202F49)
        drawRect(tile.copy(alpha = 0.8f), Offset(x0 + column * cell, y0 + row * cell), Size(cell * tileStride * 0.96f, cell * tileStride * 0.96f))
        if ((row * 7 + column * 11) % 37 == 0) drawRect(Color(0xFF72D5E9).copy(alpha = 0.12f), Offset(x0 + column * cell + cell * 0.12f, y0 + row * cell + cell * 0.12f), Size(cell * 0.1f, cell * 0.1f))
    }
    drawRect(Color(0xFF7ADDF3).copy(alpha = 0.33f), Offset(x0, y0), Size(floorW, floorH), style = Stroke(max(2f, cell * 0.08f)))
    world.rooms.forEachIndexed { index, room ->
        val rx = x0 + room.x * cell
        val ry = y0 + room.y * cell
        val rw = room.width * cell
        val rh = room.height * cell
        val palette = roomPalette(room.id, index)
        drawRect(Color(0xEE0A1224), Offset(rx - cell * 0.10f, ry - cell * 0.10f), Size(rw + cell * 0.20f, rh + cell * 0.20f))
        drawRect(Brush.verticalGradient(listOf(palette.first, palette.second), ry, ry + rh), Offset(rx, ry), Size(rw, rh))
        for (row in 0 until room.height) for (column in 0 until room.width) {
            if ((row + column) % 2 == 0) drawRect(Color.White.copy(alpha = 0.025f), Offset(rx + column * cell, ry + row * cell), Size(cell * 0.95f, cell * 0.95f))
        }
        drawRect(palette.third.copy(alpha = if (selectedRoom == room.id) 0.95f else 0.57f), Offset(rx, ry), Size(rw, rh), style = Stroke(if (selectedRoom == room.id) cell * 0.12f else cell * 0.055f))
        // Glass top wall and mullions, with reflected neon edge lighting.
        val glassY = ry + cell * 0.12f
        val glassH = cell * 0.24f
        val paneW = rw / 5f
        for (pane in 0..3) {
            drawRect(Color(0xFF75DBF4).copy(alpha = 0.23f), Offset(rx + cell * 0.22f + pane * paneW, glassY), Size(paneW * 0.76f, glassH))
            drawRect(Color(0xFFB1F1FF).copy(alpha = 0.40f), Offset(rx + cell * 0.22f + pane * paneW, glassY), Size(max(1f, cell * 0.035f), glassH))
        }
        val roomName = if (room.id == "head-office") "NOVA  /  ${room.name.uppercase()}" else room.name.uppercase()
        drawSceneText(roomName.take(25), rx + rw * 0.5f, ry + cell * 0.88f, cell * 0.31f, Color(0xFFE4F3FF), bold = true)
        val department = departmentForRoom(room)
        if (department != null) drawSceneText(department.name.uppercase().take(22), rx + rw * 0.5f, ry + cell * 1.34f, cell * 0.20f, palette.third.copy(alpha = 0.95f), bold = true)
        // Sparse strip lights along the walls and a clear, illuminated doorway.
        drawRect(palette.third.copy(alpha = 0.27f), Offset(rx + cell * 0.18f, ry + rh - cell * 0.14f), Size(rw - cell * 0.36f, cell * 0.035f))
        drawRect(Color(0xFFFFD992), Offset(x0 + room.entrance.x * cell, y0 + room.entrance.y * cell), Size(cell * 0.96f, cell * 0.96f))
        drawRect(Color(0xFFFFF1C8).copy(alpha = 0.55f), Offset(x0 + room.entrance.x * cell + cell * 0.22f, y0 + room.entrance.y * cell + cell * 0.2f), Size(cell * 0.48f, cell * 0.48f))
        drawOfficePlant(rx + rw - cell * 1.15f, ry + rh - cell * 1.25f, cell)
        if (selectedRoom == room.id) {
            drawRect(Color(0xFF7CE9FF).copy(alpha = 0.85f), Offset(rx - cell * 0.12f, ry - cell * 0.12f), Size(rw + cell * 0.24f, rh + cell * 0.24f), style = Stroke(cell * 0.12f))
        }
    }
    world.furniture.forEach { item -> drawFurniture(item, x0, y0, cell, frame, characterProfiles) }
    messages.filter { isRecent(it.createdAt) && it.recipientAgentId != null }.takeLast(8).forEach { message ->
        val sender = world.characters[message.senderAgentId] ?: return@forEach
        val recipientId = message.recipientAgentId ?: return@forEach
        val recipient = world.characters[recipientId] ?: return@forEach
        val start = Offset(x0 + (sender.x + 0.5f) * cell, y0 + (sender.y + 0.5f) * cell)
        val end = Offset(x0 + (recipient.x + 0.5f) * cell, y0 + (recipient.y + 0.5f) * cell)
        val neon = Color(0xFF8A82FF).copy(alpha = if (frame % 2 == 0) 0.8f else 0.48f)
        drawLine(neon, start, end, max(1.5f, cell * 0.06f))
        drawCircle(Color(0xFFBEB4FF), max(2f, cell * 0.13f), Offset((start.x + end.x) * 0.5f, (start.y + end.y) * 0.5f))
    }
    world.characters.values.forEach { character ->
        if (character.route.isNotEmpty()) {
            val path = listOf(OfficeCell(character.x, character.y)) + character.route
            path.forEachIndexed { index, point ->
                if (index % 2 == frame % 2) drawCircle(Color(0xFF67E8FF).copy(alpha = 0.8f), cell * 0.09f, Offset(x0 + (point.x + 0.5f) * cell, y0 + (point.y + 0.5f) * cell))
            }
        }
    }
    // The default room and furniture are an environment-only preview; it never invents staff or state.
    if (preview) {
        drawSceneText("CONNECT NOVA BACKEND FOR LIVE STAFF", x0 + floorW * 0.5f, y0 + floorH + cell * 0.8f, cell * 0.3f, Color(0xFFA9D6EF), bold = true)
    }
}

private fun DrawScope.roomPalette(id: String, index: Int): Triple<Color, Color, Color> = when (id) {
    "design" -> Triple(Color(0xFF352344), Color(0xFF1C213C), Color(0xFFFF86CB))
    "development" -> Triple(Color(0xFF18394C), Color(0xFF17253E), Color(0xFF53DDF2))
    "research" -> Triple(Color(0xFF27335A), Color(0xFF1A2040), Color(0xFFA590FF))
    "testing" -> Triple(Color(0xFF214039), Color(0xFF18283A), Color(0xFF79F2B0))
    "server-room" -> Triple(Color(0xFF152A44), Color(0xFF10182E), Color(0xFF4CCEFF))
    "memory-core" -> Triple(Color(0xFF29234E), Color(0xFF141D39), Color(0xFFB99CFF))
    "meeting" -> Triple(Color(0xFF34405A), Color(0xFF1D263E), Color(0xFFFFD58B))
    "head-office" -> Triple(Color(0xFF344467), Color(0xFF1D2845), Color(0xFFFFD18B))
    else -> if (index % 2 == 0) Triple(Color(0xFF263A54), Color(0xFF1A263D), Color(0xFF7CDCE8)) else Triple(Color(0xFF303454), Color(0xFF1A213B), Color(0xFFB899FF))
}

private fun DrawScope.drawOfficePlant(x: Float, y: Float, cell: Float) {
    drawRect(Color(0xFF785146), Offset(x + cell * 0.15f, y + cell * 0.58f), Size(cell * 0.7f, cell * 0.32f))
    drawRect(Color(0xFFB7795A), Offset(x + cell * 0.10f, y + cell * 0.54f), Size(cell * 0.8f, cell * 0.12f))
    drawLine(Color(0xFF68D899), Offset(x + cell * 0.5f, y + cell * 0.56f), Offset(x + cell * 0.5f, y + cell * 0.17f), cell * 0.08f)
    drawLine(Color(0xFF6DE0A4), Offset(x + cell * 0.5f, y + cell * 0.4f), Offset(x + cell * 0.23f, y + cell * 0.25f), cell * 0.08f)
    drawLine(Color(0xFF8CF3BD), Offset(x + cell * 0.5f, y + cell * 0.36f), Offset(x + cell * 0.78f, y + cell * 0.19f), cell * 0.08f)
}

private fun DrawScope.drawFurniture(item: OfficeFurnitureInfo, left: Float, top: Float, cell: Float, frame: Int, characterProfiles: List<CharacterProfile>) {
    val width = if (item.rotation == 90 || item.rotation == 270) item.height else item.width
    val height = if (item.rotation == 90 || item.rotation == 270) item.width else item.height
    val x = left + item.x * cell
    val y = top + item.y * cell
    val w = width * cell
    val h = height * cell
    when (item.kind) {
        "desk" -> {
            drawRect(Color(0xFF5A382E), Offset(x + cell * 0.06f, y + h * 0.22f), Size(w - cell * 0.12f, h * 0.7f))
            drawRect(Color(0xFFB37A55), Offset(x, y + h * 0.08f), Size(w, h * 0.5f))
            drawRect(Color(0xFFFFC58C), Offset(x + cell * 0.15f, y + h * 0.12f), Size(w * 0.45f, h * 0.055f))
            drawRect(Color(0xFF26374E), Offset(x + w * 0.25f, y - cell * 0.48f), Size(w * 0.55f, cell * 0.48f))
            drawRect(Color(0xFF78DDF8).copy(alpha = 0.86f), Offset(x + w * 0.29f, y - cell * 0.43f), Size(w * 0.47f, cell * 0.30f))
            drawLine(Color(0xFFA6F6FF), Offset(x + w * 0.34f, y - cell * 0.34f), Offset(x + w * 0.68f, y - cell * 0.34f), cell * 0.025f)
            drawLine(Color(0xFF8379FC), Offset(x + w * 0.34f, y - cell * 0.25f), Offset(x + w * 0.61f, y - cell * 0.25f), cell * 0.025f)
            drawRect(Color(0xFF38425A), Offset(x + w * 0.65f, y + h * 0.62f), Size(cell * 0.58f, cell * 0.25f))
            drawRect(Color(0xFF222E44), Offset(x + w * 0.70f, y + h * 0.84f), Size(cell * 0.48f, cell * 0.08f))
        }
        "personal_workstation" -> {
            val identity = item.id.removePrefix("workstation-")
            val profileKey = if (identity == "manager") "head" else identity
            val look = officeLook(identity, characterProfiles.firstOrNull { it.key == profileKey }?.appearanceJson)
            val wood = Color(look.shirtArgb).copy(alpha = 0.92f)
            val trim = Color(look.accentArgb)
            // Personal chair, desktop, monitor/computer, keyboard and mouse are one
            // persisted workstation object so visual overlays do not block path cells.
            drawRect(Color(0xFF27364C), Offset(x + cell * 1.08f, y + cell * 0.40f), Size(cell * 0.82f, cell * 0.22f))
            drawRect(Color(0xFF384A61), Offset(x + cell * 1.16f, y + cell * 0.59f), Size(cell * 0.64f, cell * 0.13f))
            drawRect(Color(0xFF45576D), Offset(x + cell * 1.42f, y + cell * 0.69f), Size(cell * 0.10f, cell * 0.18f))
            drawRect(Color(0xFF35475D), Offset(x + cell * 1.08f, y + cell * 0.86f), Size(cell * 0.80f, cell * 0.08f))
            drawRect(Color(0xFF151F31), Offset(x + cell * 0.10f, y + cell * 0.23f), Size(cell * 2.80f, cell * 0.20f))
            drawRect(wood, Offset(x, y + cell * 0.17f), Size(cell * 3f, cell * 0.18f))
            drawRect(trim.copy(alpha = 0.9f), Offset(x + cell * 0.08f, y + cell * 0.18f), Size(cell * 2.84f, cell * 0.035f))
            drawRect(Color(0xFF29394E), Offset(x + cell * 0.18f, y + cell * 0.34f), Size(cell * 0.14f, cell * 0.55f))
            drawRect(Color(0xFF29394E), Offset(x + cell * 2.68f, y + cell * 0.34f), Size(cell * 0.14f, cell * 0.55f))
            drawRect(Color(0xFF11192A), Offset(x + cell * 0.70f, y - cell * 0.55f), Size(cell * 1.62f, cell * 0.67f))
            drawRect(Color(0xFF2D4762), Offset(x + cell * 0.76f, y - cell * 0.49f), Size(cell * 1.50f, cell * 0.54f))
            drawRect(Color(0xFF14253A), Offset(x + cell * 0.82f, y - cell * 0.45f), Size(cell * 1.38f, cell * 0.43f))
            when (identity) {
                "ui-ux", "game" -> {
                    val swatches = listOf(Color(0xFFFF758E), Color(0xFFFFD05E), trim, Color(0xFF68D9F2))
                    swatches.forEachIndexed { index, color -> drawRect(color, Offset(x + cell * (0.88f + index * 0.29f), y - cell * 0.39f), Size(cell * 0.19f, cell * 0.16f)) }
                    drawLine(Color.White.copy(alpha = 0.8f), Offset(x + cell * 0.9f, y - cell * 0.12f), Offset(x + cell * 1.95f, y - cell * 0.12f), cell * 0.025f)
                }
                "research" -> {
                    for (row in 0..3) drawLine(if (row % 2 == 0) Color(0xFFB5DDF7) else trim, Offset(x + cell * 0.91f, y - cell * (0.38f - row * 0.075f)), Offset(x + cell * (1.55f + (row % 2) * 0.42f), y - cell * (0.38f - row * 0.075f)), cell * 0.025f)
                    drawCircle(Color(0xFF79E4D5), cell * 0.11f, Offset(x + cell * 1.94f, y - cell * 0.31f), style = Stroke(cell * 0.025f))
                }
                "manager" -> {
                    for (bar in 0..4) drawRect(if (bar % 2 == 0) trim else Color(0xFF70D8E8), Offset(x + cell * (0.92f + bar * 0.22f), y - cell * (0.12f + (bar % 3) * 0.06f)), Size(cell * 0.12f, cell * (0.12f + (bar % 3) * 0.06f)))
                }
                "security" -> { drawRect(Color(0xFF71E6CE), Offset(x + cell * 1.01f, y - cell * 0.34f), Size(cell * 0.26f, cell * 0.23f)); drawLine(trim, Offset(x + cell * 1.46f, y - cell * 0.34f), Offset(x + cell * 2.02f, y - cell * 0.34f), cell * 0.03f) }
                "database" -> { for (row in 0..3) drawLine(trim.copy(alpha = 0.85f - row * 0.12f), Offset(x + cell * 0.90f, y - cell * (0.38f - row * 0.08f)), Offset(x + cell * 2.03f, y - cell * (0.38f - row * 0.08f)), cell * 0.028f) }
                else -> { for (row in 0..3) drawLine(if (row % 2 == 0) trim else Color(0xFF77DFF0), Offset(x + cell * 0.91f, y - cell * (0.37f - row * 0.08f)), Offset(x + cell * (1.55f + (row % 2) * 0.36f), y - cell * (0.37f - row * 0.08f)), cell * 0.025f) }
            }
            // Keyboard/mouse plus role-specific desk details.
            drawRect(Color(0xFFAFBBC7), Offset(x + cell * 0.92f, y + cell * 0.05f), Size(cell * 0.86f, cell * 0.075f))
            for (key in 0..6) drawLine(Color(0xFF52667A), Offset(x + cell * (0.98f + key * 0.105f), y + cell * 0.065f), Offset(x + cell * (0.98f + key * 0.105f), y + cell * 0.11f), cell * 0.014f)
            drawOval(Color(0xFFD2DCE6), Offset(x + cell * 1.92f, y + cell * 0.05f), Size(cell * 0.19f, cell * 0.12f))
            if (identity == "research" || identity == "documentation") {
                drawRect(Color(0xFFEEE5C8), Offset(x + cell * 0.10f, y + cell * 0.02f), Size(cell * 0.48f, cell * 0.13f))
                drawLine(Color(0xFF60738A), Offset(x + cell * 0.16f, y + cell * 0.06f), Offset(x + cell * 0.49f, y + cell * 0.06f), cell * 0.015f)
            } else if (identity == "android" || identity == "web" || identity == "backend" || identity == "game") {
                drawRect(trim, Offset(x + cell * 2.40f, y + cell * 0.02f), Size(cell * 0.32f, cell * 0.13f))
            }
        }
        "meeting_table" -> {
            drawRect(Color(0xFF563B51), Offset(x + cell * 0.12f, y + cell * 0.12f), Size(w - cell * 0.24f, h - cell * 0.20f))
            drawRect(Color(0xFFBD8A6D), Offset(x, y), Size(w, h * 0.70f))
            drawRect(Color(0xFFFFD995), Offset(x + w * 0.42f, y + h * 0.15f), Size(w * 0.15f, h * 0.36f))
            for (seat in 0..2) {
                drawRect(Color(0xFF303E59), Offset(x + w * (0.12f + seat * 0.33f), y - cell * 0.36f), Size(cell * 0.36f, cell * 0.25f))
                drawRect(Color(0xFF303E59), Offset(x + w * (0.12f + seat * 0.33f), y + h * 0.74f), Size(cell * 0.36f, cell * 0.25f))
            }
        }
        "server_rack" -> {
            drawRect(Color(0xFF0B1324), Offset(x, y - cell * 0.30f), Size(w, h + cell * 0.50f))
            drawRect(Color(0xFF233751), Offset(x + cell * 0.09f, y - cell * 0.20f), Size(w - cell * 0.18f, h + cell * 0.28f))
            for (shelf in 0..3) {
                val sy = y - cell * 0.12f + shelf * h * 0.24f
                drawRect(Color(0xFF111D31), Offset(x + cell * 0.18f, sy), Size(w - cell * 0.36f, h * 0.17f))
                drawCircle(if ((shelf + frame) % 3 == 0) Color(0xFF73F7D4) else Color(0xFF58AFFF), cell * 0.07f, Offset(x + w * 0.34f, sy + h * 0.08f))
                drawCircle(Color(0xFFB17CFF), cell * 0.055f, Offset(x + w * 0.58f, sy + h * 0.08f))
            }
        }
        "memory_core" -> {
            drawRect(Color(0xFF101729), Offset(x, y), Size(w, h))
            drawCircle(Color(0x3325D8F2), min(w, h) * 0.43f, Offset(x + w * 0.5f, y + h * 0.5f))
            drawCircle(Color(0xFF967BFF), min(w, h) * 0.36f, Offset(x + w * 0.5f, y + h * 0.5f), style = Stroke(cell * 0.09f))
            drawCircle(Color(0xFF74F2FF).copy(alpha = if (frame % 2 == 0) 1f else 0.58f), min(w, h) * 0.24f, Offset(x + w * 0.5f, y + h * 0.5f))
            drawCircle(Color.White, min(w, h) * 0.095f, Offset(x + w * 0.5f, y + h * 0.5f))
            drawSceneText("NOVA", x + w * 0.5f, y + h + cell * 0.27f, cell * 0.18f, Color(0xFFC1B5FF), true)
        }
        "coffee_machine" -> {
            drawRect(Color(0xFF23374D), Offset(x, y), Size(w, h))
            drawRect(Color(0xFF66D8E5), Offset(x + cell * 0.12f, y + cell * 0.12f), Size(cell * 0.72f, cell * 0.35f))
            drawRect(Color(0xFF583B2E), Offset(x + cell * 0.25f, y + cell * 0.58f), Size(cell * 0.48f, cell * 0.25f))
        }
        else -> {
            drawRect(Color(0xFF4C4251), Offset(x, y), Size(w, h))
            drawRect(Color(0xFFEEE6DA), Offset(x + cell * 0.08f, y + cell * 0.10f), Size(w * 0.84f, h * 0.55f))
            drawLine(Color(0xFF675EB6), Offset(x + w * 0.2f, y + h * 0.28f), Offset(x + w * 0.78f, y + h * 0.28f), cell * 0.045f)
            drawLine(Color(0xFF4A8DA2), Offset(x + w * 0.24f, y + h * 0.43f), Offset(x + w * 0.66f, y + h * 0.43f), cell * 0.035f)
        }
    }
}

private fun DrawScope.drawSceneText(text: String, x: Float, y: Float, size: Float, color: Color, bold: Boolean = false) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = max(1f, size)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("monospace", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setShadowLayer(max(1f, size * 0.4f), 0f, size * 0.08f, Color(0xFF101727).toArgb())
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}
