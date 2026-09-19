package p2pgate.ergo

import org.ergoplatform.appkit.ErgoValue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NodeChainSource] against a stub transport — request shapes (incl. the raw
 * address POST body for unspent/byAddress), IndexedErgoBox/IndexedErgoTransaction
 * parsing (registers decode from node SValue base16), 404-as-absence without
 * failover, multi-URL failover on transport/non-404 failures, and exhaustion.
 * No network.
 */
class NodeChainSourceSpec {

    /** Records requested [NodeRequest]s and serves canned bodies (or throws). */
    private class StubTransport(
        private val bodies: Map<String, String> = emptyMap(),
        private val faults: Map<String, Throwable> = emptyMap(),
    ) : (NodeRequest) -> String {
        val requested = mutableListOf<NodeRequest>()

        override fun invoke(req: NodeRequest): String {
            requested += req
            faults[req.url]?.let { throw it }
            return bodies[req.url] ?: throw NodeChainSource.HttpStatusException(404, req.url)
        }
    }

    private val tokenIdHex = "ab".repeat(32)
    private val oracleNftHex = Base16.encode(ErgoContracts.DUMMY_ORACLE_NFT_ID)
    private val r4Hex = ErgoValue.of(ByteArray(32) { 1 }).toHex()
    private val r8Hex = ErgoValue.of(1_000_000L).toHex()

    private fun boxJson(
        boxId: String = "aa".repeat(32),
        spentTxId: String? = null,
        withRegisters: Boolean = true,
    ): String {
        val regs = if (withRegisters) {
            """ "additionalRegisters": { "R4": "$r4Hex", "R8": "$r8Hex" }, """
        } else {
            """ "additionalRegisters": {}, """
        }
        return """
        {
            "boxId": "$boxId",
            "transactionId": "${"cc".repeat(32)}",
            "index": 0,
            "value": 1500000,
            "ergoTree": "0008cd0263040506",
            "creationHeight": 1000,
            "assets": [ { "tokenId": "$tokenIdHex", "amount": 500000000 } ],
            $regs
            "address": "9fRAWhdxEsTcdb8PhGNrZdwqaUp1L1HU4LUC9pABTcx4RxC8942",
            "spentTransactionId": ${spentTxId?.let { "\"$it\"" } ?: "null"},
            "spendingHeight": ${if (spentTxId != null) 2000 else "null"},
            "inclusionHeight": 1100,
            "globalIndex": 98765
        }
        """.trimIndent()
    }

    @Test
    fun `getBox parses indexed box values tokens registers and spend state`() {
        val url = "$NODE/blockchain/box/byId/${"aa".repeat(32)}"
        val stub = StubTransport(mapOf(url to boxJson(spentTxId = "ef".repeat(32))))
        val box = NodeChainSource(NODE, stub).getBox("aa".repeat(32))!!
        assertEquals(listOf(NodeRequest(url)), stub.requested) // GET: no body
        assertEquals("aa".repeat(32), box.boxId)
        assertEquals("${"cc".repeat(32)}", box.transactionId)
        assertEquals(0, box.index)
        assertEquals(1_500_000L, box.value)
        assertEquals(1000, box.creationHeight)
        assertEquals("0008cd0263040506", box.ergoTreeHex)
        assertEquals("9fRAWhdxEsTcdb8PhGNrZdwqaUp1L1HU4LUC9pABTcx4RxC8942", box.address)
        assertEquals(1, box.tokens.size)
        assertEquals(tokenIdHex, box.tokens[0].tokenId)
        assertEquals(500_000_000L, box.tokens[0].amount)
        assertEquals("ef".repeat(32), box.spentTransactionId)
        // SValue base16 register decoding: Coll[Byte] at R4, Long at R8.
        assertTrue(box.registerBytes(4)!!.contentEquals(ByteArray(32) { 1 }))
        assertEquals(1_000_000L, box.registerLong(8))
        assertNull(box.registerBytes(8)) // mistyped accessor
        assertNull(box.registerLong(4))
    }

