package p2pgate.e2e

import org.junit.jupiter.api.Test
import p2pgate.dealprotocol.Blake2b256
import p2pgate.ergo.ChainBox
import p2pgate.ergo.SchnorrVerifier
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The ported t/3407 signer must interop with the core module's in-script verifier. */
class SchnorrPortSpec {

    @Test
    fun `ported signer verifies against the core SchnorrVerifier`() {
        val keys = Keys.random()
        val msg = Blake2b256.digest("e2e handoff record".encodeToByteArray())
        val sig = Schnorr.sign(keys.secret, msg, keys.pubKeyCompressed)
        assertTrue(SchnorrVerifier.verify(msg, sig.a, sig.z, keys.pubKeyCompressed))
    }

    @Test
    fun `signature is bound to the message and the key`() {
        val keys = Keys.random()
        val msg = Blake2b256.digest("message one".encodeToByteArray())
        val sig = Schnorr.sign(keys.secret, msg, keys.pubKeyCompressed)
        // Different message: the challenge changes, verification fails.
        val other = Blake2b256.digest("message two".encodeToByteArray())
        kotlin.test.assertFalse(SchnorrVerifier.verify(other, sig.a, sig.z, keys.pubKeyCompressed))
        // Different key: the pubkey is hashed into the challenge.
        val otherKeys = Keys.random()
        assertTrue(SchnorrVerifier.verify(msg, sig.a, sig.z, keys.pubKeyCompressed))
        kotlin.test.assertFalse(SchnorrVerifier.verify(msg, sig.a, sig.z, otherKeys.pubKeyCompressed))
    }

    @Test
    fun `public key helper matches scalar multiplication`() {
        val secret = BigInteger.valueOf(0x4242)
        val pub = Schnorr.publicKey(secret)
        assertEquals(33, pub.size)
        assertTrue(pub[0].toInt() == 0x02 || pub[0].toInt() == 0x03)
    }
}
