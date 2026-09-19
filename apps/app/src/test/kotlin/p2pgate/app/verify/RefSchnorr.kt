package p2pgate.app.verify

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Test-only reference signer — a copy of the contracts module's
 * `contracts/src/test/kotlin/p2pgate/contracts/Schnorr.kt` (the t/3407
 * variant the vault verifies in-script), same as the copy the :core:ergo
 * suite uses. Production code in :app verifies, never signs.
 */
object RefSchnorr {

    private val spec = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
    private val n: BigInteger = params.n
    private val g = params.g
    private val rand = SecureRandom()

    class Signature(val a: ByteArray, val z: ByteArray)

    fun publicKey(secret: BigInteger): ByteArray =
        g.multiply(secret).normalize().getEncoded(true)

    fun sign(secret: BigInteger, msg: ByteArray, pubKeyCompressed: ByteArray): Signature {
        while (true) {
            val r = BigInteger(n.bitLength(), rand).mod(n)
            if (r.signum() == 0) continue
            val aBytes = g.multiply(r).normalize().getEncoded(true)
            val eBytes = SchnorrVerifier.blake2b256(aBytes, msg, pubKeyCompressed)
            val e = BigInteger(eBytes) // signed two's-complement, matching byteArrayToBigInt
            val z = r.add(e.multiply(secret)).mod(n)
            if (z.bitLength() <= 254) {
                return Signature(aBytes, z.toFixed(32))
            }
        }
    }

    private fun BigInteger.toFixed(len: Int): ByteArray {
        val raw = toByteArray()
        val out = ByteArray(len)
        val copyLen = minOf(raw.size, len)
        System.arraycopy(raw, raw.size - copyLen, out, len - copyLen, copyLen)
        return out
    }
}

/** A secp256k1 keypair as the wire formats carry it (33-byte compressed point). */
class TestKeys(val secret: BigInteger, val pubKeyCompressed: ByteArray) {
    companion object {
        private val spec = CustomNamedCurves.getByName("secp256k1")
        private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)

        fun random(): TestKeys {
            val gen = ECKeyPairGenerator()
            gen.init(ECKeyGenerationParameters(params, SecureRandom()))
            val kp = gen.generateKeyPair()
            val priv = kp.private as ECPrivateKeyParameters
            val pub = kp.public as ECPublicKeyParameters
            return TestKeys(priv.d, pub.q.normalize().getEncoded(true))
        }

        /** Deterministic key from a small integer — reproducible fixtures. */
        fun of(d: Long): TestKeys {
            val secret = BigInteger.valueOf(d)
            return TestKeys(secret, RefSchnorr.publicKey(secret))
        }
    }
}
