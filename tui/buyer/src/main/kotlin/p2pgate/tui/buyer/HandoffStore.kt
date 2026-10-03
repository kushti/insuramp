package p2pgate.tui.buyer

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import p2pgate.dealprotocol.QrPayload

/**
 * The seller's signed handoff record, as the buyer holds it after the meeting.
 *
 * The buyer obtains this face to face — scanned off the seller's screen, or
 * copied from the `p2pgate://handoff?m=…` link — and it is the buyer's *evidence*
 * for a claim, so it is stored locally and never re-fetched from the backend.
 * That matters for the trust model: the record is only as good as the channel it
 * arrived over, and the channel here is a person standing in front of the buyer.
 *
 * No private key material is stored here — the record carries the *seller's*
 * signature, which is public information. The file is still written owner-only,
 * because the deal terms it contains are the buyer's own financial data.
 */
@Serializable
data class StoredHandoff(
    /** The raw record bytes, hex. */
    val recordHex: String,
    /** The seller's Schnorr signature over [recordHex]: 33-byte `a`, 32-byte `z`. */
    val signatureA: String,
    val signatureZ: String,
    /** The `p2pgate://handoff?m=…` payload as scanned, for display and re-upload. */
    val qrPayload: String,
    val capturedAtEpochMs: Long = 0,
)

/**
 * Reads and writes [StoredHandoff] at one path. Pure file I/O — deciding whether
 * a record is *acceptable* is `BuyerController.captureHandoff`'s job, and it does
 * that before anything is written.
 */
class HandoffStore(private val file: File) {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun exists(): Boolean = file.isFile && file.length() > 0

    fun read(): StoredHandoff? {
        if (!exists()) return null
        return runCatching { json.decodeFromString(StoredHandoff.serializer(), file.readText()) }.getOrNull()
    }

    /** Writes atomically, owner-only, so an interrupted write cannot corrupt the record. */
    fun write(record: StoredHandoff) {
        file.parentFile?.let { dir -> if (!dir.isDirectory && dir.mkdirs()) restrict(dir) }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.createNewFile()
        restrict(temp)
        temp.writeText(json.encodeToString(StoredHandoff.serializer(), record))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    fun delete() {
        file.delete()
    }

    private fun restrict(f: File) {
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                f.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        }
    }
}

/**
 * Splits a scanned handoff payload into the record and the seller's signature.
 *
 * The QR payload carries the *record*; the signature comes alongside it at the
 * meeting (the Android app has the same two-part problem, and the same two text
 * fields — `specs/README.md`'s open backlog notes the hand-transcription). Here
 * the console takes the record from the payload and the two signature halves as
 * arguments, so the split is explicit rather than implied.
 */
fun splitHandoffPayload(qrPayload: String, signatureA: String, signatureZ: String): StoredHandoff {
    val trimmed = qrPayload.trim()
    require(trimmed.startsWith(HANDOFF_PREFIX)) {
        "that is not a handoff link (expected something starting '$HANDOFF_PREFIX')"
    }
    val record = QrPayload.decodeHandoff(trimmed)
    require(signatureA.length == 66) { "the signature's a half is 33 bytes (66 hex chars), got ${signatureA.length}" }
    require(signatureZ.length == 64) { "the signature's z half is 32 bytes (64 hex chars), got ${signatureZ.length}" }
    return StoredHandoff(
        recordHex = record.encode().toHexString(),
        signatureA = signatureA.lowercase(),
        signatureZ = signatureZ.lowercase(),
        qrPayload = trimmed,
        capturedAtEpochMs = System.currentTimeMillis(),
    )
}

private const val HANDOFF_PREFIX = "p2pgate://handoff?m="

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }