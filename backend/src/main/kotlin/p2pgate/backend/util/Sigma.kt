package p2pgate.backend.util

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.NetworkType
import sigma.ast.ErgoTree
import java.math.BigInteger

/**
 * secp256k1 key helpers (BouncyCastle) — mirrors `DevOracle.publicKey`, kept
 * local because the ergo module's helpers are internal.
 */
object Secp256k1 {
    private val spec = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)

    /** 33-byte compressed secp256k1 point of [secret]. */
    fun publicKeyCompressed(secret: BigInteger): ByteArray =
        params.g.multiply(secret).normalize().getEncoded(true)
}

/**
 * Direct sigma interop for the small set of tree operations the backend needs
 * (test/dev P2PK derivations).
 * `:apps:core:ergo`'s `ErgoValues` is internal; these helpers duplicate its
 * three public-facing operations against the same sigma APIs.
 */
object SigmaTrees {
    private fun decodePoint(compressed: ByteArray) =
        sigma.crypto.CryptoContext.default().decodePoint(compressed)

    /** `proveDlog(decodePoint(pk))` ErgoTree for a compressed secp256k1 key. */
    fun p2pkTree(compressedPubKey: ByteArray): ErgoTree =
        ErgoTree.fromSigmaBoolean(sigma.data.ProveDlog.apply(decodePoint(compressedPubKey)))

    fun p2pkTreeHex(compressedPubKey: ByteArray): String = Hex.encode(p2pkTree(compressedPubKey).bytes())

    fun p2pkAddress(compressedPubKey: ByteArray, networkType: NetworkType): String =
        Address.fromSigmaBoolean(
            sigma.data.ProveDlog.apply(decodePoint(compressedPubKey)),
            networkType,
        ).toString()
}