    @Test
    fun `getBox returns null on 404 without failing over`() {
        val stub = StubTransport()
        val src = NodeChainSource(listOf(NODE, NODE2), stub)
        assertNull(src.getBox("ff".repeat(32)))
        assertEquals(1, stub.requested.size) // absence: only the first URL was asked
    }

    @Test
    fun `getBox fails over to the second node on connection error and http 500`() {
        val url1 = "$NODE/blockchain/box/byId/${"aa".repeat(32)}"
        val url2 = "$NODE2/blockchain/box/byId/${"aa".repeat(32)}"
        // Connection error on the first node.
        val connStub = StubTransport(
            bodies = mapOf(url2 to boxJson()),
            faults = mapOf(url1 to IOException("connection refused")),
        )
        NodeChainSource(listOf(NODE, NODE2), connStub).getBox("aa".repeat(32))
        assertEquals(listOf(NodeRequest(url1), NodeRequest(url2)), connStub.requested)
        // HTTP 500 on the first node.
        val httpStub = StubTransport(
            bodies = mapOf(url2 to boxJson()),
            faults = mapOf(url1 to NodeChainSource.HttpStatusException(500, url1)),
        )
        val box = NodeChainSource(listOf(NODE, NODE2), httpStub).getBox("aa".repeat(32))!!
        assertEquals("aa".repeat(32), box.boxId)
        assertEquals(listOf(NodeRequest(url1), NodeRequest(url2)), httpStub.requested)
    }

    @Test
    fun `getBox throws the last failure when all nodes are exhausted`() {
        val url1 = "$NODE/blockchain/box/byId/${"aa".repeat(32)}"
        val url2 = "$NODE2/blockchain/box/byId/${"aa".repeat(32)}"
        val fault = IOException("node2 down")
        val stub = StubTransport(faults = mapOf(url1 to IOException("node1 down"), url2 to fault))
        val src = NodeChainSource(listOf(NODE, NODE2), stub)
        assertEquals(fault, assertFailsWith<IOException> { src.getBox("aa".repeat(32)) })
        assertEquals(2, stub.requested.size)
    }

    @Test
    fun `getSpendingTransaction follows the spend and parses height outputs and input tokens`() {
        val spendTxId = "ef".repeat(32)
        val txJson = """
        {
            "id": "$spendTxId",
            "inputs": [
                { "boxId": "${"11".repeat(32)}", "assets": [ { "tokenId": "$oracleNftHex", "amount": 1 } ] }
            ],
            "dataInputs": [],
            "outputs": [ ${boxJson(boxId = "bb".repeat(32))} ],
            "inclusionHeight": 12345,
            "numConfirmations": 10,
            "blockId": "${"dd".repeat(32)}",
            "timestamp": 1725000000000,
            "index": 0,
            "globalIndex": 42,
            "size": 300
        }
        """.trimIndent()
        val stub = StubTransport(
            mapOf(
                "$NODE/blockchain/box/byId/${"aa".repeat(32)}" to boxJson(spentTxId = spendTxId),
                "$NODE/blockchain/transaction/byId/$spendTxId" to txJson,
            ),
        )
        val src = NodeChainSource(NODE, stub)
        val spend = src.getSpendingTransaction("aa".repeat(32))!!
        assertEquals(
            listOf(
                NodeRequest("$NODE/blockchain/box/byId/${"aa".repeat(32)}"),
                NodeRequest("$NODE/blockchain/transaction/byId/$spendTxId"),
            ),
            stub.requested,
        )
        assertEquals(spendTxId, spend.txId)
        assertEquals(12345, spend.height)
        assertEquals(1, spend.outputs.size)
        assertEquals("bb".repeat(32), spend.outputs[0].boxId)
        assertEquals(listOf(oracleNftHex), spend.inputTokenIds)
    }

    @Test
    fun `getSpendingTransaction returns null when unspent or unknown`() {
        val stub = StubTransport(mapOf("$NODE/blockchain/box/byId/${"aa".repeat(32)}" to boxJson()))
        val src = NodeChainSource(NODE, stub)
        assertNull(src.getSpendingTransaction("aa".repeat(32))) // unspent
        assertNull(src.getSpendingTransaction("ff".repeat(32))) // unknown (404)
    }

