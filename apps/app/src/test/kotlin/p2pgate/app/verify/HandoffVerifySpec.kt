package p2pgate.app.verify

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.dealprotocol.QrPayload
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The meeting-gate scan path end to end (`onramp-ux.md` §2.3): seller builds
 * the record → encodes the pinned `p2pgate://handoff?m=...` QR → buyer decodes
 * via `:core:dealprotocol`'s [QrPayload] → the ported [HandoffRecordVerifier]
 * gates on deal binding / amount / currency / freshness / Schnorr. Mirrors
 * `apps/core/ergo/.../HandoffRecordVerifierSpec.kt` plus the QR round trip.
 */
class HandoffVerifySpec {

    private val now = Instant.ofEpochSecond(1_700_000_300L)
    private val recordTs = 1_700_000_000L

    private val seller = TestKeys.of(0x1111)
    private val dealId = ByteArray(32) { (it * 7 + 2).toByte() }

    private fun record(amount: Long = 250_000L, currency: String = "EGP", ts: Long = recordTs) =
        HandoffRecord(
            dealId = dealId,
            amount = amount,
            fiatCurrency = currency.toByteArray(),
            timestamp = ts,
        )

    private fun gate(record: HandoffRecord, a: ByteArray, z: ByteArray, amount: Long = record.amount, currency: String = "EGP") =
        HandoffRecordVerifier.verify(
            recordBytes = record.encode(),
            a = a,
            z = z,
            sellerPubKey = seller.pubKeyCompressed,
            expectedDealId = dealId,
            expectedFiatAmount = amount,
            expectedFiatCurrency = currency.toByteArray(),
            now = now,
        )

    @Test
    fun `full QR path — scanned record verifies against the deal`() {
        val r = record()
        val sig = RefSchnorr.sign(seller.secret, r.encode(), seller.pubKeyCompressed)
        val qr = QrPayload.encodeHandoff(r)
        assertTrue(qr.startsWith("p2pgate://handoff?m="))
        // What the buyer's app does with the scanned payload:
        val scanned = QrPayload.decodeHandoff(qr)
        assertTrue(gate(scanned, sig.a, sig.z))
    }

    @Test
    fun `tampered signature fails`() {
        val r = record()
        val sig = RefSchnorr.sign(seller.secret, r.encode(), seller.pubKeyCompressed)
        val badZ = sig.z.copyOf().also { it[10] = (it[10].toInt() xor 0x01).toByte() }
        assertFalse(gate(r, sig.a, badZ))
    }

    @Test
    fun `signature under a different seller key fails`() {
        val r = record()
        val sig = RefSchnorr.sign(seller.secret, r.encode(), seller.pubKeyCompressed)
        val other = TestKeys.of(0x7777)
        assertFalse(
            HandoffRecordVerifier.verify(
                r.encode(), sig.a, sig.z,
                sellerPubKey = other.pubKeyCompressed,
                expectedDealId = dealId,
                expectedFiatAmount = 250_000L,
                expectedFiatCurrency = "EGP".toByteArray(),
                now = now,
            ),
        )
    }

    @Test
    fun `record for a different deal id fails the binding`() {
        val foreign = record().let { HandoffRecord(ByteArray(32) { 9 }, it.amount, it.fiatCurrency, it.timestamp) }
        val sig = RefSchnorr.sign(seller.secret, foreign.encode(), seller.pubKeyCompressed)
        assertFalse(gate(foreign, sig.a, sig.z))
    }

    @Test
    fun `wrong amount against the deal terms fails even with a valid signature`() {
        val r = record() // honestly signed for 250,000
        val sig = RefSchnorr.sign(seller.secret, r.encode(), seller.pubKeyCompressed)
        assertFalse(gate(r, sig.a, sig.z, amount = 999_000L))
    }

    @Test
    fun `wrong currency against the deal terms fails`() {
        val r = record(currency = "USD") // honestly signed, wrong currency
        val sig = RefSchnorr.sign(seller.secret, r.encode(), seller.pubKeyCompressed)
        assertFalse(gate(r, sig.a, sig.z, currency = "EGP"))
    }

    @Test
    fun `expired record fails freshness`() {
        val r = record(ts = recordTs - 5L * 60 * 60) // 5h old > 4h max age
        val sig = RefSchnorr.sign(seller.secret, r.encode(), seller.pubKeyCompressed)
        assertFalse(gate(r, sig.a, sig.z))
    }

    @Test
    fun `future record beyond clock skew fails`() {
        val r = record(ts = recordTs + 20L * 60) // +20 min > ±10 min skew
        val sig = RefSchnorr.sign(seller.secret, r.encode(), seller.pubKeyCompressed)
        assertFalse(gate(r, sig.a, sig.z))
    }

    @Test
    fun `record within the clock skew passes`() {
        val r = record(ts = recordTs + 5L * 60)
        val sig = RefSchnorr.sign(seller.secret, r.encode(), seller.pubKeyCompressed)
        assertTrue(gate(r, sig.a, sig.z))
    }

    @Test
    fun `garbage scan fails without throwing`() {
        // The verifier returns false on bytes that don't decode as a record.
        val garbage = ByteArray(52) { it.toByte() }
        val sig = RefSchnorr.sign(seller.secret, garbage, seller.pubKeyCompressed)
        assertFalse(
            HandoffRecordVerifier.verify(
                garbage, sig.a, sig.z,
                sellerPubKey = seller.pubKeyCompressed,
                expectedDealId = dealId,
                expectedFiatAmount = 250_000L,
                expectedFiatCurrency = "EGP".toByteArray(),
                now = now,
            ),
        )
        // A QR that is not a handoff payload throws on decode (the screen's
        // onQrScanned turns that into a Rejected gate).
        assertThrows<IllegalArgumentException> {
            QrPayload.decodeHandoff("https://example.com/not-a-handoff")
        }
    }
}
