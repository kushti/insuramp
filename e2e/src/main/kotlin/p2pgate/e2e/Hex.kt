package p2pgate.e2e

import java.util.Locale

/** Base16 codec with case-insensitive decoding; encodes lowercase. */
object Hex {
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
