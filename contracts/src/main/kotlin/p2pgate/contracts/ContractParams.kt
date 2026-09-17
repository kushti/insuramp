package p2pgate.contracts

/**
 * Canonical protocol parameters. The numeric values are defined once in
 * `specs/vault-contract.md` §2 — keep this object in sync with that table.
 */
object ContractParams {
    /** ~24h at the ~2-minute Ergo block target [approx]; reclaim timeout in blocks. */
    const val RECLAIM_TIMEOUT_BLOCKS: Int = 720

    /** ~12h [approx]; claim maturation in blocks, anchored to the proof height. */
    const val CLAIM_MATURATION_BLOCKS: Int = 360

    /** Freshness bound on the handoff-record timestamp. [spec] */
    const val HANDOFF_RECORD_MAX_AGE_MS: Long = 4L * 60 * 60 * 1000

    /**
     * The protocol fee in basis points — a compile-time contract constant,
     * substituted into both vault scripts as `%%FEE_BPS%%` and charged on
     * every collateral-moving path (reclaim, release, contest, claim payout).
     * fee = collateral * PROTOCOL_FEE_BPS / FEE_DENOMINATOR, rounding down.
     */
    const val PROTOCOL_FEE_BPS: Int = 25

    /** fee = collateral * PROTOCOL_FEE_BPS / FEE_DENOMINATOR, rounding down. */
    const val FEE_DENOMINATOR: Long = 10000

    /** Ergo network prefix for script compilation: 0x00 mainnet, 0x10 testnet. */
    const val NETWORK_PREFIX_MAINNET: Byte = 0x00
    const val NETWORK_PREFIX_TESTNET: Byte = 0x10
}
