package p2pgate.contracts

import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Schnorr signer matching the in-contract verification in vault_payment_proven.es /
 * vault_funded.es (ergoforum.org/t/3407 variant):
 *
 *   e     = blake2b256(aEncoded ++ msg ++ pubKeyEncoded), read as a *signed* two's-complement
 *           big-endian integer (exactly what ErgoScript's byteArrayToBigInt does)
 *   z     = (r + e * x) mod n
 *   check = g^z == a * Y^e
 *
 * The nonce r is ground until z fits 254 bits so its 32-byte encoding is always
 * positive under two's-complement (top sign bit clear) — same convention as the
 * reference snippet in the forum thread.
 */
object Schnorr {

    private val spec = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
    private val n: BigInteger = params.n
    private val g = params.g
    private val rand = SecureRandom()

    class Signature(val a: ByteArray, val z: ByteArray)

    fun sign(secret: BigInteger, msg: ByteArray, pubKeyCompressed: ByteArray): Signature {
        while (true) {
            val r = BigInteger(n.bitLength(), rand).mod(n)
            if (r.signum() == 0) continue
            val aBytes = g.multiply(r).normalize().getEncoded(true)
            val eBytes = SigmaBridge.blake2b256(aBytes, msg, pubKeyCompressed)
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
