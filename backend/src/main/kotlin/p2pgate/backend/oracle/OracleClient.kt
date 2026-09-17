package p2pgate.backend.oracle

import p2pgate.ergo.ChainBox
import p2pgate.ergo.DevOracle
import p2pgate.ergo.OracleSigner
import p2pgate.ergo.PaymentAttestation
import java.util.concurrent.ConcurrentHashMap

/**
 * Payment-proof oracle seam, `specs/operator-backend.md` §2 "oracle client":
 * the backend never observes Tron/Ethereum itself — it consumes attestation
 * status for funded deals through this interface. The [signer] is the
 * production seam of `specs/oracle-integration.md` §3.1: M3 wires [DevOracle]
 * in tests/dev; production swaps in a remote signer talking to the
 * attestation API without touching the vault manager.
 *
 * [attestationFor] throws [OracleUnavailableException] when the oracle cannot
 * be queried — callers treat that exactly like "no attestation yet" on the
 * buyer-facing path, and as a degradation signal for the infra monitor.
 */
interface OracleClient {
    val signer: OracleSigner

    /** The attestation for [dealId] (hex), or `null` while unobserved. */
    fun attestationFor(dealId: String): PaymentAttestation?

    /** Liveness probe for the infra monitor's ORACLE_LAG signal. */
    fun reachable(): Boolean

    /**
     * Miner-fee inputs usable in an oracle-signed release/contest tx. M3 seam:
     * the dev oracle signs the whole tx with its own key, so its fee inputs
     * must be oracle-key boxes; production co-signs in stages (operator wallet
     * + oracle), making this provider-specific.
     */
    fun feeInputs(): List<ChainBox> = emptyList()
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

    override val signer: OracleSigner get() = devOracle.signer()

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

    override fun reachable(): Boolean = up

    override fun feeInputs(): List<ChainBox> = listOf(devOracle.feeInputBox())
}
