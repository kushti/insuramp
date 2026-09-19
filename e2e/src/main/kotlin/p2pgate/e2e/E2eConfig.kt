package p2pgate.e2e

import p2pgate.ergo.ExplorerChainSource

/**
 * e2e harness configuration — env vars with defaults; keys are generated per
 * run, no secrets anywhere.
 *
 * | env var | default | meaning |
 * |---|---|---|
 * | `E2E_EXPLORER_URL` | `https://api.ergoplatform.com` | explorer base URL (mainnet; testnet via `https://api-testnet.ergoplatform.com`) |
 * | `E2E_FAUCET_URL` | `off` | faucet base URL (`off` = manual funding; the faucet is testnet-only, e.g. `https://testnet.ergofaucet.org`) |
 * | `E2E_MINER_FEE_NANO_ERG` | 1_100_000 | miner fee per tx |
 * | `E2E_FUNDED_BOX_NANO_ERG` | 0 = auto (tree-size dust) | FUNDED box ERG value |
 * | `E2E_RECLAIM_TIMEOUT_BLOCKS` | 6 | fast reclaim timeout (funding-time R8) |
 * | `E2E_FUNDING_TIMEOUT_MS` | 300_000 | faucet/balance poll deadline |
 * | `E2E_STEP_TIMEOUT_MS` | 1_800_000 | per-step confirm/spend poll deadline |
 * | `E2E_PREFLIGHT_TIMEOUT_S` | 15 | explorer reachability timeout |
 * | `E2E_FAUCET_ATTEMPTS` | 5 | faucet requests before giving up |
 */
class E2eConfig(
    // Mainnet is the default target since 2026-09-17; testnet stays fully
    // selectable via E2E_EXPLORER_URL / E2E_FAUCET_URL.
    val explorerBaseUrl: String = System.getenv("E2E_EXPLORER_URL") ?: ExplorerChainSource.MAINNET_BASE_URL,
    val faucetBaseUrl: String = System.getenv("E2E_FAUCET_URL") ?: "https://testnet.ergofaucet.org",
    val faucetEnabled: Boolean = (System.getenv("E2E_FAUCET_URL") ?: "off") != "off",
    val dryRun: Boolean = false,
    val minerFeeNanoErg: Long = System.getenv("E2E_MINER_FEE_NANO_ERG")?.toLongOrNull() ?: 1_100_000L,
    /** 0 = auto: dust-derived from the compiled FUNDED tree size. */
    val fundedBoxValueNanoErg: Long = System.getenv("E2E_FUNDED_BOX_NANO_ERG")?.toLongOrNull() ?: 0L,
    val reclaimTimeoutBlocks: Int = System.getenv("E2E_RECLAIM_TIMEOUT_BLOCKS")?.toIntOrNull()
        ?: p2pgate.ergo.ErgoContracts.Fast.RECLAIM_TIMEOUT_BLOCKS,
    val fundingTimeoutMs: Long = System.getenv("E2E_FUNDING_TIMEOUT_MS")?.toLongOrNull() ?: 300_000L,
    val stepTimeoutMs: Long = System.getenv("E2E_STEP_TIMEOUT_MS")?.toLongOrNull() ?: 1_800_000L,
    val preflightTimeoutSeconds: Long = System.getenv("E2E_PREFLIGHT_TIMEOUT_S")?.toLongOrNull() ?: 15L,
    val faucetAttempts: Int = System.getenv("E2E_FAUCET_ATTEMPTS")?.toIntOrNull() ?: 5,
) {
    companion object {
        const val AUTO_FUNDED_VALUE: Long = 0L
    }
}
