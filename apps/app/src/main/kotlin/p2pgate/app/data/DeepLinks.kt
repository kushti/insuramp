package p2pgate.app.data

import p2pgate.app.verify.Hex
import p2pgate.dealprotocol.QrPayload

/**
 * What an incoming `p2pgate://` / `https://<operator>/deal/…` link asks the app
 * to open (`onramp-ux.md` §7: "every deal link opens straight into the app").
 *
 * Pure and JVM-testable: [MainActivity][p2pgate.app.MainActivity] only turns a
 * resolved link into a navigation route, so a malformed QR or a pasted junk
 * string can never crash the activity.
 */
sealed interface DeepLink {

    /**
     * A deal link carrying the recovery token in its fragment
     * (`https://<operator>/deal/<id>#<token>`) — the buyer pastes or opens it on
     * a fresh install to recover a deal (`specs/android-app.md` §6.3).
     */
    data class RecoverDeal(val link: String) : DeepLink

    /**
     * The seller's handoff QR (`p2pgate://handoff?m=<base64url(record)>`,
     * `specs/deal-protocol.md` §3.3). The record carries the deal id, so the
     * app can open the meeting screen for the right deal straight from the
     * scan — the seller does not have to hand over a link too.
     */
    data class Handoff(val payload: String, val dealId: String) : DeepLink

    /** Anything else (a bare `p2pgate://` open, an unrelated https link). */
    data object Unknown : DeepLink
}

/**
 * Resolves an incoming intent's data string. The record inside a handoff QR is
 * base64url and cannot contain `#`, so the deal-link fragment check is safe to
 * run first; an unparseable handoff payload degrades to [DeepLink.Unknown]
 * rather than throwing out of a cold start.
 */
fun resolveDeepLink(uri: String?): DeepLink {
    if (uri.isNullOrBlank()) return DeepLink.Unknown
    if (uri.contains('#')) return DeepLink.RecoverDeal(uri)
    if (uri.startsWith(HANDOFF_PREFIX)) {
        val record = runCatching { QrPayload.decodeHandoff(uri) }.getOrNull() ?: return DeepLink.Unknown
        return DeepLink.Handoff(payload = uri, dealId = Hex.encode(record.dealId))
    }
    return DeepLink.Unknown
}

private const val HANDOFF_PREFIX = "p2pgate://handoff?m="
