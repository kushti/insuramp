package p2pgate.app.data

import org.junit.jupiter.api.Test
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.dealprotocol.QrPayload
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Incoming link resolution (`onramp-ux.md` §7 — every deal link opens straight
 * into the app). The handoff case is the load-bearing one: the seller shows a
 * QR, the buyer's phone opens the app, and the app has to land on the meeting
 * screen *for the deal inside the record* without the seller handing over a
 * second link.
 */
class DeepLinksSpec {

    private val record = HandoffRecord(
        dealId = ByteArray(32) { (it * 7 + 1).toByte() },
        amount = 15_600L,
        fiatCurrency = "EGP".toByteArray(Charsets.US_ASCII),
        timestamp = 1_760_000_000L,
    )

    private val handoffPayload = QrPayload.encodeHandoff(record)

    @Test
    fun `a handoff QR resolves to the deal named in the record`() {
        val link = resolveDeepLink(handoffPayload)
        val handoff = assertIs<DeepLink.Handoff>(link)
        assertEquals(handoffPayload, handoff.payload)
        // The record's deal id is the operator-issued id (blake2b of the terms).
        assertEquals(p2pgate.app.verify.Hex.encode(record.dealId), handoff.dealId)
    }

    @Test
    fun `a deal link with a recovery token resolves to recovery`() {
        val link = "https://operator.example/deal/abc123#tok-456"
        assertEquals(DeepLink.RecoverDeal(link), resolveDeepLink(link))
    }

    @Test
    fun `an unparseable handoff payload degrades instead of throwing`() {
        // A truncated/corrupt m parameter must not crash a cold start.
        assertIs<DeepLink.Unknown>(resolveDeepLink("p2pgate://handoff?m=not-base64!!"))
        assertIs<DeepLink.Unknown>(resolveDeepLink("p2pgate://handoff?m="))
    }

    @Test
    fun `unrelated and empty links resolve to unknown`() {
        assertIs<DeepLink.Unknown>(resolveDeepLink(null))
        assertIs<DeepLink.Unknown>(resolveDeepLink(""))
        assertIs<DeepLink.Unknown>(resolveDeepLink("   "))
        assertIs<DeepLink.Unknown>(resolveDeepLink("p2pgate://"))
        assertIs<DeepLink.Unknown>(resolveDeepLink("https://example.com/whatever"))
    }

    @Test
    fun `a handoff payload is never mistaken for a deal link`() {
        // base64url has no '#', so the fragment rule cannot swallow a QR.
        assert(!handoffPayload.contains('#'))
    }
}
