package ai.nova.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayApiTest {
    @Test
    fun gatewayEndpointsUseProvidedLiveHost() {
        assertEquals("https://gateway-server-mea8.onrender.com", NOVA_GATEWAY_BASE)
    }

    @Test
    fun providerBaseUrlRequiresHttpsAndRejectsEmbeddedCredentials() {
        assertTrue(isSecureProviderBaseUrl("https://api.example.com/v1"))
        assertFalse(isSecureProviderBaseUrl("http://api.example.com"))
        assertFalse(isSecureProviderBaseUrl("https://secret@api.example.com"))
        assertFalse(isSecureProviderBaseUrl("https://api.example.com/?key=secret"))
    }

    @Test
    fun workerAndModelPathIdsRejectUnsafeSegments() {
        assertTrue(isSafeGatewayId("worker_01-uuid"))
        assertFalse(isSafeGatewayId("../../admin"))
        assertFalse(isSafeGatewayId("worker/other"))
        assertFalse(isSafeGatewayId("worker?token=leak"))
    }
}
