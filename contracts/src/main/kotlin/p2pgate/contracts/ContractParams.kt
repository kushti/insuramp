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

    /**
     * The FUNDED box's spending-path discriminator: context extension variable 0,
     * a `Byte` naming which of the three paths (`specs/vault-contract.md` §3.3)
     * the spender is invoking. Chosen to mirror the Basis reserve contract, where
     * context var 0 likewise selects the action.
     *
     * Hardcoded as bare literals in `vault_funded.es` (`action == 0` etc.), the
     * way `basis.es` writes its action codes. They are structural, not deployment
     * parameters — unlike [CLAIM_MATURATION_BLOCKS], nothing varies them between a
     * mainnet compile and a fast e2e one — so substituting them through the
     * `%%...%%` mechanism would buy nothing. These constants exist for the tx
     * builders, which must put the same number on the wire; `FundedActionSpec`
     * reads the `.es` source and fails if the two ever disagree.
     */
    const val ACTION_CLAIM: Int = 0
    const val ACTION_RECLAIM: Int = 1
    const val ACTION_RELEASE: Int = 2

    /** Index of the action byte in the FUNDED box's context extension (var 0). */
    const val ACTION_VAR_INDEX: Int = 0

    /** Freshness bound on the handoff-record timestamp. [spec] */
    const val HANDOFF_RECORD_MAX_AGE_MS: Long = 4L * 60 * 60 * 1000

    /** Ergo network prefix for script compilation: 0x00 mainnet, 0x10 testnet. */
    const val NETWORK_PREFIX_MAINNET: Byte = 0x00
    const val NETWORK_PREFIX_TESTNET: Byte = 0x10
}
