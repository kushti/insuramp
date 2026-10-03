package p2pgate.tui.buyer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The buyer's key custody: **one file, encrypted under a passphrase**
 * (`specs/tui-apps.md` §5.1).
 *
 * The key is needed for exactly one operation — the path-D payout, whose
 * `proveDlog(buyerKey)` authorises the spend. Path B (claim-open) is
 * `sigmaProp`-only, so the entire dispute flow runs with no key file at all;
 * that narrowness is what lets the format stay this simple.
 *
 * ## File format
 *
 * A single fixed header then the ciphertext, so a wrong file is rejected by
 * shape rather than by a decryption failure:
 *
 * ```
 * "PGKEY"      5 bytes   magic
 * version      1 byte    1
 * iterations   4 bytes   big-endian PBKDF2 iteration count
 * salt        32 bytes   random per file
 * iv           12 bytes   random per write
 * ciphertext   n bytes   AES-256-GCM, n = plaintext + 16-byte tag
 * ```
 *
 * The header through the IV is authenticated as additional data, so an attacker
 * who lowers the iteration count or swaps the salt cannot mount a downgrade or a
 * cross-file confusion attack — the tag check fails. The iteration count is
 * stored rather than hardcoded so it can be raised without invalidating files.
 *
 * The plaintext is JSON ([Payload]) carrying the secp256k1 secret as hex plus the
 * deal identity it belongs to, so a decrypted file states which deal it unlocks.
 */
object KeyVault {

    /** Error for every failure mode: wrong passphrase, wrong file, tampered file. */
    class UnlockFailed(message: String) : Exception(message)

    private const val MAGIC = "PGKEY"
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /**
     * 210k iterations of PBKDF2-HMAC-SHA512 (the OWASP figure for SHA-512). Stored
     * in the header, so raising it later does not lock anyone out of their file.
     */
    const val DEFAULT_ITERATIONS = 210_000

    /** Refuse absurd iteration counts read from a file (a tamper or a bug, not a choice). */
    private const val MAX_ITERATIONS = 10_000_000

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /** The decrypted contents of a key file. */
    @Serializable
    data class Payload(
        /** secp256k1 secret, 32 bytes, lowercase hex. */
        val secret: String,
        /** Ergo P2PK address derived from [secret] — shown so a human can check it. */
        val address: String,
        /** The deal this key unlocks, when the buyer is working one deal. */
        val dealId: String? = null,
        /** Epoch millis the file was created. */
        val createdAt: Long = 0,
    )

    // ------------------------------------------------------------------ write

