package p2pgate.tui.buyer

import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.runMosaicBlocking
import java.io.File
import org.ergoplatform.appkit.NetworkType
import p2pgate.ergo.ChainSource
import p2pgate.ergo.ClaimTxBuilder
import p2pgate.ergo.ErgoContracts
import p2pgate.ergo.ExplorerChainSource
import p2pgate.ergo.NodeChainSource
import p2pgate.tui.common.KtorBackendClient
import p2pgate.tui.common.TuiConfig

/**
 * The buyer console's entry point (`specs/tui-apps.md` §5): the terminal
 * counterpart to the Android app, with the two things the app lacks — it builds
 * and broadcasts its own claim transactions, and it holds the seller's public key
 * from the chain rather than from the backend.
 *
 * **Key custody** lives in one passphrase-encrypted file (see [KeyVault]), needed
 * for exactly one operation: the path-D payout. The path-B claim-open is
 * `sigmaProp`-only, so a console with no key file can still open a claim.
 *
 * Run it from a real terminal — Mosaic needs a TTY, so `./gradlew run` and IDE
 * run-configs will not render:
 * `./gradlew :tui:buyer:installDist && tui/buyer/build/install/buyer/bin/buyer`
 *
 * Configuration (`P2P_*` env or flags): `P2P_BASE_URL`, `P2P_NETWORK` (`mainnet`
 * default), `P2P_EXPLORER_URL`, `P2P_NODE_URL`, plus `P2P_KEY_FILE`,
 * `P2P_HANDOFF_FILE`, `P2P_ORACLE_NFT_ID`, `P2P_PAYOUT_ADDRESS` (the buyer's TRON USDT address —
 * not the Ergo key) and `P2P_KEY_PASSPHRASE`. The last is for
 * automation only — anything that can read the process environment can read the
 * passphrase.
 */
fun main() {
    val config = TuiConfig.resolve()
    val networkType = if (config.isTestnet) NetworkType.TESTNET else NetworkType.MAINNET

    val keyFile = File(System.getenv("P2P_KEY_FILE") ?: defaultKeyFile())
    val handoffFile = File(System.getenv("P2P_HANDOFF_FILE") ?: defaultHandoffFile())

    // Everything needing a passphrase happens HERE, before Mosaic starts. Mosaic
    // owns stdin once the loop begins, so a prompt issued from inside it renders
    // and then never receives the keystrokes — a bug this console actually had.
    val sessionSecret = openKeyFile(keyFile, networkType)

    val controller = BuyerController(
        client = KtorBackendClient(config),
        chain = buildChain(config),
        submitter = TxSubmitter { tx -> tx.id },
        // The compiled trees must match the vault the operator actually deployed,
        // or the builder rejects the box it is handed. The oracle NFT id is a
        // compile-time pin of the PAYMENT_PROVEN tree, so it has to be the real
        // one -- see `oracleNftId` for why an unset variable is called out loudly.
        txBuilder = ClaimTxBuilder(
            trees = ErgoContracts.compile(
                oracleNftId = oracleNftId(),
                networkPrefix = networkPrefix(config),
            ),
        ),
        keyFile = keyFile,
        handoffFile = handoffFile,
        networkType = networkType,
        sessionSecret = sessionSecret,
        payoutAddress = System.getenv("P2P_PAYOUT_ADDRESS")?.trim()?.takeIf { it.isNotEmpty() },
    )

    runMosaicBlocking {
        Column(
            modifier = Modifier.onKeyEvent { event ->
                if (event.key == "q") {
                    // Overwrite before exit: the secret sat in this process's heap.
                    sessionSecret?.fill(0)
                    kotlin.system.exitProcess(0)
                }
                false
            },
        ) {
            Text("p2pgate buyer console — ${config.baseUrl}", textStyle = TextStyle.Bold)
            BuyerScreen(controller)
        }
    }
}

/**
 * Unlocks [keyFile] once, before the terminal loop — creating one first if asked —
 * and returns the secret. Returns `null` for a keyless console, which can still
 * open a claim.
 *
 * The passphrase is collected here for the reason above: Mosaic owns stdin once it
 * starts, so this is the only point at which a keystroke can be read.
 */
