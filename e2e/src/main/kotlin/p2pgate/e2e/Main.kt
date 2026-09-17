package p2pgate.e2e

/**
 * Entry point of the e2e gate (milestone M3-C). Mainnet is the default target
 * since 2026-09-17; testnet stays selectable via `E2E_EXPLORER_URL` +
 * `E2E_FAUCET_URL` (the faucet is testnet-only, default `off` = manual funding).
 *
 * Usage: `./gradlew :e2e:run [--args='--dry-run']`
 *
 * Exit codes: 0 — all flows passed (or `--dry-run` builds passed);
 * 1 — unexpected failure; 2 — manual action needed (explorer unreachable,
 * funding deadline passed) — instructions are printed.
 */
fun main(args: Array<String>) {
    val dryRun = args.contains("--dry-run")
    val config = E2eConfig(dryRun = dryRun)
    val transport = HttpTransport(timeoutSeconds = config.preflightTimeoutSeconds)
    val gateway = E2eExplorer(config.explorerBaseUrl, transport)
    val faucet = if (config.faucetEnabled) HttpFaucet(config.faucetBaseUrl, transport) else Faucet {
        FaucetResult.Unavailable("faucet disabled via E2E_FAUCET_URL=off")
    }

    val start = System.currentTimeMillis()
    val code = try {
        E2eFlow(config, gateway, faucet).run()
    } catch (e: E2eFlow.E2eException) {
        println("e2e gate aborted: ${e.message}")
        1
    } catch (e: Exception) {
        println("e2e gate crashed: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
        1
    }
    println("total runtime: ${(System.currentTimeMillis() - start) / 1000}s, exit code $code")
    System.exit(code)
}
