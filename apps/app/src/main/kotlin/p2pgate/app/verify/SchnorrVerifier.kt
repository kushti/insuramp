package p2pgate.app.verify

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger

/**
 * Android port of `apps/core/ergo/.../SchnorrVerifier.kt` (itself the verify
 * side of the contracts module's reference signer) — the ergoforum.org/t/3407
 * Schnorr variant the vault's path B verifies in-script (`specs/vault-contract.md`
 * §5):
 *
 * ```
 * e     = blake2b256(aEncoded ++ msg ++ pubKeyEncoded), read as a *signed*
 *         two's-complement big-endian integer (exactly ErgoScript's byteArrayToBigInt)
 * check = g^z == a · pk^e        (z read as signed two's-complement, as in-script)
 * ```
 *
 * **Why ported instead of depended upon:** `:app` must not depend on
 * `:apps:core:ergo` (appkit/scala does not survive Android desugaring), so the
 * minimum verify path is copied here. The logic is byte-identical to the JVM
 * original — same curve, same hash construction, same signed two's-complement
 * reads, same never-throws contract; the app's unit tests re-run the fixed
 * vectors from the :core:ergo suite against this port. BouncyCastle
 * `bcprov-jdk18on` is plain JVM bytecode and needs no spongycastle swap on
 * Android (minSdk 26, API-desugaring-free).
 */
object SchnorrVerifier {

    private val spec = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)

    /**
     * Verifies the record signature over [msg] (the 52-byte P2PH record).
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
