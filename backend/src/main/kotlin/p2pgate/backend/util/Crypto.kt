package p2pgate.backend.util

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Base16 codec (lowercase on encode, case-insensitive on decode) — the backend
 * keeps its own copy because `:apps:core:ergo`'s `Base16` is internal to that
 * module; the wire shapes are identical.
 */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            out[i * 2] = DIGITS[(bytes[i].toInt() shr 4) and 0xf]
            out[i * 2 + 1] = DIGITS[bytes[i].toInt() and 0xf]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length, got ${hex.length}" }
        val h = hex.lowercase()
        return ByteArray(h.length / 2) { i ->
            ((h[2 * i].digitToInt(16) shl 4) or h[2 * i + 1].digitToInt(16)).toByte()
        }
    }
}

/** Small crypto helpers shared by the store, auth and AML layers. */
object Crypto {
    private val random = SecureRandom()

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = Hex.encode(sha256(bytes))

    /** BLAKE2b-256 (BouncyCastle — the same primitive the chain stack uses). */
    fun blake2b256(bytes: ByteArray): ByteArray {
        val digest = org.bouncycastle.crypto.digests.Blake2bDigest(256)
        digest.update(bytes, 0, bytes.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    /** Random 32-byte token, hex-encoded (deal / courier / operator bearer tokens). */
    fun secureToken(): String {
        val raw = ByteArray(32)
        random.nextBytes(raw)
        return Hex.encode(raw)
    }
}
