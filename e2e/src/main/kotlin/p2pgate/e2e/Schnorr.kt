package p2pgate.e2e

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import p2pgate.dealprotocol.Blake2b256
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Ports of the reference helpers the suite keeps in test fixtures
 * (`contracts/src/test/kotlin/p2pgate/contracts/Schnorr.kt`,
 * `apps/core/ergo/src/test/kotlin/p2pgate/ergo/RefSchnorr.kt`) — the e2e
 * module must not depend on test fixtures, so the ~40-line t/3407 signing
 * routine lives here in main sources.
 */
object Schnorr {
    private val spec = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
    private val n: BigInteger = params.n
    private val g = params.g
    private val rand = SecureRandom()

    class Signature(val a: ByteArray, val z: ByteArray)

    fun publicKey(secret: BigInteger): ByteArray =
        g.multiply(secret).normalize().getEncoded(true)

    /**
     * The ergoforum.org/t/3407 Schnorr half the vault's path B verifies
     * in-script (`specs/vault-contract.md` §5): challenge
     * `e = blake2b256(a ‖ msg ‖ pubkey)` read as a signed big integer,
     * response `z = r + e·secret mod n`, retried until `z` fits 32 bytes.
     */
    fun sign(secret: BigInteger, msg: ByteArray, pubKeyCompressed: ByteArray): Signature {
        while (true) {
            val r = BigInteger(n.bitLength(), rand).mod(n)
            if (r.signum() == 0) continue
            val aBytes = g.multiply(r).normalize().getEncoded(true)
            val eBytes = Blake2b256.digest(aBytes, msg, pubKeyCompressed)
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
class Keys(val secret: BigInteger, val pubKeyCompressed: ByteArray) {
    companion object {
        private val spec = CustomNamedCurves.getByName("secp256k1")
        private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)

        fun random(): Keys {
            val gen = ECKeyPairGenerator()
            gen.init(ECKeyGenerationParameters(params, SecureRandom()))
            val kp = gen.generateKeyPair()
            val priv = kp.private as ECPrivateKeyParameters
            val pub = kp.public as ECPublicKeyParameters
            return Keys(priv.d, pub.q.normalize().getEncoded(true))
        }
    }
}