private fun openKeyFile(keyFile: File, networkType: NetworkType): ByteArray? {
    if (!KeyVault.exists(keyFile)) {
        if (System.console() == null) return null
        System.err.println(
            "No key file at ${keyFile.path}.\n" +
                "The claim-open path needs none, but the path-D payout does.\n" +
                "Create one now? [y/N] ",
        )
        if (readLine()?.trim()?.equals("y", ignoreCase = true) != true) return null
        if (!writeNewKey(keyFile, networkType)) return null
    }
    // `P2P_KEY_PASSPHRASE` is the automation path; otherwise prompt with no echo.
    val passphrase = runCatching { readPassphrase("Passphrase for ${keyFile.path}: ") }
        .getOrElse {
            System.err.println("${it.message} — running keyless (a claim can still be opened)")
            return null
        }
    return try {
        KeyVault.readSecret(keyFile, passphrase)
    } catch (e: KeyVault.UnlockFailed) {
        System.err.println("${e.message} — running keyless (a claim can still be opened)")
        null
    } finally {
        passphrase.fill('\u0000')
    }
}

/** Creates a key file and its public sidecar. Returns whether it worked. */
private fun writeNewKey(keyFile: File, networkType: NetworkType): Boolean {
    val secret = BuyerKeys.generateSecret()
    val address = BuyerKeys.address(secret, networkType)
    val passphrase = runCatching { readPassphrase("Passphrase for the new key file: ") }
        .getOrElse {
            System.err.println("${it.message} — running keyless")
            secret.fill(0)
            return false
        }
    return try {
        KeyVault.write(keyFile, passphrase, secret, address)
        // The sidecar lets a restarted console know its own address without a
        // passphrase prompt; see `KeyVault.PublicInfo`.
        KeyVault.writePublicInfo(keyFile, address, fingerprintOf(secret))
        System.err.println("wrote $address to ${keyFile.path}")
        true
    } catch (e: KeyVault.UnlockFailed) {
        System.err.println("${e.message} — running keyless")
        false
    } finally {
        passphrase.fill('\u0000')
        secret.fill(0)
    }
}

/**
 * The chain reader. A node URL means the node's extra-indexer API (private, and
 * it does not leak the vault set to a third party); otherwise the explorer client.
 * Same precedence as the backend's `P2P_CHAIN_SOURCE=node`.
 *
 * Neither source takes a network -- the network is whichever one the base URL
 * points at, which is why the defaults differ per network here.
 */
private fun buildChain(config: TuiConfig): ChainSource {
    val urls = config.nodeUrl
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return if (urls.isNotEmpty()) {
        NodeChainSource(urls)
    } else {
        ExplorerChainSource(
            baseUrl = config.explorerUrl
                ?: if (config.isTestnet) ExplorerChainSource.TESTNET_BASE_URL
                else ExplorerChainSource.MAINNET_BASE_URL,
        )
    }
}

/**
 * The deployed oracle's NFT id from `P2P_ORACLE_NFT_ID` (64 hex chars), or the
 * test dummy. An unset variable is the case that matters: the console then
 * refuses a real vault box with "input box is not a FUNDED vault box of this
 * contract", which is correct but opaque — so it is called out on stderr at
 * startup rather than left to be discovered at claim time.
 */
private fun oracleNftId(): ByteArray {
    val hex = System.getenv("P2P_ORACLE_NFT_ID")?.trim()
    if (hex.isNullOrEmpty()) {
        System.err.println(
            "P2P_ORACLE_NFT_ID is not set — compiling with the test dummy oracle NFT.\n" +
                "  A claim-open will build, but a real vault box will be rejected.\n" +
                "  Set it to the deployed oracle NFT id (64 hex chars) before claiming.\n",
        )
        return ErgoContracts.DUMMY_ORACLE_NFT_ID
    }
    require(hex.length == 64) { "P2P_ORACLE_NFT_ID must be 64 hex chars, got ${hex.length}" }
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun networkPrefix(config: TuiConfig): Byte =
    if (config.isTestnet) {
        p2pgate.contracts.ContractParams.NETWORK_PREFIX_TESTNET
    } else {
        p2pgate.contracts.ContractParams.NETWORK_PREFIX_MAINNET
    }

private fun defaultKeyFile(): String =
    "${System.getProperty("user.home")}/.p2pgate/buyer.p2pkey"

private fun defaultHandoffFile(): String =
    "${System.getProperty("user.home")}/.p2pgate/handoff.json"