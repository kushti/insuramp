package p2pgate.app

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import p2pgate.app.claim.ClaimScreen
import p2pgate.app.deal.DealTimelineScreen
import p2pgate.app.handoff.HandoffScreen
import p2pgate.app.locale.resolveSupportedTag
import p2pgate.app.quotes.QuoteScreen
import p2pgate.app.recover.RecoverScreen
import p2pgate.app.ui.theme.P2PGateTheme
import p2pgate.app.welcome.WelcomeGate
import p2pgate.app.welcome.WelcomeScreen

/**
 * Single-activity shell: five screens, one timeline per deal, plus the
 * once-after-install welcome screen as the start destination until dismissed.
 * Deal entry is link-based (`onramp-ux.md` §7) — an opened deal link lands on
 * recovery.
 *
 * An [AppCompatActivity] so `AppCompatDelegate.setApplicationLocales` applies
 * the in-app language choice pre-API-33 and persists it across process death.
 */
class MainActivity : AppCompatActivity() {

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
        val deepLink = intent?.dataString
        setContent {
            P2PGateTheme {
                P2PGateNav(container, deepLink, showWelcome, welcomeGate)
            }
        }
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
    const val HANDOFF = "deal/{dealId}/handoff"
    const val CLAIM = "deal/{dealId}/claim"
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

    // An incoming deal link pre-fills recovery; a bare p2pgate link goes home.
    // Deal entry takes priority even over a not-yet-dismissed welcome.
    LaunchedEffect(deepLink) {
        if (deepLink != null && deepLink.contains("#")) {
            navController.navigate("recover?link=${android.net.Uri.encode(deepLink)}")
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
                        selected = currentRoute?.startsWith("recover") == true,
                        onClick = { navController.navigate(Routes.RECOVER) },
                        label = { Text(stringResource(R.string.nav_recover)) },
                        icon = { Text("②") },
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
            composable(Routes.HANDOFF) { entry ->
                val dealId = entry.arguments?.getString("dealId") ?: return@composable
                HandoffScreen(dealId = dealId, container = container)
            }
            composable(Routes.CLAIM) { entry ->
                val dealId = entry.arguments?.getString("dealId") ?: return@composable
                ClaimScreen(dealId = dealId, container = container)
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
