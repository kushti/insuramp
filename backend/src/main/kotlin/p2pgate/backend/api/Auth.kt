package p2pgate.backend.api

import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.bus.EventBus
import p2pgate.backend.util.Crypto
import p2pgate.backend.util.Hex
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bearer-token issuance and verification, `specs/operator-backend.md` §9:
 *
 *  - **deal-scoped tokens** — minted at deal creation (returned as
 *    `dealToken`), authorize the buyer-facing deal endpoints, expire at close;
 *  - **operator key** — long-lived, dashboard-only; vault-signing keys never
 *    leave the vault manager.
 *
 * Only SHA-256 hashes of tokens are stored; verification compares digests
 * with [MessageDigest.isEqual] (constant-time). Token death at close is
 * driven by subscribing to terminal [BackendEvent.DealTransitioned]s.
 */
class TokenService {
    sealed interface Scope {
        data class DealToken(val dealId: String) : Scope
        data object OperatorToken : Scope
    }

    private data class Entry(val digest: ByteArray, val scope: Scope)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val random = java.security.SecureRandom()

    /** Mints a raw token for [scope]; the raw value is shown to the caller exactly once. */
    fun mint(scope: Scope): String {
        val raw = ByteArray(32).also(random::nextBytes)
        entries += Entry(Crypto.sha256(raw), scope)
        return Hex.encode(raw)
    }

    fun verifyDeal(raw: String?, dealId: String): Boolean = matches(raw) { it is Scope.DealToken && it.dealId == dealId }

    fun verifyOperator(raw: String?): Boolean = matches(raw) { it is Scope.OperatorToken }

    /** Kills all tokens scoped to a closed deal. */
    fun revokeDeal(dealId: String) {
        entries.removeIf {
            (it.scope as? Scope.DealToken)?.dealId == dealId
        }
    }

    private fun matches(raw: String?, predicate: (Scope) -> Boolean): Boolean {
        if (raw.isNullOrEmpty()) return false
        val digest = try {
            Crypto.sha256(Hex.decode(raw))
        } catch (e: IllegalArgumentException) {
            return false
        }
        return entries.any { e -> predicate(e.scope) && MessageDigest.isEqual(e.digest, digest) }
    }

    /** Revokes deal tokens automatically when the deal reaches a terminal state. */
    fun subscribeToClosures(bus: EventBus) {
        bus.subscribe { event ->
            if (event is BackendEvent.DealTransitioned && event.to.isTerminal) revokeDeal(event.dealId)
        }
    }
}
