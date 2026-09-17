package p2pgate.dealprotocol

/**
 * Canonical deal states for the cash→USDT on-ramp, `specs/deal-protocol.md` §1. Every
 * component uses these names verbatim. On-chain footprint: FUNDED-family states hold
 * the FUNDED vault box; CLAIM_OPENED/CLAIMABLE hold the PAYMENT_PROVEN box; terminal
 * states are box spent or never existed (QUOTED → abandoned).
 */
enum class DealState {
    QUOTED,
    FUNDED,
    PAYMENT_PENDING,
    PAYMENT_CONFIRMED,
    RELEASED,
    RECLAIMED,
    CLAIM_OPENED,
    CLAIMABLE,
    CLAIMED,
    ;

    /** `specs/deal-protocol.md` §1: terminal states are RELEASED, RECLAIMED, CLAIMED. */
    val isTerminal: Boolean
        get() = this == RELEASED || this == RECLAIMED || this == CLAIMED
}
