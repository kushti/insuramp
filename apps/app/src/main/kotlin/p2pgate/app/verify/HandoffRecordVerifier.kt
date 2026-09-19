package p2pgate.app.verify

import p2pgate.dealprotocol.HandoffRecord
import p2pgate.dealprotocol.ProtocolConstants
import java.time.Duration
import java.time.Instant

/**
 * Android port of `apps/core/ergo/.../HandoffRecordVerifier.kt` — the
 * "don't leave the meeting without the record" gate (`specs/android-app.md`
 * §3.3 step 4, `onramp-ux.md` §2.3). Returns `false` on any defect — this is
 * a UI gate, not an exception surface.
 *
 * Checks, in order (byte-identical to the :core:ergo original):
 *  1. the record decodes as a 52-byte P2PH message (magic/version/fields);
 *  2. `record.dealId` equals the deal's id (the deal binding, §3.2 — the JVM
 *     original compares against `dealTerms.dealId`; the app holds the
 *     operator-issued deal id, which IS `blake2b256(terms)`, so the check is
 *     the same bytes);
 *  3. fiat amount and currency match the deal;
 *  4. the record timestamp is fresh: within [ProtocolConstants.HANDOFF_RECORD_MAX_AGE]
 *     of [now] (it must still pass path B's in-script freshness window when the
 *     claim lands) and within [ProtocolConstants.HANDOFF_CLOCK_SKEW] of [now]
 *     (an "absurd" signer clock is rejected before cash changes hands, §3.2);
 *  5. [SchnorrVerifier] over the raw record bytes under [sellerPubKey].
 *
 * The seller key arrives with the deal record (vault R5's key, pinned in the
 * deal terms); the app keeps it in the local deal snapshot.
 */
object HandoffRecordVerifier {

    fun verify(
        recordBytes: ByteArray,
        a: ByteArray,
        z: ByteArray,
        sellerPubKey: ByteArray,
        expectedDealId: ByteArray,
        expectedFiatAmount: Long,
        expectedFiatCurrency: ByteArray,
        now: Instant,
    ): Boolean {
        val record = try {
            HandoffRecord.decode(recordBytes)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (!record.dealId.contentEquals(expectedDealId)) return false
        if (record.amount != expectedFiatAmount) return false
        if (!record.fiatCurrency.contentEquals(expectedFiatCurrency)) return false

        val ts = Instant.ofEpochSecond(record.timestamp)
        val age = Duration.between(ts, now)
        if (age > ProtocolConstants.HANDOFF_RECORD_MAX_AGE) return false
        if (age.abs() > ProtocolConstants.HANDOFF_CLOCK_SKEW) return false

        return SchnorrVerifier.verify(recordBytes, a, z, sellerPubKey)
    }
}
