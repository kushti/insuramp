package p2pgate.tui.seller

import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.runMosaicBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import p2pgate.tui.common.KtorBackendClient
import p2pgate.tui.common.TuiConfig

/**
 * The operator console's entry point (`specs/operator-backend.md` §2, "seller
 * side"): the terminal counterpart of the web dashboard at `/dashboard/`, talking
 * to the same `/v1` APIs. It holds no keys — every seller-signed transaction is
 * built and signed inside the backend's `VaultSigner` seam, so this process can
 * only ask for actions.
 *
 * Run it from a real terminal: `./gradlew :tui:seller:installDist` then
 * `tui/seller/build/install/seller/bin/seller`. Mosaic needs a TTY, so
 * `./gradlew run` and IDE run-configs will not render.
 *
 * Configuration (`P2P_*` env or flags): `P2P_BASE_URL`, `P2P_OPERATOR_KEY`.
 */
fun main() {
    val config = TuiConfig.resolve()
    if (config.operatorKey.isNullOrBlank()) {
        System.err.println(
            "p2pgate-seller: P2P_OPERATOR_KEY is not set.\n" +
                "  The lane, pool, quotes, disputes and infra endpoints all require the operator key\n" +
                "  (an operator backend started without it is demo-open; see specs/operator-backend.md §9).",
        )
        kotlin.system.exitProcess(2)
    }

    val client = KtorBackendClient(config)
    val controller = SellerController(client, CoroutineScope(Dispatchers.Default))

    runMosaicBlocking {
        Column(
            modifier = Modifier.onKeyEvent { event ->
                // 'q' quits from anywhere; the board itself ignores it.
                if (event.key == "q") {
                    controller.stop()
                    kotlin.system.exitProcess(0)
                }
                false
            },
        ) {
            Text("p2pgate seller console — ${config.baseUrl}", textStyle = TextStyle.Bold)
            SellerScreen(controller)
        }
    }
}
