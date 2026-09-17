package p2pgate.ergo

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The meeting-time "safe to leave" gate (`specs/android-app.md` §3.3 step 4):
 * record decode, deal binding (dealId/amount/currency), timestamp freshness
 * (HANDOFF_RECORD_MAX_AGE) and signer clock sanity (HANDOFF_CLOCK_SKEW),
 * then the Schnorr signature under the seller key. All defects return `false`.
 */
class HandoffRecordVerifierSpec {

    private val now = Instant.ofEpochSecond(1_700_000_300L)
    private val recordTs = 1_700_000_000L

    private fun valid(): Triple<ByteArray, ByteArray, ByteArray> {
        val terms = ErgoTestFixtures.dealTerms()
        val record = ErgoTestFixtures.handoffRecord(terms, tsSec = recordTs)
        val sig = ErgoTestFixtures.sellerSign(record)
        return Triple(record.encode(), sig.a, sig.z)
    }

    @Test
    fun `a complete seller-signed record verifies against the terms`() {
        val terms = ErgoTestFixtures.dealTerms()
        val record = ErgoTestFixtures.handoffRecord(terms, tsSec = recordTs)
        val sig = ErgoTestFixtures.sellerSign(record)
        assertTrue(
            HandoffRecordVerifier.verify(record.encode(), sig.a, sig.z, terms.sellerPubKey, terms, now),
        )
    }

    @Test
    fun `record bound to a different deal fails`() {
        val terms = ErgoTestFixtures.dealTerms()
        val record = ErgoTestFixtures.handoffRecord(terms, tsSec = recordTs)
        val sig = ErgoTestFixtures.sellerSign(record)
        val otherTerms = ErgoTestFixtures.dealTerms(fiatAmount = 999_000L)
        assertFalse(
            HandoffRecordVerifier.verify(record.encode(), sig.a, sig.z, otherTerms.sellerPubKey, otherTerms, now),
        )
    }

    @Test
    fun `amount mismatch against the terms fails`() {
        val terms = ErgoTestFixtures.dealTerms()
        // Record honestly signed for a *different* amount under the same seller key:
        // the signature is valid, the terms binding is what must fail.
        val otherTerms = ErgoTestFixtures.dealTerms(fiatAmount = 999_000L)
        val foreignRecord = ErgoTestFixtures.handoffRecord(otherTerms, tsSec = recordTs)
        val sig = ErgoTestFixtures.sellerSign(foreignRecord)
        assertFalse(
            HandoffRecordVerifier.verify(foreignRecord.encode(), sig.a, sig.z, terms.sellerPubKey, terms, now),
        )
    }

    @Test
    fun `currency mismatch against the terms fails`() {
        val terms = ErgoTestFixtures.dealTerms()
        val usdTerms = ErgoTestFixtures.dealTerms(currency = "USD")
        val foreignRecord = ErgoTestFixtures.handoffRecord(usdTerms, tsSec = recordTs)
        val sig = ErgoTestFixtures.sellerSign(foreignRecord)
        assertFalse(
            HandoffRecordVerifier.verify(foreignRecord.encode(), sig.a, sig.z, terms.sellerPubKey, terms, now),
        )
    }

    @Test
    fun `stale record fails freshness`() {
        val terms = ErgoTestFixtures.dealTerms()
        val record = ErgoTestFixtures.handoffRecord(terms, tsSec = recordTs - 5L * 60 * 60) // 5h old
        val sig = ErgoTestFixtures.sellerSign(record)
        assertFalse(
            HandoffRecordVerifier.verify(record.encode(), sig.a, sig.z, terms.sellerPubKey, terms, now),
        )
    }

    @Test
    fun `absurd future timestamp beyond signer clock skew fails`() {
        val terms = ErgoTestFixtures.dealTerms()
        val record = ErgoTestFixtures.handoffRecord(terms, tsSec = recordTs + 20L * 60) // +20 min
        val sig = ErgoTestFixtures.sellerSign(record)
        assertFalse(
            HandoffRecordVerifier.verify(record.encode(), sig.a, sig.z, terms.sellerPubKey, terms, now),
        )
    }

    @Test
    fun `record within the clock skew passes the timestamp gate`() {
        val terms = ErgoTestFixtures.dealTerms()
        val record = ErgoTestFixtures.handoffRecord(terms, tsSec = recordTs + 5L * 60) // +5 min, inside ±10 min
        val sig = ErgoTestFixtures.sellerSign(record)
        assertTrue(
            HandoffRecordVerifier.verify(record.encode(), sig.a, sig.z, terms.sellerPubKey, terms, now),
        )
    }

    @Test
    fun `garbage bytes fail decode`() {
        val terms = ErgoTestFixtures.dealTerms()
        val (record, a, z) = valid()
        assertFalse(HandoffRecordVerifier.verify(ByteArray(10), a, z, terms.sellerPubKey, terms, now))
        val badMagic = record.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFalse(HandoffRecordVerifier.verify(badMagic, a, z, terms.sellerPubKey, terms, now))
    }

    @Test
    fun `broken signature fails`() {
        val terms = ErgoTestFixtures.dealTerms()
        val (record, a, z) = valid()
        val badZ = z.copyOf().also { it[10] = (it[10].toInt() xor 0x01).toByte() }
        assertFalse(HandoffRecordVerifier.verify(record, a, badZ, terms.sellerPubKey, terms, now))
        val wrongKey = TestKeys.of(0x7777)
        assertFalse(HandoffRecordVerifier.verify(record, a, z, wrongKey.pubKeyCompressed, terms, now))
    }
}
