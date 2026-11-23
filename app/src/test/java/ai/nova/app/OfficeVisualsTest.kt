package ai.nova.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OfficeVisualsTest {
    @Test
    fun seededRolesHaveStableDistinctUniformsAcrossRenderCalls() {
        val identities = listOf("manager", "android", "web", "game", "ui-ux", "backend", "database", "security", "qa", "research", "documentation")
        val firstRender = identities.map(::officeLook)
        val nextRender = identities.map(::officeLook)
        assertEquals(firstRender, nextRender)
        assertEquals(identities.size, firstRender.map { it.shirtArgb }.toSet().size)
        assertNotEquals(officeLook("manager"), officeLook("android"))
    }

    @Test
    fun unknownRolesStillUseDeterministicAppearance() {
        assertEquals(officeLook("custom-role"), officeLook("custom-role"))
        assertNotEquals(officeLook("custom-role"), officeLook("different-role"))
    }
}
