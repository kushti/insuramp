package p2pgate.tui.buyer

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Key custody. The properties asserted here are the ones a stolen or corrupted
 * key file would break, in rough order of how much they would cost: a wrong
 * passphrase must not decrypt, tampering must not decrypt, and the secret must
 * never touch disk in the clear.
 */
class KeyVaultSpec {

    private val secret = ByteArray(32) { it.toByte() }
    private val address = "9dRTA1YSPXZgTLwMXKKKrqzU4UppV6JDPRAgW5ScTbBrUjF"

    private fun tempFile(name: String = "key.p2pkey"): File =
        File(System.getProperty("java.io.tmpdir"), "p2pgate-test-${MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).take(6).joinToString("") { "%02x".format(it) }}-$name")

    private fun written(passphrase: String = "correct horse battery staple", file: File = tempFile()): File {
        file.delete()
        KeyVault.write(file, passphrase.toCharArray(), secret, address, dealId = "deal-abc", iterations = 2_000)
        return file
    }

    @Test
    fun `a written key round-trips under the right passphrase`() {
        val file = written()
        val payload = KeyVault.read(file, "correct horse battery staple".toCharArray())
        assertContentEquals(secret, payload.secret.hexToBytes())
        assertEquals(address, payload.address)
        assertEquals("deal-abc", payload.dealId)
    }

    @Test
    fun `readSecret returns the exact 32 bytes`() {
        assertContentEquals(secret, KeyVault.readSecret(written(), "correct horse battery staple".toCharArray()))
    }

    @Test
    fun `a wrong passphrase is refused, and says so`() {
        val file = written()
        val error = assertFailsWith<KeyVault.UnlockFailed> {
            KeyVault.read(file, "not the passphrase".toCharArray())
        }
        assertTrue(error.message!!.contains("wrong passphrase"), error.message)
    }

    @Test
    fun `one file's ciphertext cannot be pasted into another's envelope`() {
        // The salt and IV are per file, so a key file is bound to its own header:
        // swapping the body into another file's header fails, even though the
        // passphrase is the same and both files were written by this process.
        val a = written(passphrase = "same", file = tempFile("a.p2pkey"))
        val b = written(passphrase = "same", file = tempFile("b.p2pkey"))

        // Sanity: each file opens under its own passphrase.
        assertContentEquals(secret, KeyVault.readSecret(a, "same".toCharArray()))
        assertContentEquals(secret, KeyVault.readSecret(b, "same".toCharArray()))

        // Now splice a's ciphertext (and a's IV) into b's envelope.
        val aBytes = a.readBytes()
        val bBytes = b.readBytes()
        val headerBytes = 5 + 1 + 4 + 32 + 12
        val spliced = bBytes.copyOfRange(0, headerBytes) + aBytes.copyOfRange(headerBytes, aBytes.size)
        assertFailsWith<KeyVault.UnlockFailed> { KeyVault.unlock(spliced, "same".toCharArray()) }
    }

    @Test
    fun `the plaintext secret never appears in the file`() {
        val file = written()
        val onDisk = file.readBytes().toHex()
        assertFalse(onDisk.contains(secret.toHex().take(32)), "the secret is in the file in the clear")
        assertFalse(onDisk.contains("correct horse"), "the passphrase is in the file")
    }

    @Test
    fun `the salt and IV are fresh on every write, so the file is not deterministic`() {
        val a = written(file = tempFile("n1.p2pkey")).readBytes()
        val b = written(file = tempFile("n2.p2pkey")).readBytes()
        assertFalse(a.contentEquals(b), "two writes produced byte-identical files")
    }

