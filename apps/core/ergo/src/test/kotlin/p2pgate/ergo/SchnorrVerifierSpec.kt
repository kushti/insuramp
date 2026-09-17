package p2pgate.ergo

import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM port of the t/3407 Schnorr verification (`specs/vault-contract.md` §5),
 * cross-checked against the contracts module's reference signer
 * (`contracts/.../Schnorr.kt`, copied here as [RefSchnorr] for the dynamic
 * rows) and against three fixed vectors generated with that signer. The
 * adversarial rows mirror the contracts suite's path-B rejection cases
 * (matrix tests 38–41): wrong key, tampered record bytes in every field
 * region, `z = n`, sign-bit-set `z`, and an off-curve nonce point.
 */
class SchnorrVerifierSpec {

    // Fixed vectors, generated with contracts/src/test/.../Schnorr.kt semantics
    // (e = blake2b256(a ‖ msg ‖ pubKey) signed, z ground to ≤254 bits).
    private data class Vector(val secretHex: String, val pubHex: String, val msgHex: String, val aHex: String, val zHex: String)

    private val vectors = listOf(
        Vector(
            "a11ce",
            "02a64db41e2968c849c2a5615ba0d6e816734a6d3e6ea6ecd6f3acb7d59daa9102",
            "5032504801b174dd2f351fce3e7b1243167ec0390e3df14fd31cc53f4076501ec4d1fa18c3000000000003d0904547506553f10045f98e4f7666f222afa84d3681d33130d49588b0c4ca2ddf50bdc859f6ae1cb0",
            "02426254c6e45d80e49dc3ee9d5a7e5908d772cfa7eb49b57e81b26bf4bb3e72b9",
            "0dff9ec963da430859424120c2cfa01414a5be3b6f4e24df383a6171916cf5a3",
        ),
        Vector(
            "b0b",
            "035d45cb81aa765d69ca52e3869491ecf0e8fdf6a63d64e65b5213647ee4973ae5",
            "50325048011129961d054b0bd933513b4ffea8a6bdca7d9cfe099af90ff12123d6c44009b300000000000f3e584547506553f5d245f98e4f7666f222afa84d3681d33130d49588b0c4ca2ddf50bdc859f6ae1cb0",
            "02eecb3520e680d02410277472b34401c991ade5b91628421a1e20e347f4a60889",
            "3c2b5c060dc431b72fe4ef0316dbc6693cb16d367c3020d67911b038742d0337",
        ),
        Vector(
            "c0ffee",
            "032a5bbcb0eede528e6abe5f2ec50ad7887eb5677af383a460b05ee23bf892dfe5",
            "5032504801a8fdf2b20b05d73a2cbb88771a9ec72da86b0ca8ce3442be158af24bcfdcb94b000000000003d0905553446553f9ae45f98e4f7666f222afa84d3681d33130d49588b0c4ca2ddf50bdc859f6ae1cb0",
            "03612383972b32ffd9d69bd8abde300a8e559d1225663e027845b9b76e6708fe8d",
            "1d993c5b27081762323cfd84887dac5997a2f77c97afcd7ace3903b7209c98a3",
        ),
    )

    private fun vmsg(v: Vector) = Base16.decode(v.msgHex)
    private fun vkey(v: Vector) = Base16.decode(v.pubHex)

    // ---------- fixed vectors ----------

    @Test
    fun `fixed vectors verify`() {
        for (v in vectors) {
            assertTrue(SchnorrVerifier.verify(vmsg(v), Base16.decode(v.aHex), Base16.decode(v.zHex), vkey(v)), "vector ${v.secretHex}")
        }
    }

    @Test
    fun `fixed vectors cross-check against the reference signer equations`() {
        // Independently recompute e and the verification equation per §5.
        val spec = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1")
        val params = org.bouncycastle.crypto.params.ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
        for (v in vectors) {
            val a = Base16.decode(v.aHex)
            val z = Base16.decode(v.zHex)
            val pk = params.curve.decodePoint(vkey(v))
            val nonce = params.curve.decodePoint(a)
            val e = BigInteger(SchnorrVerifier.blake2b256(a, vmsg(v), vkey(v)))
            val lhs = params.g.multiply(BigInteger(z)).normalize()
            val rhs = nonce.add(pk.multiply(e)).normalize()
            assertTrue(lhs == rhs, "equation recompute for ${v.secretHex}")
        }
    }

