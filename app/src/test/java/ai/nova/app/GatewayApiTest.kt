package ai.nova.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GatewayApiTest {
    @Test
    fun gatewayUsesTheProvidedCentralServer() {
        assertEquals("https://gateway-server-mea8.onrender.com", NOVA_GATEWAY_BASE)
    }

    @Test
    fun canonicalWorkerIdentitiesMapToInstallationAgentKeys() {
        assertEquals("worker_1", gatewayAgentKey("android"))
        assertEquals("worker_2", gatewayAgentKey("research"))
        assertEquals("worker_10", gatewayAgentKey("game"))
    }

    @Test(expected = java.io.IOException::class)
    fun unknownWorkerIdentityDoesNotInventAnAgentKey() {
        gatewayAgentKey("unknown")
    }

    @Test
    fun installationRefreshWindowUsesServerExpiryAndFiveMinuteSkew() {
        val now = Instant.parse("2026-11-22T00:00:00Z").toEpochMilli()
        assertTrue(isInstallationExpiring("2026-11-22T00:04:00Z", now))
        assertFalse(isInstallationExpiring("2026-11-22T00:06:00Z", now))
        assertTrue(isInstallationExpiring("invalid", now))
    }
}
