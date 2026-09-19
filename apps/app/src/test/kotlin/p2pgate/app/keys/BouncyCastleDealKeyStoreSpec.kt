package p2pgate.app.keys

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BouncyCastleDealKeyStoreSpec {

    @Test
    fun `ensure returns a stable compressed secp256k1 public key per deal`() {
        val store = BouncyCastleDealKeyStore()
        val first = store.ensurePublicKey("deal-A")
        val second = store.ensurePublicKey("deal-A")
        assertEquals(33, first.size)
        assert(first[0] == 0x02.toByte() || first[0] == 0x03.toByte())
        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun `different deals get different keys`() {
        val store = BouncyCastleDealKeyStore()
        assertNotEquals(
            store.ensurePublicKey("deal-A").toList(),
            store.ensurePublicKey("deal-B").toList(),
        )
    }

    @Test
    fun `move re-keys a pending key to the deal id`() {
        val store = BouncyCastleDealKeyStore()
        val pending = store.ensurePublicKey("pending-1")
        store.move("pending-1", "deal-A")
        assertEquals(pending.toList(), store.ensurePublicKey("deal-A").toList())
        assertNull(store.publicKey("pending-1"))
    }

    @Test
    fun `delete forgets the key`() {
        val store = BouncyCastleDealKeyStore()
        store.ensurePublicKey("deal-A")
        store.delete("deal-A")
        assertNull(store.publicKey("deal-A"))
    }
}
