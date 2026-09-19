package p2pgate.backend.oracle

import p2pgate.ergo.ChainBox
import p2pgate.ergo.DevOracle
import p2pgate.ergo.PaymentAttestation
import java.util.concurrent.ConcurrentHashMap

/**
 * Payment-proof oracle seam, `specs/operator-backend.md` §2 "oracle client":
 * the backend never observes Tron/Ethereum itself — it consumes attestation
 * status for funded deals through this interface. M3 wires [DevOracle] in
 * tests/dev; production swaps in a remote client talking to the attestation
 * API (`POST /v1/attestations`) without touching the vault manager.
 *
 * [attestationFor] throws [OracleUnavailableException] when the oracle cannot
 * be queried — callers treat that exactly like "no attestation yet" on the
 * buyer-facing path, and as a degradation signal for the infra monitor.
 *
 * ## How a release consumes the attestation
 *
 * The vault contracts read the attestation from the oracle box's R4 via
 * `CONTEXT.dataInputs(0)` — the oracle box is a DATA INPUT of the
 * release/contest tx and its script never executes, so there is no oracle
 * co-signature to collect. [attestationBoxFor] hands the vault manager that
 * data-input box per deal (the on-chain box the oracle posted by rotating its
 * `oracle.es` singleton with R4 = the payload); release txs are
 * operator-wallet-only, funded from the vault signer's fee inputs.
 */
interface OracleClient {
    /** The attestation for [dealId] (hex), or `null` while unobserved. */
    fun attestationFor(dealId: String): PaymentAttestation?

    /**
     * The oracle box carrying [dealId]'s attestation as a release-ready data
     * input (NFT + R4 payload), or `null` while no attestation is posted.
     */
    fun attestationBoxFor(dealId: String): ChainBox?

    /** Liveness probe for the infra monitor's ORACLE_LAG signal. */
    fun reachable(): Boolean
}

class OracleUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * M3 default: an in-process [DevOracle] plus a registry of minted
 * attestations keyed by deal id. Nothing here observes a real source chain —
 * tests and the dev harness assert a seller transfer and call [attest].
 */
class DevOracleClient(
    private val devOracle: DevOracle,
) : OracleClient {
    private val attestations = ConcurrentHashMap<String, PaymentAttestation>()

    @Volatile
    private var up: Boolean = true

    /** Dev/test only: records (or replaces) the attestation for a deal. */
    fun attest(dealId: String, attestation: PaymentAttestation) {
        attestations[dealId] = attestation
    }

    fun drop(dealId: String) {
        attestations.remove(dealId)
    }

    /** Simulates an outage for the infra monitor / fail-closed paths. */
    fun setReachable(reachable: Boolean) {
        up = reachable
    }

    override fun attestationFor(dealId: String): PaymentAttestation? {
        if (!up) throw OracleUnavailableException("oracle unreachable")
        return attestations[dealId]
    }

    override fun attestationBoxFor(dealId: String): ChainBox? {
        if (!up) throw OracleUnavailableException("oracle unreachable")
        val attestation = attestations[dealId] ?: return null
        return devOracle.attestationBox(attestation)
    }

    override fun reachable(): Boolean = up
}
