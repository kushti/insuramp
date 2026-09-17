package p2pgate.backend.aml

import p2pgate.backend.util.Hex

/**
 * AML pre-check hook, `specs/operator-backend.md` §6 — off-chain by design, the
 * one irreducible piece of off-chain reputation. Scored at QUOTED on the buyer's
 * declared USDT **receive** address; a re-check fires when the address seen at
 * funding differs from the declared one (address-swap defense).
 *
 * Hard rules from the spec:
 *  - **Decision-only recording** — a deal records the accept/reject decision,
 *    the scorer identifier and a timestamp. The risk report itself is never
 *    stored, never logged, never proxied to any client.
 *  - **Fail-closed** — a scorer that cannot be reached is a rejection of
 *    *funding* (the deal may exist, it must not fund). Callers catch
 *    [RiskScorerException] and treat it as "no funding".
 */
enum class AmlDecision { ACCEPT, REJECT }

/** The scorer is unreachable or misbehaving — funding must halt (fail-closed). */
class RiskScorerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface RiskScorer {
    val scorerId: String

    /**
     * Scores [address] (the raw source-chain address payload of the buyer's
     * declared USDT receive address) on chain [chainId] (the deal-terms wire
     * id). Throws [RiskScorerException] when the scorer cannot answer.
     */
    fun score(address: ByteArray, chainId: Int): AmlDecision
}

/**
 * Config-driven stub (`specs/operator-backend.md` §6 "stub for manual
 * review"): exact hex addresses map to a decision, everything else falls back
 * to [defaultDecision]. Ships as the M3 default; the HTTP adapter below is the
 * production seam.
 */
class ConfigRiskScorer(
    private val decisions: Map<String, AmlDecision> = emptyMap(),
    private val defaultDecision: AmlDecision = AmlDecision.ACCEPT,
    override val scorerId: String = "config-stub",
) : RiskScorer {
    override fun score(address: ByteArray, chainId: Int): AmlDecision =
        decisions[Hex.encode(address).lowercase()] ?: defaultDecision
}

/**
 * HTTP adapter shape for an external risk-scoring provider. The transport is
 * `(url) -> response body`, exactly the seam `ExplorerChainSource` uses, so a
 * Tor/proxy/fake transport drops in. Contract: the provider returns a JSON
 * object with a `"decision"` field of `"ACCEPT"` or `"REJECT"`. M3 ships this
 * as the integration shape only — the suite never talks to a real provider;
 * any transport or parse failure surfaces as [RiskScorerException]
 * (fail-closed at the caller).
 */
class HttpRiskScorer(
    private val endpoint: String,
    private val transport: (url: String) -> String,
    override val scorerId: String = "http-adapter",
) : RiskScorer {

    override fun score(address: ByteArray, chainId: Int): AmlDecision {
        val url = "$endpoint?address=${Hex.encode(address)}&chain=$chainId"
        val body = try {
            transport(url)
        } catch (e: RiskScorerException) {
            throw e
        } catch (e: Exception) {
            throw RiskScorerException("AML scorer unreachable at $endpoint", e)
        }
        return try {
            parseDecision(body)
        } catch (e: RiskScorerException) {
            throw e
        } catch (e: Exception) {
            throw RiskScorerException("AML scorer returned an unreadable response", e)
        }
    }

    private fun parseDecision(body: String): AmlDecision {
        val match = Regex("\\\"decision\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"").find(body)
            ?: throw RiskScorerException("AML scorer response has no decision field")
        return when (match.groupValues[1]) {
            "ACCEPT" -> AmlDecision.ACCEPT
            "REJECT" -> AmlDecision.REJECT
            else -> throw RiskScorerException("AML scorer returned unknown decision ${match.groupValues[1]}")
        }
    }
}