    // ---------- dynamic cross-check against the reference signer ----------

    @Test
    fun `signatures from the reference signer verify under random keys and records`() {
        val terms = ErgoTestFixtures.dealTerms()
        repeat(5) { i ->
            val keys = TestKeys.random()
            val record = ErgoTestFixtures.handoffRecord(terms, tsSec = 1_700_000_000L + i)
            val sig = RefSchnorr.sign(keys.secret, record.encode(), keys.pubKeyCompressed)
            assertTrue(SchnorrVerifier.verify(record.encode(), sig.a, sig.z, keys.pubKeyCompressed))
        }
    }

    // ---------- adversarial rows (mirror contracts matrix tests 38-41) ----------

    @Test
    fun `wrong key fails`() {
        val v = vectors[0]
        val other = TestKeys.of(0x9999)
        assertFalse(SchnorrVerifier.verify(vmsg(v), Base16.decode(v.aHex), Base16.decode(v.zHex), other.pubKeyCompressed))
    }

    @Test
    fun `tampered record byte in every field region fails`() {
        val v = vectors[0]
        // Region boundaries per specs/deal-protocol.md §3.2: magic 0..3, version 4,
        // dealId 5..36, amount 37..44, currency 45..47, timestamp 48..51, courierIdHash 52..83.
        val regions = listOf(0, 4, 10, 37, 45, 48, 60, 83)
        for (idx in regions) {
            val tampered = vmsg(v).copyOf().also { it[idx] = (it[idx].toInt() xor 0x01).toByte() }
            assertFalse(
                SchnorrVerifier.verify(tampered, Base16.decode(v.aHex), Base16.decode(v.zHex), vkey(v)),
                "tampered byte at $idx must fail",
            )
        }
    }

    @Test
    fun `z equal to the group order fails`() {
        val v = vectors[0]
        val spec = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1")
        // n is 32 bytes big-endian with the top bit set — negative under signed
        // two's-complement byteArrayToBigInt, so g^z != a·pk^e (contracts test 39).
        val src = spec.n.toByteArray()
        val zN = ByteArray(32)
        System.arraycopy(src, src.size - 32, zN, 0, 32)
        assertFalse(SchnorrVerifier.verify(vmsg(v), Base16.decode(v.aHex), zN, vkey(v)))
    }

    @Test
    fun `honest z with its sign bit set fails`() {
        val v = vectors[1]
        val z = Base16.decode(v.zHex).copyOf()
        z[0] = (z[0].toInt() or 0x80).toByte()
        assertFalse(SchnorrVerifier.verify(vmsg(v), Base16.decode(v.aHex), z, vkey(v)))
    }

    @Test
    fun `off-curve nonce point fails without throwing`() {
        val v = vectors[0]
        val badA = byteArrayOf(0x05) + ByteArray(32) { 0x01 } // invalid compressed prefix
        assertFalse(SchnorrVerifier.verify(vmsg(v), badA, Base16.decode(v.zHex), vkey(v)))
        val offCurve = byteArrayOf(0x02) + ByteArray(32) { 0xff.toByte() }
        assertFalse(SchnorrVerifier.verify(vmsg(v), offCurve, Base16.decode(v.zHex), vkey(v)))
    }

    @Test
    fun `size violations fail`() {
        val v = vectors[0]
        assertFalse(SchnorrVerifier.verify(vmsg(v), Base16.decode(v.aHex), Base16.decode(v.zHex) + 0x00, vkey(v)))
        assertFalse(SchnorrVerifier.verify(vmsg(v), Base16.decode(v.aHex).copyOf(32), Base16.decode(v.zHex), vkey(v)))
        assertFalse(SchnorrVerifier.verify(vmsg(v), Base16.decode(v.aHex), Base16.decode(v.zHex), vkey(v).copyOf(32)))
    }
}
