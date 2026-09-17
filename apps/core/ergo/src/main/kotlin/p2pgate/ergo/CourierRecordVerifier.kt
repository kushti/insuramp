package p2pgate.ergo

import p2pgate.dealprotocol.DealTerms
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.dealprotocol.ProtocolConstants
import java.time.Duration
import java.time.Instant

/**
 * The "don't leave the meeting without the record" gate, `specs/android-app.md`
 * §3.3 step 4: validates the scanned courier-signed handoff record against the
 * deal terms and the courier key pinned in them, and verifies the single
 * Schnorr half (the user signs nothing in v2). Returns `false` on any defect —
 * this is a UI gate, not an exception surface.
 *
 * Checks, in order:
 *  1. the record decodes as an 84-byte P2PH message (magic/version/fields);
 *  2. `record.dealId == dealTerms.dealId` — the deal binding (§3.2);
 *  3. fiat amount and currency match the terms;
 *  4. the record timestamp is fresh: within [ProtocolConstants.HANDOFF_RECORD_MAX_AGE]
 *     of [now] (it must still pass path B's in-script freshness window when the
 *     claim lands) and within [ProtocolConstants.COURIER_CLOCK_SKEW] of [now]
 *     (an "absurd" courier clock is rejected before cash changes hands, §3.2);
 *  5. [SchnorrVerifier] over the raw record bytes under [courierPubKey].
 *
 * Callers pass the pinned key — `dealTerms.courierPubKey` (or the key extracted
 * from the funded box's R7 when double-checking against the chain).
 */
object CourierRecordVerifier {

    fun verify(
        recordBytes: ByteArray,
        a: ByteArray,
        z: ByteArray,
        courierPubKey: ByteArray,
        dealTerms: DealTerms,
        now: Instant,
    ): Boolean {
        val record = try {
            HandoffRecord.decode(recordBytes)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (!record.dealId.contentEquals(dealTerms.dealId)) return false
        if (record.amount != dealTerms.fiatAmount) return false
        if (!record.fiatCurrency.contentEquals(dealTerms.fiatCurrency)) return false

        val ts = Instant.ofEpochSecond(record.timestamp)
        val age = Duration.between(ts, now)
        if (age > ProtocolConstants.HANDOFF_RECORD_MAX_AGE) return false
        if (age.abs() > ProtocolConstants.COURIER_CLOCK_SKEW) return false

        return SchnorrVerifier.verify(recordBytes, a, z, courierPubKey)
    }
}
