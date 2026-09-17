package p2pgate.dealprotocol

import java.time.Duration

/**
 * Timing constants referenced, not redefined: `specs/vault-contract.md` §2 is the
 * source of truth. On-chain the timeouts are block-heights (≈720 blocks per 24h
 * [approx]); off-chain the state machine tracks `java.time` instants/durations.
 *
 * Owner decision (2026-09-10): `java.time` is used in `:core:dealprotocol` for
 * readability; at KMP/JS migration these become `kotlinx-datetime`
 * expect/actuals (`specs/android-app.md` §8.8, amended).
 */
object ProtocolConstants {
    /** Deal window; also the quote expiry horizon on the user side. */
    val RECLAIM_TIMEOUT: Duration = Duration.ofHours(24)

    /**
     * Claim maturation: the seller gets half a day to counter an open claim
     * with the oracle signal (path C′) before the user can take the collateral
     * (path D). Owner decision (2026-09-13): shortened from 24h — the release
     * direction is fully oracle-trusted in v2, so a long contest window buys
     * nothing.
     */
    val CLAIM_MATURATION: Duration = Duration.ofHours(12)

    /** Contract freshness bound on the handoff-record timestamp, enforced
     * in-script by vault path B against the claim tx timestamp — not by the
     * state machine. */
    val HANDOFF_RECORD_MAX_AGE: Duration = Duration.ofHours(4)

    /**
     * Pre-sign sanity bound on courier-device clock skew when the handoff
     * confirmation arrives ([spec] ±10 min, `specs/deal-protocol.md` §3.2). The
     * user app rejects an "absurd" timestamp before showing the sign prompt.
     */
    val COURIER_CLOCK_SKEW: Duration = Duration.ofMinutes(10)
}
