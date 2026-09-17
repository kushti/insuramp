package p2pgate.dealprotocol

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Known-answer tests for the pure-Kotlin BLAKE2b-256 (RFC 7693), plus a
 * property test comparing 200 deterministic pseudo-random inputs against
 * BouncyCastle's `Blake2b-256` (reference oracle, test dependency only).
 *
 * Hardcoded vectors: `""` and `"abc"` are the RFC 7693 §2.4.2 values for
 * BLAKE2b-256 (parameter block digestLength=32). The 256-byte vector uses
 * the RFC's §2.4.2 input pattern (bytes 0x00..0xff, the "in" column of the
 * 256-byte row) under the 256-bit parameterization — the RFC table itself
 * only publishes the 512-bit digests, so the expected value was generated
 * independently (Python `hashlib.blake2b(..., digest_size=32)`) and
 * hardcoded. The 1000×'x' vector is another independently generated
 * long-input reference.
 */
class Blake2b256Spec {

    @Test
    fun `empty input matches RFC 7693`() {
        val expected = "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8"
        assertEquals(expected, Blake2b256.digest(ByteArray(0)).toHex())
    }

    @Test
    fun `abc matches RFC 7693`() {
        val expected = "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319"
        assertEquals(expected, Blake2b256.digest("abc".encodeToByteArray()).toHex())
    }

    @Test
    fun `256-byte RFC 7693 input pattern`() {
        val input = ByteArray(256) { it.toByte() }
        val expected = "39a7eb9fedc19aabc83425c6755dd90e6f9d0c804964a1f4aaeea3b9fb599835"
        assertEquals(expected, Blake2b256.digest(input).toHex())
    }

    @Test
    fun `1000 x long input`() {
        val input = ByteArray(1000) { 'x'.code.toByte() }
        val expected = "ab75119ede14ef06ebf31f745fb655ed006cccfe8054c635308a557f7c9beaba"
        assertEquals(expected, Blake2b256.digest(input).toHex())
    }

    @Test
    fun `digest is always 32 bytes`() {
        assertEquals(32, Blake2b256.digest(ByteArray(0)).size)
        assertEquals(32, Blake2b256.digest(ByteArray(128)).size)
        assertEquals(32, Blake2b256.digest(ByteArray(129)).size)
    }

    @Test
    fun `multi-input digest equals digest of the concatenation`() {
        val a = ByteArray(100) { (it * 7 % 256).toByte() }
        val b = ByteArray(57) { (it * 13 % 256).toByte() }
        val joined = a + b
        assertContentEquals(Blake2b256.digest(joined), Blake2b256.digest(a, b))
        // boundary straddling: 100 + 57 crosses the 128-byte block edge
        assertContentEquals(Blake2b256.digest(joined), Blake2b256.digest(a.copyOfRange(0, 60), a.copyOfRange(60, 100), b))
    }

    @Test
    fun `matches BouncyCastle on 200 deterministic pseudo-random inputs`() {
        val random = Random(42)
        val reference = Blake2bDigest(256)
        repeat(200) { iteration ->
            val length = when {
                iteration < 8 -> iteration // 0..7: tiny inputs incl. empty
                else -> random.nextInt(0, 1024)
            }
            val input = ByteArray(length) { random.nextBytes(1)[0] }
            reference.reset()
            reference.update(input, 0, input.size)
            val expected = ByteArray(32)
            reference.doFinal(expected, 0)
            assertContentEquals(expected, Blake2b256.digest(input), "mismatch at iteration $iteration (length $length)")
        }
    }
}
