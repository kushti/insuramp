package p2pgate.e2e

import p2pgate.ergo.ChainBox
import p2pgate.ergo.ChainRegister
import p2pgate.ergo.ChainSource
import p2pgate.ergo.ChainToken
import p2pgate.ergo.ExplorerChainSource

/**
 * The chain operations the e2e flow needs — the seam the offline unit tests
 * fake (canned chain state, no network).
 */
interface ChainGateway {
    fun getCurrentHeight(): Int
    fun getBox(boxId: String): ChainBox?
    fun getUnspentBoxes(address: String): List<ChainBox>

    /** The confirmed transaction's outputs as [ChainBox]es (explorer OutputInfo shape). */
    fun getTransactionOutputs(txId: String): List<ChainBox>

    /**
     * Broadcasts [signedTxJson] (appkit's `SignedTransaction.toJson(false)`
     * shape, which matches the explorer's transaction model) to
     * `POST /api/v1/mempool/transactions`; returns the accepted tx id.
     */
    fun submitTransaction(signedTxJson: String): String
}

/**
 * The live [ChainGateway]: all reads go through a core [ExplorerChainSource]
 * fed by the harness-owned [HttpTransport] (the core transport seam is
 * public), plus two raw operations the core seam does not cover — fetching a
 * transaction's outputs by id (needed to learn the box ids the run itself
 * created) and submitting a signed tx to the explorer mempool endpoint.
 */
class E2eExplorer(
    val baseUrl: String,
    private val transport: HttpTransport,
) : ChainGateway {
    /** The core chain-read seam, driven by the harness transport. */
    private val chain: ChainSource = ExplorerChainSource(baseUrl, transport::get)

    class SubmitException(val status: Int, message: String) : RuntimeException(message)

    override fun submitTransaction(signedTxJson: String): String {
        val body = try {
            transport.postJson("$baseUrl/api/v1/mempool/transactions", signedTxJson)
        } catch (e: HttpTransport.HttpException) {
            throw SubmitException(e.status, "explorer rejected the tx: HTTP ${e.status}: ${e.message}")
        }
        return J.parse(body).str("id")
            ?: throw SubmitException(-1, "explorer accepted the tx but the response has no id: ${body.take(300)}")
    }

    /** The confirmed transaction's outputs as [ChainBox]es (explorer OutputInfo shape). */
    override fun getTransactionOutputs(txId: String): List<ChainBox> {
        val tx = J.parse(transport.get("$baseUrl/api/v1/transactions/$txId"))
        return tx.arr("outputs")?.items?.map { parseBox(it) }
            ?: throw IllegalArgumentException("explorer transaction $txId has no outputs")
    }

    /** Box state — delegates to the core seam. */
    override fun getBox(boxId: String): ChainBox? = chain.getBox(boxId)

    override fun getCurrentHeight(): Int = chain.getCurrentHeight()

    override fun getUnspentBoxes(address: String): List<ChainBox> = chain.getUnspentBoxes(address)

    /** Parses one explorer OutputInfo-shaped JSON object — mirrors `ExplorerChainSource`'s parser. */
    private fun parseBox(j: J): ChainBox {
        val registers = MutableList<ChainRegister?>(6) { null }
        j.obj("additionalRegisters")?.entries?.forEach { (name, value) ->
            val r = name.removePrefix("R").toIntOrNull()
                ?: throw IllegalArgumentException("unexpected register name '$name'")
            require(r in 4..9) { "unexpected register index R$r" }
            val hex = (value as? J.Str)?.value
                ?: throw IllegalArgumentException("register R$r is not a hex string")
            registers[r - 4] = Wire.decodeRegister(hex)
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
