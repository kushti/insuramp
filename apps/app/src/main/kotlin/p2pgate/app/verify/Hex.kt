package p2pgate.app.verify

/** Minimal hex codec (the JVM modules keep theirs internal to :core:ergo). */
object Hex {
    private val digits = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append(digits[(b.toInt() shr 4) and 0xf])
            append(digits[b.toInt() and 0xf])
        }
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "not a hex string: $hex" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