    /**
     * Encrypts [secret] (32 raw bytes) under [passphrase] and writes the envelope
     * to [file] with owner-only permissions where the platform allows it.
     *
     * The file is written to a sibling temp file and moved into place, so an
     * interrupted write cannot leave a truncated key file where a valid one was.
     * Existing content is not overwritten unless [overwrite] — losing a deal key
     * to an accidental re-key is unrecoverable.
     */
    fun write(
        file: File,
        passphrase: CharArray,
        secret: ByteArray,
        address: String,
        dealId: String? = null,
        iterations: Int = DEFAULT_ITERATIONS,
        overwrite: Boolean = false,
    ) {
        require(secret.size == 32) { "a secp256k1 secret is 32 bytes, got ${secret.size}" }
        require(passphrase.isNotEmpty()) { "an empty passphrase encrypts nothing" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range: $iterations" }
        if (file.exists() && !overwrite) {
            throw UnlockFailed("${file.path} already exists — pass overwrite to replace it")
        }
        // A key directory is created owner-only; an existing one is left as it is,
        // since tightening permissions the operator chose would be rude.
        file.parentFile?.let { dir ->
            if (!dir.isDirectory && dir.mkdirs()) restrictToOwner(dir)
        }

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = header(salt, iv, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(passphrase, salt, iterations),
            GCMParameterSpec(TAG_BITS, iv),
        )
        cipher.updateAAD(header)
        val plaintext = json.encodeToString(
            Payload.serializer(),
            Payload(secret.toHex(), address, dealId, System.currentTimeMillis()),
        ).toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        plaintext.fill(0)

        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.createNewFile()
        // Restrict *before* the first byte is written: creating the file leaves it
        // at the umask default (world-readable), and a window where the
        // ciphertext is readable by anyone is exactly the window that matters.
        restrictToOwner(temp)
        DataOutputStream(temp.outputStream().buffered()).use { out ->
            out.write(header)
            out.write(ciphertext)
        }
        if (!temp.renameTo(file)) {
            // Some filesystems refuse rename-over; fall back to copy, then drop
            // the temp file. `copyTo` carries the source's permissions with it.
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    /** True when a key file exists at [file] — presence only, never its contents. */
    fun exists(file: File): Boolean = file.isFile && file.length() > 0

    /**
     * The public facts about a key file: its address and fingerprint, readable
     * **without the passphrase**.
     *
     * A restarted console has to know its own payout address before it can deal —
     * the address is published as `receiveAddress` at deal creation — but deriving
     * it needs the secret, and prompting for a passphrase just to draw the status
     * bar would be absurd. So `write` also drops this sidecar next to the key
     * file. It contains nothing secret: the address is public by construction (it
     * is published on-chain and in every deal DTO) and the fingerprint is a
     * truncated hash of the secret.
     */
    @Serializable
    data class PublicInfo(
        val address: String,
        val fingerprint: String,
        val createdAtEpochMs: Long = 0,
    )

    /** Where [PublicInfo] for the key file at [keyFile] lives. */
    fun publicInfoFile(keyFile: File): File = File(keyFile.parentFile, "${keyFile.name}.pub")

    /** Writes the sidecar beside [keyFile]. Best-effort: a missing sidecar is recoverable. */
    internal fun writePublicInfo(keyFile: File, address: String, fingerprint: String) {
        runCatching {
            val file = publicInfoFile(keyFile)
            file.parentFile?.mkdirs()
            file.writeText(
                json.encodeToString(
                    PublicInfo.serializer(),
                    PublicInfo(address, fingerprint, System.currentTimeMillis()),
                ),
            )
            restrictToOwner(file)
        }
    }

    /** The sidecar, or `null` when it is missing or unreadable. */
    fun readPublicInfo(keyFile: File): PublicInfo? {
        val file = publicInfoFile(keyFile)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(PublicInfo.serializer(), file.readText())
        }.getOrNull()
    }

    // ------------------------------------------------------------------- read

    /**
     * Decrypts [file] under [passphrase].
     *
     * Every failure — absent file, truncated file, wrong magic, wrong version,
     * absurd iteration count, wrong passphrase, tampered ciphertext — surfaces as
     * [UnlockFailed] with a message that says which, so the console can tell a
     * user "that passphrase is wrong" without leaking anything about the file.
     */
    fun read(file: File, passphrase: CharArray): Payload {
        if (!file.isFile) throw UnlockFailed("no key file at ${file.path}")
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            throw UnlockFailed("cannot read ${file.path}: ${e.message}")
        }
        return unlock(bytes, passphrase, file.path)
    }

    /** The decrypt half, over bytes already in hand — what [read] and the tests use. */
    fun unlock(bytes: ByteArray, passphrase: CharArray, label: String = "key file"): Payload {
        val headerBytes = MAGIC.length + 1 + 4 + SALT_BYTES + IV_BYTES
        if (bytes.size <= headerBytes) throw UnlockFailed("$label is too short to be a key file")

        if (String(bytes, 0, MAGIC.length, StandardCharsets.US_ASCII) != MAGIC) {
            throw UnlockFailed("$label is not a p2pgate key file")
        }
        val version = bytes[MAGIC.length]
        if (version != VERSION) throw UnlockFailed("$label is format version $version, this build reads $VERSION")

        val iterations = readIterations(bytes, label)
        val saltStart = MAGIC.length + 1 + 4
        val salt = bytes.copyOfRange(saltStart, saltStart + SALT_BYTES)
        val iv = bytes.copyOfRange(saltStart + SALT_BYTES, headerBytes)
        val header = bytes.copyOfRange(0, headerBytes)
        val ciphertext = bytes.copyOfRange(headerBytes, bytes.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(passphrase, salt, iterations),
            GCMParameterSpec(TAG_BITS, iv),
        )
        cipher.updateAAD(header)
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw UnlockFailed("wrong passphrase, or $label has been modified")
        }
        return try {
            json.decodeFromString(Payload.serializer(), String(plaintext, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw UnlockFailed("$label decrypted but its contents are unreadable: ${e.message}")
        } finally {
            plaintext.fill(0)
        }
    }

    /** Decrypts and returns the raw 32-byte secret. */
    fun readSecret(file: File, passphrase: CharArray): ByteArray {
        val hex = read(file, passphrase).secret
        val bytes = hex.hexToBytes()
        if (bytes.size != 32) throw UnlockFailed("key file holds a ${bytes.size}-byte secret, expected 32")
        return bytes
    }

    // --------------------------------------------------------------- internals

    private fun header(salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        DataOutputStream(buffer).run {
            write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
            write(VERSION.toInt())
            writeInt(iterations)
            write(salt)
            write(iv)
            flush()
        }
        return buffer.toByteArray()
    }

    private fun readIterations(bytes: ByteArray, label: String): Int {
        val at = MAGIC.length + 1
        val iterations = DataInputStream(bytes.inputStream()).use { stream ->
            stream.skip(at.toLong())
            stream.readInt()
        }
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw UnlockFailed("$label declares an implausible iteration count ($iterations)")
        }
        return iterations
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** `chmod 600` where the platform supports it; a no-op elsewhere. */
    private fun restrictToOwner(file: File) {
        runCatching {
            val path = file.toPath()
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            java.nio.file.Files.setPosixFilePermissions(path, perms)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Reads a passphrase without echoing it.
 *
 * `P2P_KEY_PASSPHRASE` wins when it is set: someone who exports it has opted into
 * automation, and prompting anyway would hang a scripted run waiting for input
 * that is never coming. Otherwise [System.console] prompts with no echo. With
 * neither, this throws rather than silently using an empty passphrase.
 *
 * Note the env var is visible to anything that can read the process environment
 * (including `/proc/<pid>/environ`), so it is a convenience for automation and
 * smoke tests, not the recommended way to hold a deal key.
 *
 * Called *before* the Mosaic terminal loop starts: Mosaic owns stdin, so a prompt
 * issued from inside the event loop renders and never receives the keystrokes.
 * That was a real bug in this console before [openKeyFile] was introduced.
 *
 * [env] and [console] are injectable so the precedence is testable.
 */
fun readPassphrase(
    prompt: String = "Passphrase: ",
    env: () -> String? = { System.getenv("P2P_KEY_PASSPHRASE") },
    console: () -> java.io.Console? = { System.console() },
): CharArray {
    env()?.takeIf { it.isNotEmpty() }?.let { return it.toCharArray() }
    val term = console() ?: throw KeyVault.UnlockFailed(
        "no terminal available for a passphrase prompt; set P2P_KEY_PASSPHRASE",
    )
    return term.readPassword(prompt)
}

/**
 * A digest of the public half, for showing a key file's fingerprint in the UI
 * without revealing anything: two different secrets never collide here.
 */
fun fingerprintOf(secret: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(secret).take(4).joinToString("") { "%02x".format(it) }