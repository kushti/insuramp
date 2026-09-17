package p2pgate.dealprotocol

/** Test-only hex helpers (kotlin.text.HexFormat is not in the 2.0 stdlib). */

internal fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
