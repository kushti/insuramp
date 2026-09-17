package p2pgate.ergo

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Default [ChainSource] against the public Ergo explorer REST API
 * (`specs/android-app.md` §4.1), mainnet `api.ergoplatform.com` /
 * testnet `api-testnet.ergoplatform.com`.
 *
 * The transport is injectable — `(path) -> JSON body` — so tests feed canned
 * responses without touching the network and a Tor/proxy transport can be
 * slotted in later (§5). The default transport is a plain JDK
 * `java.net.http.HttpClient` (no third-party HTTP framework).
 *
 * Endpoints used (v1):
 *  - `GET /api/v1/boxes/{boxId}` — box state incl. `spentTransactionId`
 *  - `GET /api/v1/transactions/{txId}` — outputs + `blockHeight` of the spend
 *  - `GET /api/v1/blocks?limit=1` — current best height
 *  - `GET /api/v1/boxes/unspent/byAddress/{address}` — unspent boxes (fee funding)
 *
 * Only Coll[Byte] and Long register constants are decoded (the vault's register
 * types); other constant types in responses are a parse error by design.
 */
class ExplorerChainSource(
    private val baseUrl: String = MAINNET_BASE_URL,
    private val transport: (path: String) -> String = defaultTransport(),
) : ChainSource {

    /** Non-2xx responses: [status] is the HTTP status, [path] the requested path. */
    class HttpStatusException(val status: Int, val path: String) :
        RuntimeException("explorer request failed: HTTP $status for $path")

    companion object {
        const val MAINNET_BASE_URL = "https://api.ergoplatform.com"
        const val TESTNET_BASE_URL = "https://api-testnet.ergoplatform.com"

        /** Default JDK transport; may be replaced (Tor, caching, test stubs). */
        fun defaultTransport(requestTimeoutSeconds: Long = 30): (String) -> String {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            return { path ->
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(path))
                    .timeout(java.time.Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Accept", "application/json")
                    .GET()
                    .build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() / 100 != 2) throw HttpStatusException(resp.statusCode(), path)
                resp.body()
            }
        }
    }

    override fun getBox(boxId: String): ChainBox? {
        val body = try {
            transport("$baseUrl/api/v1/boxes/$boxId")
        } catch (e: HttpStatusException) {
            if (e.status == 404) return null
            throw e
        }
        return parseBox(Json.parse(body))
    }

    override fun getSpendingTransaction(boxId: String): ChainSpend? {
        val box = getBox(boxId) ?: return null
        val spendTxId = box.spentTransactionId ?: return null
        val tx = Json.parse(transport("$baseUrl/api/v1/transactions/$spendTxId"))
        val height = tx.int("blockHeight") ?: tx.int("inclusionHeight")
            ?: throw IllegalArgumentException("explorer transaction $spendTxId has no blockHeight")
        val outputs = tx.arr("outputs")?.items?.map { parseBox(it) }
            ?: throw IllegalArgumentException("explorer transaction $spendTxId has no outputs")
        // Best-effort: inputs' token ids (the oracle NFT among them marks a release).
        val inputTokenIds = tx.arr("inputs")?.items?.flatMap { input ->
            input.arr("assets")?.items?.mapNotNull { it.str("tokenId")?.lowercase() } ?: emptyList()
        } ?: emptyList()
        return ChainSpend(spendTxId, height, outputs, inputTokenIds)
    }

    override fun getCurrentHeight(): Int {
        val blocks = Json.parse(transport("$baseUrl/api/v1/blocks?limit=1"))
        val first = blocks.arr("items")?.items?.firstOrNull()
            ?: throw IllegalArgumentException("explorer /blocks returned no items")
        return first.int("height") ?: throw IllegalArgumentException("explorer /blocks item has no height")
    }

    override fun getUnspentBoxes(address: String): List<ChainBox> {
        val body = Json.parse(transport("$baseUrl/api/v1/boxes/unspent/byAddress/$address"))
        return body.arr("items")?.items?.map { parseBox(it) } ?: emptyList()
    }

    /** Parses one explorer OutputInfo-shaped JSON object (box or tx output). */
    private fun parseBox(j: Json): ChainBox {
        val registers = MutableList<ChainRegister?>(6) { null }
        j.obj("additionalRegisters")?.entries?.forEach { (name, value) ->
            val r = name.removePrefix("R").toIntOrNull()
                ?: throw IllegalArgumentException("unexpected register name '$name'")
            require(r in 4..9) { "unexpected register index R$r" }
            val hex = (value as? Json.Str)?.value
                ?: throw IllegalArgumentException("register R$r is not a hex string")
            registers[r - 4] = ErgoValues.decodeRegister(hex)
        }
        return ChainBox(
            boxId = j.str("boxId") ?: throw IllegalArgumentException("box has no boxId"),
            transactionId = j.str("transactionId") ?: throw IllegalArgumentException("box has no transactionId"),
            index = j.int("index") ?: 0,
            value = j.long("value") ?: throw IllegalArgumentException("box has no value"),
            creationHeight = j.int("creationHeight") ?: throw IllegalArgumentException("box has no creationHeight"),
            ergoTreeHex = j.str("ergoTree")?.lowercase() ?: throw IllegalArgumentException("box has no ergoTree"),
            address = j.str("address") ?: throw IllegalArgumentException("box has no address"),
            tokens = j.arr("assets")?.items?.map { a ->
                ChainToken(
                    tokenId = a.str("tokenId")?.lowercase()
                        ?: throw IllegalArgumentException("asset has no tokenId"),
                    amount = a.long("amount") ?: throw IllegalArgumentException("asset has no amount"),
                )
            } ?: emptyList(),
            registers = registers,
            spentTransactionId = j.str("spentTransactionId"),
        )
    }
}
