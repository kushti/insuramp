package p2pgate.dealprotocol

/**
 * Pure-Kotlin BLAKE2b with a 32-byte digest, RFC 7693: parameter block
 * digestLength=32, keyLength=0, fanout=1, depth=1; 128-byte blocks; the
 * 12-round tweak (sigma rounds 0–9, then sigma 0–1 again). No `java.*`
 * imports — KMP-ready (`specs/android-app.md` §8.8, amended).
 *
 * Used for `dealId` and every message hash in `specs/deal-protocol.md` §3,
 * so the wire format hashes identically on Android, the backend, and in
 * ErgoScript (whose `blake2b256` is the same function).
 */
object Blake2b256 {

    const val DIGEST_LENGTH = 32
    private const val BLOCK_LENGTH = 128
    private const val ROUNDS = 12

    /** SHA-512 IV, reused as the BLAKE2b initialization vector (RFC 7693 §2.1). */
    private val IV = longArrayOf(
        0x6a09e667f3bcc908L, -0x4498517a7b3558c5L,
        0x3c6ef372fe94f82bL, -0x5ab00ac5a0e2c90fL,
        0x510e527fade682d1L, -0x64fa9773d4c193e1L,
        0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    )

    /** Message schedule (RFC 7693 §2.3); BLAKE2b has 12 rounds, so rows 0–1 repeat. */
    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
    )

    /** Hashes [inputs] concatenated; equals `blake2b256(join(inputs))`. */
    fun digest(vararg inputs: ByteArray): ByteArray {
        var total = 0
        for (input in inputs) total += input.size
        val message = when {
            inputs.size == 1 -> inputs[0]
            else -> ByteArray(total).also { out ->
                var position = 0
                for (input in inputs) {
                    input.copyInto(out, position)
                    position += input.size
                }
            }
        }

        val h = IV.copyOf()
        // Parameter block, word 0: digestLength | keyLength<<8 | fanout<<16 | depth<<24
        // = 32 | 0 | 1<<16 | 1<<24; leafLength/nodeOffset/innerLength/xofLength stay 0.
        h[0] = h[0] xor (32L or (1L shl 16) or (1L shl 24))

        var counterLo = 0L
        var counterHi = 0L
        var position = 0

        fun addToCounter(bytes: Int) {
            val sum = counterLo + bytes
            if (sum < counterLo) counterHi++
            counterLo = sum
        }

        // Every full block except the last is a non-final block; the last block
        // (zero-padded, possibly empty) carries the finalization flag.
        while (message.size - position > BLOCK_LENGTH) {
            addToCounter(BLOCK_LENGTH)
            compress(h, message, position, counterLo, counterHi, last = false)
            position += BLOCK_LENGTH
        }
        val lastLength = message.size - position
        addToCounter(lastLength)
        val lastBlock = ByteArray(BLOCK_LENGTH)
        message.copyInto(lastBlock, 0, position, message.size)
        compress(h, lastBlock, 0, counterLo, counterHi, last = true)

        val digest = ByteArray(DIGEST_LENGTH)
        for (i in 0 until DIGEST_LENGTH / 8) {
            val word = h[i]
            for (b in 0 until 8) digest[i * 8 + b] = (word shr (8 * b)).toByte()
        }
        return digest
    }

    private fun compress(
        h: LongArray,
        block: ByteArray,
        offset: Int,
        counterLo: Long,
        counterHi: Long,
        last: Boolean,
    ) {
        fun messageWord(i: Int): Long {
            var word = 0L
            for (b in 0 until 8) {
                word = word or ((block[offset + i * 8 + b].toLong() and 0xffL) shl (8 * b))
            }
            return word
        }

        val v = LongArray(16)
        for (i in 0 until 8) v[i] = h[i]
        for (i in 0 until 8) v[i + 8] = IV[i]
        v[12] = v[12] xor counterLo
        v[13] = v[13] xor counterHi
        if (last) v[14] = v[14].inv()

        for (round in 0 until ROUNDS) {
            val s = SIGMA[round % SIGMA.size]
            g(v, 0, 4, 8, 12, messageWord(s[0]), messageWord(s[1]))
            g(v, 1, 5, 9, 13, messageWord(s[2]), messageWord(s[3]))
            g(v, 2, 6, 10, 14, messageWord(s[4]), messageWord(s[5]))
            g(v, 3, 7, 11, 15, messageWord(s[6]), messageWord(s[7]))
            g(v, 0, 5, 10, 15, messageWord(s[8]), messageWord(s[9]))
            g(v, 1, 6, 11, 12, messageWord(s[10]), messageWord(s[11]))
            g(v, 2, 7, 8, 13, messageWord(s[12]), messageWord(s[13]))
            g(v, 3, 4, 9, 14, messageWord(s[14]), messageWord(s[15]))
        }

        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = v[a] + v[b] + y
        v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(63)
    }
}
