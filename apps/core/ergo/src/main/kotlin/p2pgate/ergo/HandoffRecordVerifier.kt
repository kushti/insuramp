package p2pgate.ergo

import p2pgate.dealprotocol.DealTerms
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.dealprotocol.ProtocolConstants
import java.time.Duration
import java.time.Instant

/**
 * The "don't leave the meeting without the record" gate, `specs/android-app.md`
 * §3.3 step 4: validates the scanned seller-signed handoff record (the
 * cash-received acknowledgment) against the deal terms and verifies the
 * single Schnorr signature under the deal's seller key (the buyer signs
 * nothing in v2). Returns `false` on any defect — this is a UI gate, not an
 * exception surface.
 *
 * Checks, in order:
 *  1. the record decodes as a 52-byte P2PH message (magic/version/fields);
 *  2. `record.dealId == dealTerms.dealId` — the deal binding (§3.2);
 *  3. fiat amount and currency match the terms;
 *  4. the record timestamp is fresh: within [ProtocolConstants.HANDOFF_RECORD_MAX_AGE]
 *     of [now] (it must still pass path B's in-script freshness window when the
 *     claim lands) and within [ProtocolConstants.HANDOFF_CLOCK_SKEW] of [now]
 *     (an "absurd" signer clock is rejected before cash changes hands, §3.2);
 *  5. [SchnorrVerifier] over the raw record bytes under [sellerPubKey].
 *
 * Callers pass the pinned key — `dealTerms.sellerPubKey` (or the key extracted
 * from the funded box's R5 when double-checking against the chain).
 */
object HandoffRecordVerifier {

    fun verify(
        recordBytes: ByteArray,
        a: ByteArray,
        z: ByteArray,
        sellerPubKey: ByteArray,
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
        if (age.abs() > ProtocolConstants.HANDOFF_CLOCK_SKEW) return false

        return SchnorrVerifier.verify(recordBytes, a, z, sellerPubKey)
    }
}
