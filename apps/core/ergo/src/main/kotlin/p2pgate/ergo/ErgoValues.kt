package p2pgate.ergo

import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.JavaHelpers
import sigma.ast.ErgoTree
import sigma.serialization.ValueSerializer
import java.util.Locale

/**
 * Conversions between plain Kotlin values and sigma/appkit representations —
 * the only places the module crosses from `ChainSource`'s plain shapes into
 * the JVM chain stack. Kept small and side-effect free; register wire bytes
 * round-trip exactly (what explorers serve is what builders re-emit).
 */
internal object ErgoValues {

    /** Sigma-serialized bytes of a Coll[Byte] constant (explorer `additionalRegisters` hex). */
    fun serializedBytes(value: ByteArray): ByteArray = Base16.decode(ErgoValue.of(value).toHex())

    /** Sigma-serialized bytes of a Long constant (explorer `additionalRegisters` hex). */
    fun serializedBytes(value: Long): ByteArray = Base16.decode(ErgoValue.of(value).toHex())

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

    fun decodeRegister(serializedHex: String): ChainRegister = decodeRegister(Base16.decode(serializedHex))

    /** The sigma constant for a raw Coll[Byte] value (registers R4–R7, R8, R9). */
    fun collBytesConstant(value: ByteArray): sigma.ast.EvaluatedValue<out sigma.ast.SType> =
        sigma.ast.ByteArrayConstant.apply(value)

    /** The sigma constant for a Long value (packed R7/R8 registers). */
    fun longConstant(value: Long): sigma.ast.EvaluatedValue<out sigma.ast.SType> =
        sigma.ast.LongConstant.apply(value)

    /** secp256k1 curve point from 33-byte compressed encoding (throws on off-curve bytes). */
    fun decodePoint(compressed: ByteArray): sigma.crypto.Platform.Ecp =
        sigma.crypto.CryptoContext.default().decodePoint(compressed)

    fun encodePoint(point: sigma.crypto.Platform.Ecp, compressed: Boolean = true): ByteArray =
        sigma.crypto.Platform.getASN1Encoding(point, compressed)

    /** P2PK ErgoTree for a compressed secp256k1 public key (proveDlog(decodePoint(pk))). */
    fun p2pkTree(compressedPubKey: ByteArray): ErgoTree =
        ErgoTree.fromSigmaBoolean(sigma.data.ProveDlog.apply(decodePoint(compressedPubKey)))

    fun treeHex(tree: ErgoTree): String = Base16.encode(tree.bytes())
}

/** Base16 codec with case-insensitive decoding; encodes lowercase. */
internal object Base16 {
    private val HEX = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            out[i * 2] = HEX[(bytes[i].toInt() shr 4) and 0xf]
            out[i * 2 + 1] = HEX[bytes[i].toInt() and 0xf]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length, got ${hex.length}" }
        val h = hex.lowercase(Locale.ROOT)
        val out = ByteArray(h.length / 2)
        for (i in out.indices) {
            out[i] = ((h[2 * i].digitToInt(16) shl 4) or h[2 * i + 1].digitToInt(16)).toByte()
        }
        return out
    }
}