    @Test
    fun `a flipped ciphertext bit is refused`() {
        val file = written()
        val bytes = file.readBytes()
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 0x01).toByte()
        val error = assertFailsWith<KeyVault.UnlockFailed> {
            KeyVault.unlock(bytes, "correct horse battery staple".toCharArray())
        }
        assertTrue(error.message!!.contains("modified"), error.message)
    }

    @Test
    fun `a lowered iteration count is refused rather than honoured`() {
        // The header is authenticated, so this cannot be done silently -- but if
        // it ever were, accepting 1 iteration would turn key derivation into
        // nothing. The reader refuses implausible counts regardless.
        val file = written()
        val bytes = file.readBytes()
        val lowered = ByteArray(bytes.size)
        lowered.indices.forEach { lowered[it] = bytes[it] }
        // Rewrite iterations in place to 1 (magic 5 + version 1, then 4 bytes).
        lowered[6] = 0; lowered[7] = 0; lowered[8] = 0; lowered[9] = 1
        val error = assertFailsWith<KeyVault.UnlockFailed> {
            KeyVault.unlock(lowered, "correct horse battery staple".toCharArray(), "tampered")
        }
        // Either the count is rejected outright, or GCM's tag rejects the swap.
        assertTrue(
            error.message!!.contains("implausible") || error.message!!.contains("modified"),
            error.message,
        )
    }

    @Test
    fun `an unknown magic is not treated as a key file`() {
        val bytes = written().readBytes()
        bytes[0] = 'X'.code.toByte()
        val error = assertFailsWith<KeyVault.UnlockFailed> {
            KeyVault.unlock(bytes, "correct horse battery staple".toCharArray())
        }
        assertTrue(error.message!!.contains("not a p2pgate key file"), error.message)
    }

    @Test
    fun `a future format version is refused, not guessed at`() {
        val bytes = written().readBytes()
        bytes[5] = 99
        val error = assertFailsWith<KeyVault.UnlockFailed> {
            KeyVault.unlock(bytes, "correct horse battery staple".toCharArray())
        }
        assertTrue(error.message!!.contains("format version 99"), error.message)
    }

    @Test
    fun `a truncated file is refused rather than read partially`() {
        val bytes = written().readBytes()
        val error = assertFailsWith<KeyVault.UnlockFailed> {
            KeyVault.unlock(bytes.copyOf(20), "correct horse battery staple".toCharArray())
        }
        assertTrue(error.message!!.contains("too short"), error.message)
    }

    @Test
    fun `a missing file says where it looked`() {
        val error = assertFailsWith<KeyVault.UnlockFailed> {
            KeyVault.read(File(System.getProperty("java.io.tmpdir"), "absent.p2pkey"), "x".toCharArray())
        }
        assertTrue(error.message!!.contains("no key file at"), error.message)
    }

    @Test
    fun `writing refuses to clobber an existing key without an explicit overwrite`() {
        val file = written()
        val error = assertFailsWith<KeyVault.UnlockFailed> {
            KeyVault.write(file, "correct horse battery staple".toCharArray(), secret, address, iterations = 2_000)
        }
        assertTrue(error.message!!.contains("already exists"), error.message)

        // …and honours it when asked, leaving a file that still opens.
        KeyVault.write(
            file, "a new passphrase".toCharArray(), ByteArray(32) { 7 }, address,
            iterations = 2_000, overwrite = true,
        )
        // The old passphrase must no longer open it -- a re-key really replaced it.
        assertFailsWith<KeyVault.UnlockFailed> {
            KeyVault.unlock(file.readBytes(), "correct horse battery staple".toCharArray())
        }
        assertContentEquals(ByteArray(32) { 7 }, KeyVault.readSecret(file, "a new passphrase".toCharArray()))
    }

    @Test
    fun `writing rejects a wrong-sized secret, an empty passphrase, and a bad iteration count`() {
        val file = tempFile("reject.p2pkey").also { it.delete() }
        assertFailsWith<IllegalArgumentException> {
            KeyVault.write(file, "pass".toCharArray(), ByteArray(16), address)
        }
        assertFailsWith<IllegalArgumentException> {
            KeyVault.write(file, CharArray(0), secret, address)
        }
        assertFailsWith<IllegalArgumentException> {
            KeyVault.write(file, "pass".toCharArray(), secret, address, iterations = 0)
        }
        assertFalse(file.exists(), "a rejected write still created a file")
    }

    @Test
    fun `the file is created owner-only where the platform allows it`() {
        val file = written()
        val perms = runCatching {
            java.nio.file.attribute.PosixFilePermissions.toString(
                java.nio.file.Files.getPosixFilePermissions(file.toPath()),
            )
        }.getOrNull()
        // A non-POSIX filesystem cannot express this; there assert nothing rather
        // than a wrong expectation. Where it can, it must be owner-only.
        if (perms != null) assertEquals("rw-------", perms, "key file permissions are $perms")
    }

    @Test
    fun `exists reports presence without reading the file`() {
        val file = written()
        assertTrue(KeyVault.exists(file))
        file.delete()
        assertFalse(KeyVault.exists(file))
        assertFalse(KeyVault.exists(tempFile("never-written.p2pkey")))
    }

    @Test
    fun `a fingerprint identifies a key without revealing it`() {
        val a = fingerprintOf(secret)
        assertEquals(8, a.length)
        assertEquals(a, fingerprintOf(secret.copyOf()))
        assertFalse(a == fingerprintOf(ByteArray(32) { 1 }))
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()