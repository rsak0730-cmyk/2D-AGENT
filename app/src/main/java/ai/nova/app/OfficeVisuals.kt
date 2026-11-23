package ai.nova.app

import kotlin.math.floor

/** Stable, role-based visual identity for the native pixel-art renderer. */
internal data class OfficeLook(
    val shirtArgb: Int,
    val hairArgb: Int,
    val skinArgb: Int,
    val accentArgb: Int,
    val hairStyle: Int,
    val accessory: Int,
)

internal enum class WorkerPose { IDLE, WALKING, TYPING, THINKING, TALKING, MEETING, RESTING, ALERT }

internal data class OfficeDepartment(val name: String, val roomId: String, val subtitle: String)

internal val novaDepartments = listOf(
    OfficeDepartment("Frontend Design", "design", "Interface studio · critique wall"),
    OfficeDepartment("Development", "development", "Engineering floor · build desks"),
    OfficeDepartment("Research & AI", "research", "Discovery lab · model workbench"),
    OfficeDepartment("Testing", "testing", "Quality lab · test consoles"),
    OfficeDepartment("Operations", "server-room", "Infrastructure · live systems"),
    OfficeDepartment("Memory Core", "memory-core", "Knowledge archive · persistent memory"),
)

private val roleShirts = mapOf(
    "manager" to 0xFFB98B47.toInt(),
    "android" to 0xFF527ED3.toInt(),
    "web" to 0xFF3D9C90.toInt(),
    "game" to 0xFF8E70BE.toInt(),
    "ui-ux" to 0xFFD06E91.toInt(),
    "backend" to 0xFF4D79A8.toInt(),
    "database" to 0xFFB36F47.toInt(),
    "security" to 0xFF566579.toInt(),
    "qa" to 0xFF4B9D69.toInt(),
    "research" to 0xFF547FA2.toInt(),
    "documentation" to 0xFFB486C2.toInt(),
)

private val accents = intArrayOf(
    0xFF74E5E2.toInt(), 0xFFFFC36E.toInt(), 0xFFC19AFF.toInt(), 0xFF8DE5A5.toInt(),
    0xFFFF8FAD.toInt(), 0xFF8EBEFF.toInt(), 0xFFFFD98B.toInt(), 0xFF71DCEB.toInt(),
    0xFFFC9B7D.toInt(), 0xFFA6F1D0.toInt(), 0xFFDBA9FF.toInt(),
)

internal fun officeLook(identity: String): OfficeLook {
    val role = identity.lowercase()
    val hash = role.hashCode()
    val fallback = listOf(0xFF5D82D1, 0xFFCC765E, 0xFF8E70BE, 0xFF4D9C88, 0xFFD09B4A, 0xFF568FA8)
    val shirt = roleShirts[role] ?: fallback[Math.floorMod(hash, fallback.size)].toInt()
    val hair = if (Math.floorMod(hash, 2) == 0) 0xFF483A39.toInt() else 0xFF332F40.toInt()
    val skinSet = intArrayOf(0xFFF0C49A.toInt(), 0xFFE1A879.toInt(), 0xFFBC805D.toInt(), 0xFF8F5D49.toInt())
    return OfficeLook(
        shirtArgb = shirt,
        hairArgb = hair,
        skinArgb = skinSet[Math.floorMod(hash / 3, skinSet.size)],
        accentArgb = accents[Math.floorMod(hash, accents.size)],
        hairStyle = Math.floorMod(hash / 7, 4),
        accessory = Math.floorMod(hash / 11, 4),
    )
}

internal fun departmentForRoom(room: OfficeRoomInfo): OfficeDepartment? =
    novaDepartments.firstOrNull { it.roomId == room.id } ?: when {
        room.id == "security" -> OfficeDepartment("Operations", room.id, "Security operations")
        else -> null
    }

internal fun assignedTaskFor(agent: WorkforceAgent, tasks: List<WorkforceTask>): WorkforceTask? =
    tasks.firstOrNull { it.assignedAgentId == agent.id && it.state in ACTIVE_TASK_STATES }
        ?: tasks.firstOrNull { it.requestedAgentId == agent.id && it.state in ACTIVE_TASK_STATES }
        ?: if (agent.identity == "manager") tasks.firstOrNull { it.state == "PLANNING" } else null

private val ACTIVE_TASK_STATES = setOf("PLANNING", "ASSIGNED", "RUNNING")

internal fun workerPose(
    agent: WorkforceAgent,
    task: WorkforceTask?,
    inMeeting: Boolean,
    communicating: Boolean,
    movement: String,
): WorkerPose = when {
    movement == "walking" -> WorkerPose.WALKING
    agent.status.equals("error", true) || agent.status.equals("failed", true) -> WorkerPose.ALERT
    inMeeting -> WorkerPose.MEETING
    communicating -> WorkerPose.TALKING
    task?.state in setOf("PLANNING", "ASSIGNED") -> WorkerPose.THINKING
    task?.state == "RUNNING" || agent.status.equals("working", true) -> WorkerPose.TYPING
    agent.status.equals("paused", true) || agent.status.equals("disabled", true) -> WorkerPose.RESTING
    else -> WorkerPose.IDLE
}

internal data class OfficeCamera(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f)

internal fun clampOfficeCamera(camera: OfficeCamera, viewportWidth: Float, viewportHeight: Float): OfficeCamera {
    val zoom = camera.zoom.coerceIn(1f, 3.5f)
    val minX = viewportWidth * (1f - zoom)
    val minY = viewportHeight * (1f - zoom)
    return OfficeCamera(zoom, camera.x.coerceIn(minX, 0f), camera.y.coerceIn(minY, 0f))
}

internal fun previewOfficeWorld(): OfficeWorld {
    val names = listOf(
        "head-office" to "Head Office", "development" to "Development", "design" to "Frontend Design", "research" to "Research & AI",
        "security" to "Operations", "testing" to "Testing", "writing" to "Writing Studio", "meeting" to "Meeting Room",
        "server-room" to "Operations Hub", "reception" to "Reception", "break-room" to "Lounge", "memory-core" to "Memory Core",
    )
    val rooms = names.mapIndexed { index, (id, name) ->
        val x = (index % 4) * 10
        val y = (index / 4) * 8
        OfficeRoomInfo(id, name, x, y, 10, 6, OfficeCell(x + 4, y + 5))
    }
    val furniture = buildList {
        rooms.forEach { room ->
            when (room.id) {
                "meeting" -> add(OfficeFurnitureInfo("preview-meeting-table", "meeting_table", room.x + 3, room.y + 2, 3, 2, 0))
                "server-room" -> add(OfficeFurnitureInfo("preview-server-rack", "server_rack", room.x + 2, room.y + 1, 2, 1, 0))
                "memory-core" -> add(OfficeFurnitureInfo("preview-memory-core", "memory_core", room.x + 4, room.y + 1, 2, 2, 0))
                "break-room" -> add(OfficeFurnitureInfo("preview-coffee-machine", "coffee_machine", room.x + 2, room.y + 1, 1, 1, 0))
                else -> add(OfficeFurnitureInfo("preview-${room.id}-desk", "desk", room.x + 2, room.y + 1, 2, 1, 0))
            }
        }
    }
    return OfficeWorld(40, 24, rooms, furniture, emptyMap())
}

internal fun officeGridCell(screen: Float, start: Float, pan: Float, zoom: Float, cellSize: Float): Int =
    floor(((screen - pan) / zoom - start) / cellSize).toInt()
