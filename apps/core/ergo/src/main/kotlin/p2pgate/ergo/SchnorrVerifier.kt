package p2pgate.ergo

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.digests.Blake2bDigest
import java.math.BigInteger

/**
 * JVM verifier for the ergoforum.org/t/3407 Schnorr variant used in-script by
 * vault path B (`specs/vault-contract.md` §5) — a port of the reference signer
 * in `contracts/src/test/kotlin/p2pgate/contracts/Schnorr.kt` to the verify side:
 *
 * ```
 * e     = blake2b256(aEncoded ++ msg ++ pubKeyEncoded), read as a *signed*
 *         two's-complement big-endian integer (exactly ErgoScript's byteArrayToBigInt)
 * check = g^z == a · pk^e        (z read as signed two's-complement, as in-script)
 * ```
 *
 * No sigma transaction layer is involved — plain BouncyCastle secp256k1, so the
 * meeting-time "safe to leave" check runs on-device without the proving stack.
 */
object SchnorrVerifier {

    private val spec = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)

    /**
     * Verifies the courier half over [msg] (the 84-byte P2PH record).
     * Returns `false` (never throws) on malformed points, wrong sizes, or a
     * failing equation — mirroring the contract, where a `decodePoint` throw
     * rejects the spend.
     */
    fun verify(msg: ByteArray, a: ByteArray, z: ByteArray, pubKey: ByteArray): Boolean = try {
        require(a.size == 33) { "nonce point must be 33 bytes (compressed), got ${a.size}" }
        require(z.size == 32) { "response must be 32 bytes, got ${z.size}" }
        require(pubKey.size == 33) { "public key must be 33 bytes (compressed), got ${pubKey.size}" }
        val nonce = params.curve.decodePoint(a)
        val pk = params.curve.decodePoint(pubKey)
        val e = BigInteger(blake2b256(a, msg, pubKey)) // signed two's-complement
        val zScalar = BigInteger(z)                     // signed two's-complement, as byteArrayToBigInt
        val lhs = params.g.multiply(zScalar).normalize()
        val rhs = nonce.add(pk.multiply(e)).normalize()
        lhs == rhs
    } catch (e: IllegalArgumentException) {
        false
    }

    /** Ergo's blake2b-256 over the concatenated parts (BouncyCastle, 256-bit digest). */
    internal fun blake2b256(vararg parts: ByteArray): ByteArray {
        val d = Blake2bDigest(256)
        for (p in parts) d.update(p, 0, p.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }
}
