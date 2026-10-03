package p2pgate.tui.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Flag and environment resolution — the console's whole configuration story. */
class TuiConfigSpec {

    @Test
    fun `defaults to a local backend on mainnet`() {
        val config = TuiConfig.resolve(args = emptyArray(), env = emptyMap())
        assertEquals("http://127.0.0.1:8080", config.baseUrl)
        assertEquals("mainnet", config.network)
        assertNull(config.operatorKey)
    }

    @Test
    fun `flags win over the environment`() {
        val config = TuiConfig.resolve(
            args = arrayOf("--base-url", "https://ops.example", "--network", "testnet"),
            env = mapOf("P2P_BASE_URL" to "http://ignored", "P2P_NETWORK" to "mainnet"),
        )
        assertEquals("https://ops.example", config.baseUrl)
        assertEquals("testnet", config.network)
    }

    @Test
    fun `both flag spellings are accepted`() {
        // A scheme is added when missing, so a bare host is still a valid value.
        assertEquals("http://a", TuiConfig.resolve(arrayOf("--base-url=a"), emptyMap()).baseUrl)
        assertEquals("http://b", TuiConfig.resolve(arrayOf("--base-url", "b"), emptyMap()).baseUrl)
    }

    @Test
    fun `the environment supplies the operator key`() {
        val config = TuiConfig.resolve(emptyArray(), mapOf("P2P_OPERATOR_KEY" to "secret"))
        assertEquals("secret", config.operatorKey)
    }

    @Test
    fun `a bare host gets an http scheme and a trailing slash is dropped`() {
        assertEquals("http://ops.example", TuiConfig.resolve(arrayOf("--base-url", "ops.example/"), emptyMap()).baseUrl)
    }

    @Test
    fun `websocket base is derived from the http base`() {
        assertEquals("ws://127.0.0.1:8080", TuiConfig.resolve(emptyArray(), emptyMap()).wsBaseUrl)
        assertEquals(
            "wss://ops.example",
            TuiConfig.resolve(arrayOf("--base-url", "https://ops.example"), emptyMap()).wsBaseUrl,
        )
    }

    @Test
    fun `an unknown network is rejected rather than silently defaulted`() {
        val error = assertFailsWith<IllegalArgumentException> {
            TuiConfig.resolve(arrayOf("--network", "devnet"), emptyMap())
        }
        assertTrue(error.message!!.contains("devnet"))
    }
}
