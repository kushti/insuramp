package p2pgate.app.data

/**
 * Deal-link parsing (`specs/android-app.md` §5): the recovery link is
 * `https://<operator>/deal/<dealId>#<random-token>` — the fragment authorizes
 * status reads. Paste-based recovery accepts the full link, a
 * `p2pgate://deal/<dealId>#<token>` variant, or the bare `dealId#token`.
 */
object DealLinkParser {

    data class DealLink(val dealId: String, val token: String)

    fun parse(input: String): DealLink {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "empty deal link" }

        val withoutFragment = trimmed.substringBefore('#')
        val token = trimmed.substringAfter('#', "")
        require(token.isNotEmpty()) { "deal link is missing the recovery token" }

        val path = withoutFragment
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("p2pgate://deal/")
            .removePrefix("p2pgate://")
            .trimEnd('/')
        val dealId = path.substringAfterLast("/deal/", path.substringAfterLast('/'))
        require(dealId.isNotEmpty()) { "deal link is missing the deal id" }
        return DealLink(dealId, token)
    }
}
