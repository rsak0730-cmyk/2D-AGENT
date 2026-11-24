package ai.nova.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeVisualsTest {
    @Test
    fun seededRolesHaveStableDistinctUniformsAcrossRenderCalls() {
        val identities = listOf("manager", "android", "web", "game", "ui-ux", "backend", "database", "security", "qa", "research", "documentation")
        val firstRender = identities.map(::officeLook)
        val nextRender = identities.map(::officeLook)
        assertEquals(firstRender, nextRender)
        assertEquals(identities.size, firstRender.map { it.shirtArgb }.toSet().size)
        assertEquals(identities.size, firstRender.distinct().size)
        assertNotEquals(officeLook("manager"), officeLook("android"))
    }

    @Test
    fun unknownRolesStillUseDeterministicAppearance() {
        assertEquals(officeLook("custom-role"), officeLook("custom-role"))
        assertNotEquals(officeLook("custom-role"), officeLook("different-role"))
    }

    @Test
    fun sixDepartmentsAreBackedByDistinctOfficeRoomsIncludingMemoryCore() {
        val world = previewOfficeWorld()
        val rooms = world.rooms.associateBy { it.id }
        assertEquals(6, novaDepartments.size)
        novaDepartments.forEach { department -> assertTrue("missing ${department.roomId}", rooms.containsKey(department.roomId)) }
        assertEquals(6, novaDepartments.map { it.roomId }.toSet().size)
        assertTrue(world.furniture.any { it.kind == "memory_core" })
        assertTrue(world.characters.isEmpty()) // environment preview never fabricates workers
    }

    @Test
    fun cameraIsClampedAndCanPanOnlyWithinZoomedViewport() {
        assertEquals(OfficeCamera(3.5f, 0f, 0f), clampOfficeCamera(OfficeCamera(4f, 800f, 900f), 100f, 200f))
        assertEquals(OfficeCamera(2f, -100f, -200f), clampOfficeCamera(OfficeCamera(2f, -999f, -999f), 100f, 200f))
        assertEquals(OfficeCamera(1f, 0f, 0f), clampOfficeCamera(OfficeCamera(0.2f, -20f, -30f), 100f, 200f))
    }

    @Test
    fun workerPoseReflectsBackendStatesAndCommunication() {
        val worker = agent(status = "idle")
        assertEquals(WorkerPose.WALKING, workerPose(worker, null, false, false, "walking"))
        assertEquals(WorkerPose.MEETING, workerPose(worker, null, true, false, "idle"))
        assertEquals(WorkerPose.TALKING, workerPose(worker, null, false, true, "idle"))
        assertEquals(WorkerPose.RESTING, workerPose(agent(status = "paused"), null, false, false, "idle"))
        assertEquals(WorkerPose.ALERT, workerPose(agent(status = "error"), null, false, false, "idle"))
    }

    private fun agent(status: String) = WorkforceAgent(
        id = "worker-1", identity = "backend", name = "Backend", title = "Engineer", provider = "test",
        model = "model-a", instructions = "", status = status, specialties = emptyList(), temperature = 0.2,
        contextLength = 2048, permissions = emptyList(), memoryEnabled = true, priority = 50, critical = false,
        maxDuration = 300,
    )
}
