package p2pgate.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import p2pgate.app.claim.ClaimScreen
import p2pgate.app.data.DeepLink
import p2pgate.app.data.resolveDeepLink
import p2pgate.app.deal.DealTimelineScreen
import p2pgate.app.deals.DealListScreen
import p2pgate.app.handoff.HandoffScreen
import p2pgate.app.locale.resolveSupportedTag
import p2pgate.app.quotes.QuoteScreen
import p2pgate.app.recover.RecoverScreen
import p2pgate.app.settings.SettingsScreen
import p2pgate.app.ui.theme.P2PGateTheme
import p2pgate.app.welcome.WelcomeGate
import p2pgate.app.welcome.WelcomeScreen

/**
 * Single-activity shell: the quote screen, one timeline per deal, the meeting
 * and claim screens, the deal list and recovery, plus the once-after-install
 * welcome screen as the start destination until dismissed.
 *
 * Deal entry is link-based (`onramp-ux.md` §7) — an opened deal link lands on
 * recovery, and the seller's handoff QR lands on the meeting screen for the
 * deal named inside the record. The manifest declares `singleTask`, so a second
 * link arrives through [onNewIntent] and is re-resolved here rather than
 * dropped (the previous behaviour lost every link after the first).
 *
 * An [AppCompatActivity] so `AppCompatDelegate.setApplicationLocales` applies
 * the in-app language choice pre-API-33 and persists it across process death.
 */
class MainActivity : AppCompatActivity() {

    /**
     * The most recent incoming link. Compose holds navigation, so a re-entry has
     * to be observable state — a plain `val` would never reach the NavHost.
     */
    private var incomingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        detectFirstRunLocale()
        super.onCreate(savedInstanceState)
        val container = AppContainer.get(this)
        val welcomeGate = WelcomeGate(
            seen = { container.settings.getBoolean(WelcomeGate.PREF_KEY, false) },
            mark = { container.settings.edit().putBoolean(WelcomeGate.PREF_KEY, true).apply() },
        )
        // The start-destination decision is taken once at process start; the
        // flag is persisted, so process death / reboot never re-shows a
        // dismissed welcome.
        val showWelcome = welcomeGate.shouldShow()
        incomingLink = intent?.dataString
        setContent {
            P2PGateTheme {
                P2PGateNav(container, incomingLink, showWelcome, welcomeGate)
            }
        }
    }

    /**
     * `singleTask` re-entry: a second deal link or handoff QR (the seller
     * sending the record while the app is already open) is handed to the running
     * graph instead of starting a second activity that would then be finished.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLink = intent.dataString
    }

    /**
     * First-run locale detection: when the user has never chosen a language
     * (the delegate's persisted locales are empty), pin the supported locale
     * matching the system language. Idempotent — once the app locales are set
     * (by this detection or by an explicit picker choice) they are never
     * overwritten here. Resources would fall back the same way; the explicit
     * pin lets the quote screen read the locale in effect for its
     * currency default.
     */
    private fun detectFirstRunLocale() {
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) return
        val systemLanguage = LocaleListCompat.getAdjustedDefault().get(0)?.language
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(resolveSupportedTag(systemLanguage)),
        )
    }
}

private object Routes {
    const val WELCOME = "welcome"
    const val QUOTE = "quote"
    const val DEAL = "deal/{dealId}"
    /** Opened from the deal screen: the buyer scans or pastes the record there. */
    const val HANDOFF = "deal/{dealId}/handoff"
    /** Opened from a `p2pgate://handoff?m=…` link: the record is pre-filled. */
    const val HANDOFF_WITH_RECORD = "deal/{dealId}/handoff?m={m}"
    const val CLAIM = "deal/{dealId}/claim"
    const val DEALS = "deals"
    const val SETTINGS = "settings"
    const val RECOVER = "recover?link={link}"
}

@Composable
fun P2PGateNav(
    container: AppContainer,
    deepLink: String?,
    showWelcome: Boolean,
    welcomeGate: WelcomeGate,
) {
    val navController = rememberNavController()

    // An incoming link routes once per distinct link: a deal link pre-fills
    // recovery, the seller's handoff QR opens the meeting screen for the deal
    // named in the record. A bare p2pgate link goes home.
    LaunchedEffect(deepLink) {
        when (val link = resolveDeepLink(deepLink)) {
            is DeepLink.RecoverDeal ->
                navController.navigate("recover?link=${android.net.Uri.encode(link.link)}")
            is DeepLink.Handoff -> navController.navigate(
                "deal/${link.dealId}/handoff?m=${android.net.Uri.encode(link.payload)}",
            )
            DeepLink.Unknown -> Unit
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // The welcome screen stands alone — no navigation chrome on it.
            if (currentRoute != Routes.WELCOME) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.QUOTE,
                        onClick = {
                            navController.navigate(Routes.QUOTE) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                        label = { Text(stringResource(R.string.nav_quote)) },
                        icon = { Text("①") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.DEALS,
                        onClick = {
                            navController.navigate(Routes.DEALS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                        label = { Text(stringResource(R.string.nav_deals)) },
                        icon = { Text("②") },
                    )
                    NavigationBarItem(
                        selected = currentRoute?.startsWith("recover") == true,
                        onClick = { navController.navigate(Routes.RECOVER) },
                        label = { Text(stringResource(R.string.nav_recover)) },
                        icon = { Text("③") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = { navController.navigate(Routes.SETTINGS) },
                        label = { Text(stringResource(R.string.nav_settings)) },
                        icon = { Text("④") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (showWelcome) Routes.WELCOME else Routes.QUOTE,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.WELCOME) {
                WelcomeScreen(
                    onGetStarted = {
                        welcomeGate.markSeen()
                        navController.navigate(Routes.QUOTE) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.QUOTE) {
                QuoteScreen(container = container, onDealCreated = { dealId ->
                    navController.navigate("deal/$dealId")
                })
            }
            composable(Routes.DEAL) { entry ->
                val dealId = entry.arguments?.getString("dealId") ?: return@composable
                DealTimelineScreen(
                    dealId = dealId,
                    container = container,
                    onOpenHandoff = { navController.navigate("deal/$dealId/handoff") },
                    onOpenClaim = { navController.navigate("deal/$dealId/claim") },
                    onBackToQuotes = { navController.popBackStack(Routes.QUOTE, inclusive = false) },
                )
            }
            // The meeting screen, opened either from the deal screen (no record
            // yet) or from the seller's handoff link (record pre-filled).
            listOf(Routes.HANDOFF, Routes.HANDOFF_WITH_RECORD).forEach { route ->
                composable(route) { entry ->
                    val dealId = entry.arguments?.getString("dealId") ?: return@composable
                    HandoffScreen(
                        dealId = dealId,
                        container = container,
                        prefill = entry.arguments?.getString("m"),
                    )
                }
            }
            composable(Routes.CLAIM) { entry ->
                val dealId = entry.arguments?.getString("dealId") ?: return@composable
                ClaimScreen(dealId = dealId, container = container)
            }
            composable(Routes.DEALS) {
                DealListScreen(container = container, onOpenDeal = { dealId ->
                    navController.navigate("deal/$dealId")
                })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(container = container)
            }
            composable(Routes.RECOVER) { entry ->
                val link = entry.arguments?.getString("link")
                RecoverScreen(
                    container = container,
                    initialLink = link,
                    onRecovered = { dealId -> navController.navigate("deal/$dealId") },
                )
            }
        }
    }
}