    @Test
    fun `getCurrentHeight reads fullHeight from indexedHeight`() {
        val stub = StubTransport(
            mapOf("$NODE/blockchain/indexedHeight" to """{ "indexedHeight": 987650, "fullHeight": 987654 }"""),
        )
        assertEquals(987654, NodeChainSource(NODE, stub).getCurrentHeight())
    }

    @Test
    fun `getUnspentBoxes posts the raw address and parses the box array`() {
        val url = "$NODE/blockchain/box/unspent/byAddress?offset=0&limit=${NodeChainSource.UNSPENT_LIMIT}"
        val body = """[ ${boxJson(boxId = "aa".repeat(32))}, ${boxJson(boxId = "bb".repeat(32))} ]"""
        val stub = StubTransport(mapOf(url to body))
        val boxes = NodeChainSource(NODE, stub).getUnspentBoxes("9fRAWhdxEsTcdb8PhGNrZdwqaUp1L1HU4LUC9pABTcx4RxC8942")
        // POST with the raw address as the body (the node route binds entity(as[String])).
        assertEquals(listOf(NodeRequest(url, "9fRAWhdxEsTcdb8PhGNrZdwqaUp1L1HU4LUC9pABTcx4RxC8942")), stub.requested)
        assertEquals(listOf("aa".repeat(32), "bb".repeat(32)), boxes.map { it.boxId })
        assertTrue(boxes[0].registerBytes(4)!!.contentEquals(ByteArray(32) { 1 }))
        assertEquals(1_000_000L, boxes[0].registerLong(8))
        assertNull(boxes[0].spentTransactionId)
    }

    @Test
    fun `getUnspentBoxes tolerates the items-wrapped page shape`() {
        val url = "$NODE/blockchain/box/unspent/byAddress?offset=0&limit=${NodeChainSource.UNSPENT_LIMIT}"
        val stub = StubTransport(mapOf(url to """{ "items": [ ${boxJson(boxId = "aa".repeat(32))} ], "total": 1 }"""))
        val boxes = NodeChainSource(NODE, stub).getUnspentBoxes("addr1")
        assertEquals(listOf("aa".repeat(32)), boxes.map { it.boxId })
    }

    @Test
    fun `comma-separated base urls are split for failover`() {
        val url1 = "$NODE/blockchain/box/byId/${"aa".repeat(32)}"
        val url2 = "$NODE2/blockchain/box/byId/${"aa".repeat(32)}"
        val stub = StubTransport(
            bodies = mapOf(url2 to boxJson()),
            faults = mapOf(url1 to IOException("down")),
        )
        val src = NodeChainSource("$NODE, $NODE2", stub) // env-var style config
        assertEquals("aa".repeat(32), src.getBox("aa".repeat(32))!!.boxId)
        assertEquals(2, stub.requested.size)
    }

    @Test
    fun `unsupported register type is a parse error`() {
        // An Int constant at R4 is not a vault register type — must fail loudly, not silently skip.
        val intReg = ErgoValue.of(42).toHex()
        val json = boxJson().replace("\"R4\": \"$r4Hex\"", "\"R4\": \"$intReg\"")
        val stub = StubTransport(mapOf("$NODE/blockchain/box/byId/${"aa".repeat(32)}" to json))
        assertFailsWith<IllegalArgumentException> { NodeChainSource(NODE, stub).getBox("aa".repeat(32)) }
    }

    @Test
    fun `default base url is the local node`() {
        val stub = StubTransport(mapOf("$DEFAULT/blockchain/box/byId/${"aa".repeat(32)}" to boxJson()))
        NodeChainSource(listOf(DEFAULT), stub).getBox("aa".repeat(32))
        assertTrue(stub.requested.single().url.startsWith(DEFAULT))
    }

    companion object {
        private const val NODE = "http://node1:9053"
        private const val NODE2 = "http://node2:9053"
        private const val DEFAULT = NodeChainSource.DEFAULT_BASE_URLS
    }
}
