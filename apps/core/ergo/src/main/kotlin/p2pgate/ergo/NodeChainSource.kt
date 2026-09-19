package p2pgate.ergo

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * An HTTP request against an Ergo node's extra-indexer API: [url] plus an
 * optional raw JSON [body] (present → POST, absent → GET). Passed as a single
 * value so the injectable transport stays `(NodeRequest) -> body` — tests feed
 * canned bodies without touching the network.
 */
data class NodeRequest(val url: String, val body: String? = null)

/**
 * [ChainSource] against a full Ergo node's `/blockchain` extra-indexer API —
 * the drop-in alternative to [ExplorerChainSource] (`specs/android-app.md` §4.1)
 * for operators running their own node (privacy, no rate limits).
 *
 * The transport is injectable — `(NodeRequest) -> JSON body` — mirroring
 * [ExplorerChainSource]'s seam; the default transport is a plain JDK
 * `java.net.http.HttpClient` (no third-party HTTP framework).
 *
 * Wire formats (from the node's `api/openapi.yaml`):
 *  - `GET /blockchain/box/byId/{boxId}` — `IndexedErgoBox` = all
 *    `ErgoTransactionOutput` fields (`boxId`, `value`, `ergoTree`, `assets`,
 *    `additionalRegisters`, `creationHeight`, `transactionId`, `index`) plus
 *    `address`, `spentTransactionId`, `spendingHeight`, `inclusionHeight`,
 *    `globalIndex`. `additionalRegisters` values are `SValue`s — plain base16
 *    sigma-serialized constants, the same encoding the explorer serves, so
 *    register decoding reuses [ErgoValues.decodeRegister].
 *  - `GET /blockchain/transaction/byId/{txId}` — `IndexedErgoTransaction`
 *    (`id`, `inputs`/`outputs` as `IndexedErgoBox` so inputs carry `assets`,
 *    `inclusionHeight`).
 *  - `GET /blockchain/indexedHeight` — `{indexedHeight, fullHeight}`; the
 *    full height is the node's best chain, which is what "current height"
 *    means for the deal protocol.
 *  - `POST /blockchain/box/unspent/byAddress?offset=..&limit=..` — the request
 *    body is the raw address string (the route binds `entity(as[String])`, not
 *    a JSON-encoded string despite the `type: string` requestBody schema);
 *    the response is a bare JSON array of `IndexedErgoBox` (the node's
 *    `ApiResponse(Seq)` shape — the `{items, total}` wrapper used by the
 *    `byTokenId` siblings is tolerated defensively).
 *
 * Failover: multiple base URLs are tried in order on transport errors and
 * non-404 HTTP failures; a 404 means absence and never fails over. Only
 * Coll[Byte] and Long register constants are decoded (the vault's register
 * types); other constant types in responses are a parse error by design.
 */
