package p2pgate.e2e

import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.JavaHelpers
import p2pgate.ergo.ChainRegister
import sigma.ast.ErgoTree
import sigma.serialization.ValueSerializer

/**
 * Conversions between plain values and sigma/appkit representations — ports of
 * `:apps:core:ergo`'s internal `ErgoValues`/`Base16` (not visible across the
 * module boundary), same wire-exact guarantees: register bytes served by
 * explorers round-trip unchanged when boxes are re-serialized into txs.
 */
object Wire {
    /** Sigma-serialized bytes of a Coll[Byte] constant (explorer `additionalRegisters` hex). */
    fun serializedBytes(value: ByteArray): ByteArray = Hex.decode(ErgoValue.of(value).toHex())

    /** Sigma-serialized bytes of a Long constant. */
    fun serializedBytes(value: Long): ByteArray = Hex.decode(ErgoValue.of(value).toHex())

    /** Decodes sigma-serialized register bytes (as served by explorers) to a typed value. */
    fun decodeRegister(serialized: ByteArray): ChainRegister {
        val v = ValueSerializer.deserialize(serialized, 0) as sigma.ast.EvaluatedValue<out sigma.ast.SType>
        return when (val decoded = v.value()) {
            is java.lang.Long -> ChainRegister.Int64(decoded.toLong())
            is sigma.Coll<*> -> ChainRegister.CollBytes(JavaHelpers.collToByteArray(decoded as sigma.Coll<Any>))
            else -> throw IllegalArgumentException(
                "unsupported register constant type ${decoded?.javaClass?.simpleName} — only Coll[Byte] and Long are supported",
            )
        }
    }

    fun decodeRegister(serializedHex: String): ChainRegister = decodeRegister(Hex.decode(serializedHex))

    /** secp256k1 curve point from 33-byte compressed encoding (throws on off-curve bytes). */
    fun decodePoint(compressed: ByteArray): sigma.crypto.Platform.Ecp =
        sigma.crypto.CryptoContext.default().decodePoint(compressed)

    /** P2PK ErgoTree for a compressed secp256k1 public key (proveDlog(decodePoint(pk))). */
    fun p2pkTree(compressedPubKey: ByteArray): ErgoTree =
        ErgoTree.fromSigmaBoolean(sigma.data.ProveDlog.apply(decodePoint(compressedPubKey)))

    fun treeHex(tree: ErgoTree): String = Hex.encode(tree.bytes())
}

