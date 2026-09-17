package p2pgate.e2e

/**
 * The faucet seam: `GET {faucet}/payment/address/{address}` (the testnet
 * faucet, e.g. testnet.ergofaucet.org — enabled only on testnet runs via
 * `E2E_FAUCET_URL`; mainnet has no faucet and funds manually). [Unavailable]
 * means the faucet is gone/rate-limiting hard — the harness then
 * prints manual-funding instructions and exits 2 instead of failing.
 */
fun interface Faucet {
    fun request(address: String): FaucetResult
}

sealed interface FaucetResult {
    /** The faucet accepted the request (a payment should arrive shortly). */
    data object Accepted : FaucetResult

    /** The faucet is gone or unusable — carry the observed reason. */
    class Unavailable(val reason: String) : FaucetResult
}

class HttpFaucet(private val baseUrl: String, private val transport: HttpTransport) : Faucet {
    override fun request(address: String): FaucetResult = try {
        transport.get("$baseUrl/payment/address/$address")
        FaucetResult.Accepted
    } catch (e: HttpTransport.HttpException) {
        FaucetResult.Unavailable("faucet returned HTTP ${e.status}")
    } catch (e: Exception) {
        FaucetResult.Unavailable("faucet unreachable: ${e.javaClass.simpleName}: ${e.message}")
    }
}
