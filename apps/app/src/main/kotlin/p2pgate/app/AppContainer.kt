package p2pgate.app

import android.content.Context
import androidx.room.Room
import p2pgate.app.BuildConfig
import p2pgate.app.data.DealDatabase
import p2pgate.app.data.DealRepository
import p2pgate.app.data.RoomDealSnapshotStore
import p2pgate.app.keys.AndroidKeystoreDealKeyStore
import p2pgate.app.keys.DealKeyStore
import p2pgate.app.net.BackendClient
import p2pgate.app.net.KtorBackendClient

/**
 * Manual composition root (no DI framework, keeps ViewModels
 * constructor-injected and unit-testable). The operator backend base URL is
 * overridable for testnet/dev via `p2pgate.backend_url` in SharedPreferences.
 */
class AppContainer(
    val backendClient: BackendClient,
    val dealRepository: DealRepository,
    val dealKeyStore: DealKeyStore,
    /** The `p2pgate_settings` prefs (backend_url, explicit fiat currency pick). */
    val settings: android.content.SharedPreferences,
) {
    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppContainer {
            val prefs = context.getSharedPreferences("p2pgate_settings", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("backend_url", null) ?: DEFAULT_BACKEND_URL
            val backend = KtorBackendClient(baseUrl)
            val db = Room.databaseBuilder(context, DealDatabase::class.java, "p2pgate-deals.db").build()
            val repository = DealRepository(backend, RoomDealSnapshotStore(db))
            return AppContainer(
                backendClient = backend,
                dealRepository = repository,
                dealKeyStore = AndroidKeystoreDealKeyStore(context),
                settings = prefs,
            )
        }

        /** Backend default; debug builds point at the emulator's host loopback. */
        const val DEFAULT_BACKEND_URL = BuildConfig.BACKEND_URL
    }
}
