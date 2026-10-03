package p2pgate.tui.common

/**
 * Runtime configuration for both consoles (`specs/tui-apps.md` §6; the
 * operator backend's own env vars are in `specs/operator-backend.md` §9).
 * Values come from, in order of precedence: command-line
 * flags, environment variables, then the defaults below — the same order the
 * backend uses for its own configuration.
 *
 * Nothing here is secret storage: the operator key is read from the
 * environment (or a flag) and never written to disk.
 */
data class TuiConfig(
    /** Base URL of the operator backend, e.g. `http://127.0.0.1:8080`. */
    val baseUrl: String,
    /** `P2P_OPERATOR_KEY` — the dashboard API's bearer token. */
    val operatorKey: String?,
    /** Ergo network name, `mainnet` or `testnet` — for chain-facing clients. */
    val network: String = DEFAULT_NETWORK,
    /** Explorer base URL; `null` selects the per-network default. */
    val explorerUrl: String? = null,
    /** Node base URLs (comma-separated) when reading chain state from a node. */
    val nodeUrl: String? = null,
) {
    /** WebSocket base derived from [baseUrl] (`http` → `ws`). */
    val wsBaseUrl: String get() = baseUrl.trimEnd('/').replace(Regex("^http"), "ws")

    val isTestnet: Boolean get() = network == TESTNET

    companion object {
        const val DEFAULT_NETWORK = "mainnet"
        const val TESTNET = "testnet"
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"

        /**
         * Resolves the configuration from [args] and [env] (injectable so the
         * resolution itself is testable). Recognised flags: `--base-url`,
         * `--operator-key`, `--network`, `--explorer-url`, `--node-url`.
         * Environment: `P2P_BASE_URL`, `P2P_OPERATOR_KEY`, `P2P_NETWORK`,
         * `P2P_EXPLORER_URL`, `P2P_NODE_URL`.
         */
        fun resolve(
            args: Array<String> = emptyArray(),
            env: Map<String, String> = System.getenv(),
        ): TuiConfig {
            val flags = parseFlags(args)
            fun pick(flag: String, varName: String): String? =
                flags[flag]?.takeIf { it.isNotBlank() } ?: env[varName]?.takeIf { it.isNotBlank() }

            val baseUrl = (pick("--base-url", "P2P_BASE_URL") ?: DEFAULT_BASE_URL)
                .let { if (it.startsWith("http")) it else "http://$it" }
                .trimEnd('/')
            val network = (pick("--network", "P2P_NETWORK") ?: DEFAULT_NETWORK).lowercase()
            require(network == DEFAULT_NETWORK || network == TESTNET) {
                "network must be $DEFAULT_NETWORK or $TESTNET, got '$network'"
            }
            return TuiConfig(
                baseUrl = baseUrl,
                operatorKey = pick("--operator-key", "P2P_OPERATOR_KEY"),
                network = network,
                explorerUrl = pick("--explorer-url", "P2P_EXPLORER_URL"),
                nodeUrl = pick("--node-url", "P2P_NODE_URL"),
            )
        }

        /** `--flag value` and `--flag=value`; a bare `--flag` maps to `""`. */
        internal fun parseFlags(args: Array<String>): Map<String, String> {
            val out = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                if (arg.startsWith("--")) {
                    val eq = arg.indexOf('=')
                    if (eq >= 0) {
                        out[arg.substring(0, eq)] = arg.substring(eq + 1)
                    } else if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        out[arg] = args[i + 1]
                        i++
                    } else {
                        out[arg] = ""
                    }
                }
                i++
            }
            return out
        }
    }
}
