package p2pgate.app.verify

import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android port of the t/3407 Schnorr verification, pinned by the SAME
 * fixed vectors as `apps/core/ergo/.../SchnorrVerifierSpec.kt` (generated with
 * the contracts module's reference signer) plus the same adversarial rows
 * (wrong key, tampered record bytes in every field region, `z = n`,
 * sign-bit-set `z`, off-curve nonce). Green here proves the port is
 * byte-identical to the JVM original.
 */
class SchnorrPortSpec {

    private data class Vector(val secretHex: String, val pubHex: String, val msgHex: String, val aHex: String, val zHex: String)

    private val vectors = listOf(
        Vector(
            "a11ce",
            "02a64db41e2968c849c2a5615ba0d6e816734a6d3e6ea6ecd6f3acb7d59daa9102",
            "5032504801b174dd2f351fce3e7b1243167ec0390e3df14fd31cc53f4076501ec4d1fa18c3000000000003d0904547506553f100",
            "037e893c1f91dfce6946167fc86b50fe4f9ac8eec68b224a356ef68791840cad34",
            "1d4cedaa8721711617cd25ad0cacbb21fc24ad0f50caac49453c07606b6a310f",
        ),
        Vector(
            "b0b",
            "035d45cb81aa765d69ca52e3869491ecf0e8fdf6a63d64e65b5213647ee4973ae5",
            "50325048011129961d054b0bd933513b4ffea8a6bdca7d9cfe099af90ff12123d6c44009b300000000000f3e584547506553f5d2",
            "0245604c3929004b392481277bfb7025fbc22a667b7b45cecde6b94950fa36e491",
            "27d00e7a44af83b3ad8910f3a60a914dd00fd4ff58f2637d7dff7b151ee35ff8",
        ),
        Vector(
            "c0ffee",
            "032a5bbcb0eede528e6abe5f2ec50ad7887eb5677af383a460b05ee23bf892dfe5",
            "5032504801a8fdf2b20b05d73a2cbb88771a9ec72da86b0ca8ce3442be158af24bcfdcb94b000000000003d0905553446553f9ae",
            "02149bab37e4dafc65e98d3fa6040ea5d21b94346efb41c492bd8b1d1cbca02ef4",
            "349f2256d0196f623781ce7bfddc43dce09400302f9523e3f5de26963623ee58",
        ),
    )

    private fun vmsg(v: Vector) = Hex.decode(v.msgHex)
    private fun vkey(v: Vector) = Hex.decode(v.pubHex)

    @Test
    fun `fixed vectors from the core suite verify under the port`() {
        for (v in vectors) {
            assertTrue(SchnorrVerifier.verify(vmsg(v), Hex.decode(v.aHex), Hex.decode(v.zHex), vkey(v)), "vector ${v.secretHex}")
        }
    }

    @Test
    fun `wrong key fails`() {
        val v = vectors[0]
        val other = TestKeys.of(0x9999)
        assertFalse(SchnorrVerifier.verify(vmsg(v), Hex.decode(v.aHex), Hex.decode(v.zHex), other.pubKeyCompressed))
    }

    @Test
    fun `tampered record byte in every field region fails`() {
        val v = vectors[0]
        val regions = listOf(0, 4, 10, 37, 45, 48, 51)
        for (idx in regions) {
            val tampered = vmsg(v).copyOf().also { it[idx] = (it[idx].toInt() xor 0x01).toByte() }
            assertFalse(
                SchnorrVerifier.verify(tampered, Hex.decode(v.aHex), Hex.decode(v.zHex), vkey(v)),
                "tampered byte at $idx must fail",
            )
        }
    }

    @Test
    fun `z equal to the group order fails`() {
        val v = vectors[0]
        val spec = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1")
        val src = spec.n.toByteArray()
        val zN = ByteArray(32)
        System.arraycopy(src, src.size - 32, zN, 0, 32)
        assertFalse(SchnorrVerifier.verify(vmsg(v), Hex.decode(v.aHex), zN, vkey(v)))
    }

    @Test
    fun `honest z with its sign bit set fails`() {
        val v = vectors[1]
        val z = Hex.decode(v.zHex).copyOf()
        z[0] = (z[0].toInt() or 0x80).toByte()
        assertFalse(SchnorrVerifier.verify(vmsg(v), Hex.decode(v.aHex), z, vkey(v)))
    }

    @Test
    fun `off-curve nonce point fails without throwing`() {
        val v = vectors[0]
        val badA = byteArrayOf(0x05) + ByteArray(32) { 0x01 }
        assertFalse(SchnorrVerifier.verify(vmsg(v), badA, Hex.decode(v.zHex), vkey(v)))
        val offCurve = byteArrayOf(0x02) + ByteArray(32) { 0xff.toByte() }
        assertFalse(SchnorrVerifier.verify(vmsg(v), offCurve, Hex.decode(v.zHex), vkey(v)))
    }

    @Test
    fun `size violations fail`() {
        val v = vectors[0]
        assertFalse(SchnorrVerifier.verify(vmsg(v), Hex.decode(v.aHex), Hex.decode(v.zHex) + 0x00, vkey(v)))
        assertFalse(SchnorrVerifier.verify(vmsg(v), Hex.decode(v.aHex).copyOf(32), Hex.decode(v.zHex), vkey(v)))
        assertFalse(SchnorrVerifier.verify(vmsg(v), Hex.decode(v.aHex), Hex.decode(v.zHex), vkey(v).copyOf(32)))
    }

    @Test
    fun `signatures from the reference signer verify under random keys and records`() {
        val seller = TestKeys.of(0x1111)
        val dealId = ByteArray(32) { (it * 3 + 1).toByte() }
        repeat(4) { i ->
            val record = p2pgate.dealprotocol.HandoffRecord(
                dealId = dealId,
                amount = 250_000L + i,
                fiatCurrency = "EGP".toByteArray(),
                timestamp = 1_700_000_000L + i,
            )
            val sig = RefSchnorr.sign(seller.secret, record.encode(), seller.pubKeyCompressed)
            assertTrue(SchnorrVerifier.verify(record.encode(), sig.a, sig.z, seller.pubKeyCompressed))
        }
    }
}