class NodeChainSource(
    private val baseUrls: List<String>,
    private val transport: (request: NodeRequest) -> String = defaultTransport(),
) : ChainSource {

    /** Comma-separated base URLs, e.g. from `P2P_NODE_URL`. */
    constructor(
        baseUrls: String,
        transport: (request: NodeRequest) -> String = defaultTransport(),
    ) : this(baseUrls.split(',').map { it.trim() }.filter { it.isNotEmpty() }, transport)

    /** Non-2xx responses: [status] is the HTTP status, [url] the requested URL. */
    class HttpStatusException(val status: Int, val url: String) :
        RuntimeException("node request failed: HTTP $status for $url")

    companion object {
        /** Default local node (mainnet default port); testnet nodes use 9052. */
        const val DEFAULT_BASE_URLS = "http://127.0.0.1:9053"

        /** Unspent-box page size for fee funding (node default is 5, max 16384). */
        const val UNSPENT_LIMIT = 500

        /** Default JDK transport; may be replaced (proxying, caching, test stubs). */
        fun defaultTransport(requestTimeoutSeconds: Long = 30): (NodeRequest) -> String {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            return { req ->
                val builder = HttpRequest.newBuilder()
                    .uri(URI.create(req.url))
                    .timeout(java.time.Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Accept", "application/json")
                if (req.body != null) {
                    builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(req.body))
                } else {
                    builder.GET()
                }
                val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() / 100 != 2) throw HttpStatusException(resp.statusCode(), req.url)
                resp.body()
            }
        }
    }

    override fun getBox(boxId: String): ChainBox? = try {
        withFailover { base ->
            parseBox(Json.parse(transport(NodeRequest("$base/blockchain/box/byId/$boxId"))))
        }
    } catch (e: HttpStatusException) {
        if (e.status == 404) null else throw e
    }

    override fun getSpendingTransaction(boxId: String): ChainSpend? {
        val box = getBox(boxId) ?: return null
        val spendTxId = box.spentTransactionId ?: return null
        val tx = withFailover { base ->
            Json.parse(transport(NodeRequest("$base/blockchain/transaction/byId/$spendTxId")))
        }
        val height = tx.int("inclusionHeight")
            ?: throw IllegalArgumentException("node transaction $spendTxId has no inclusionHeight")
        val outputs = tx.arr("outputs")?.items?.map { parseBox(it) }
            ?: throw IllegalArgumentException("node transaction $spendTxId has no outputs")
        // Best-effort: inputs' token ids (IndexedErgoBox inputs carry assets;
        // see ChainSpend.inputTokenIds for why this signal is legacy-only).
        val inputTokenIds = tx.arr("inputs")?.items?.flatMap { input ->
            input.arr("assets")?.items?.mapNotNull { it.str("tokenId")?.lowercase() } ?: emptyList()
        } ?: emptyList()
        return ChainSpend(spendTxId, height, outputs, inputTokenIds)
    }

    override fun getCurrentHeight(): Int {
        val body = withFailover { base ->
            Json.parse(transport(NodeRequest("$base/blockchain/indexedHeight")))
        }
        return body.int("fullHeight") ?: body.int("indexedHeight")
            ?: throw IllegalArgumentException("node /blockchain/indexedHeight has no fullHeight")
    }

    override fun getUnspentBoxes(address: String): List<ChainBox> {
        val body = withFailover { base ->
            Json.parse(
                transport(
                    NodeRequest(
                        "$base/blockchain/box/unspent/byAddress?offset=0&limit=$UNSPENT_LIMIT",
                        body = address,
                    ),
                ),
            )
        }
        // Openapi + node route: bare array of IndexedErgoBox; the {items,total}
        // wrapper shape is tolerated for proxy implementations.
        val items = (body as? Json.Arr)?.items
            ?: body.arr("items")?.items
            ?: throw IllegalArgumentException("node unspent/byAddress response is not a box array")
        return items.map { parseBox(it) }
    }

    /**
     * Runs [call] against each base URL in order: HTTP failures other than 404
     * and connection-level [IOException]s fail over to the next URL, a 404
     * means absence and is rethrown immediately, and a successful parse on any
     * URL wins. When every URL fails, the last failure is rethrown.
     */
    private fun <T> withFailover(call: (base: String) -> T): T {
        var lastFailure: Exception? = null
        for (base in baseUrls) {
            try {
                return call(base)
            } catch (e: HttpStatusException) {
                if (e.status == 404) throw e
                lastFailure = e
            } catch (e: IOException) {
                lastFailure = e
            }
        }
        throw lastFailure ?: IllegalStateException("no Ergo node base URLs configured")
    }

    /** Parses one `IndexedErgoBox`-shaped JSON object (box or tx output). */
    private fun parseBox(j: Json): ChainBox {
        val registers = MutableList<ChainRegister?>(6) { null }
        j.obj("additionalRegisters")?.entries?.forEach { (name, value) ->
            val r = name.removePrefix("R").toIntOrNull()
                ?: throw IllegalArgumentException("unexpected register name '$name'")
            require(r in 4..9) { "unexpected register index R$r" }
            val hex = (value as? Json.Str)?.value
                ?: throw IllegalArgumentException("register R$r is not a base16 SValue string")
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
