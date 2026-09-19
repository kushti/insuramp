package p2pgate.backend.util

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Sign half of the ergoforum.org/t/3407 Schnorr variant verified in-script by
 * vault path B (`specs/vault-contract.md` §5) — the mirror of
 * `:apps:core:ergo`'s `SchnorrVerifier` (verify side) and a port of the
 * reference signer in `contracts/src/test/kotlin/p2pgate/contracts/Schnorr.kt`:
 *
 * ```
 * e     = blake2b256(aEncoded ++ msg ++ pubKeyEncoded), read as a *signed*
 *         two's-complement big-endian integer (exactly ErgoScript's byteArrayToBigInt)
 * z     = (r + e * x) mod n
 * check = g^z == a * pk^e        (z read as signed two's-complement, as in-script)
 * ```
 *
 * The nonce r is ground until z fits 254 bits so its 32-byte encoding is always
 * positive under two's-complement (top sign bit clear) — same convention as the
 * reference snippet in the forum thread. Plain BouncyCastle secp256k1; no sigma
 * transaction layer is involved.
 */
object Schnorr {

    private val spec = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
    private val n: BigInteger = params.n
    private val g = params.g
    private val rand = SecureRandom()

    /** A t/3407 signature: [a] the 33-byte compressed nonce point, [z] the 32-byte response. */
    class Signature(val a: ByteArray, val z: ByteArray)

    /**
     * Signs [msg] (the 52-byte P2PH handoff record) under [secret]; the
     * matching compressed public key must be passed as [pubKeyCompressed]
     * (it feeds the challenge hash).
     */
    fun sign(secret: BigInteger, msg: ByteArray, pubKeyCompressed: ByteArray): Signature {
        require(secret.signum() > 0 && secret < n) { "secret must be a secp256k1 scalar" }
        require(pubKeyCompressed.size == 33) { "public key must be 33 bytes (compressed)" }
        while (true) {
            val r = BigInteger(n.bitLength(), rand).mod(n)
            if (r.signum() == 0) continue
            val aBytes = g.multiply(r).normalize().getEncoded(true)
            val e = BigInteger(blake2b256(aBytes, msg, pubKeyCompressed)) // signed two's-complement
            val z = r.add(e.multiply(secret)).mod(n)
            if (z.bitLength() <= 254) {
                return Signature(aBytes, z.toFixed(32))
            }
        }
    }

    /** Ergo's blake2b-256 over the concatenated parts (BouncyCastle, 256-bit digest). */
    private fun blake2b256(vararg parts: ByteArray): ByteArray {
        val d = Blake2bDigest(256)
        for (p in parts) d.update(p, 0, p.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }

    /** Left-padded big-endian encoding, exactly `byteArrayToBigInt`'s inverse width. */
    private fun BigInteger.toFixed(len: Int): ByteArray {
        val raw = toByteArray()
        val out = ByteArray(len)
        val copyLen = minOf(raw.size, len)
        System.arraycopy(raw, raw.size - copyLen, out, len - copyLen, copyLen)
        return out
    }
}
