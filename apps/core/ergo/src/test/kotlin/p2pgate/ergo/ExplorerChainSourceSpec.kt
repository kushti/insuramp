package p2pgate.ergo

import org.ergoplatform.appkit.ErgoValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ExplorerChainSource] against a stub transport — URL construction, box/tx
 * parsing (registers decode from explorer hex), unspent-by-address, current
 * height, and error paths. No network.
 */
class ExplorerChainSourceSpec {

    /** Records requested paths and serves canned bodies (or throws). */
    private class StubTransport(private val bodies: Map<String, String> = emptyMap()) : (String) -> String {
        val requested = mutableListOf<String>()
        var toThrow: Throwable? = null

        override fun invoke(path: String): String {
            requested += path
            toThrow?.let { throw it }
            return bodies[path] ?: throw ExplorerChainSource.HttpStatusException(404, path)
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
            "creationHeight": 1000,
            "ergoTree": "0008cd0263040506",
            "address": "9fRAWhdxEsTcdb8PhGNrZdwqaUp1L1HU4LUC9pABTcx4RxC8942",
            "assets": [ { "tokenId": "$tokenIdHex", "amount": 500000000 } ],
            $regs
            "spentTransactionId": ${spentTxId?.let { "\"$it\"" } ?: "null"}
        }
        """.trimIndent()
    }

    @Test
    fun `getBox parses values tokens registers and spend state`() {
        val stub = StubTransport(mapOf("$MAIN/api/v1/boxes/${"aa".repeat(32)}" to boxJson()))
        val src = ExplorerChainSource(MAIN, stub)
        val box = src.getBox("aa".repeat(32))!!
        assertEquals(listOf("$MAIN/api/v1/boxes/${"aa".repeat(32)}"), stub.requested)
        assertEquals("aa".repeat(32), box.boxId)
        assertEquals("${"cc".repeat(32)}", box.transactionId)
        assertEquals(0, box.index)
        assertEquals(1_500_000L, box.value)
        assertEquals(1000, box.creationHeight)
        assertEquals("0008cd0263040506", box.ergoTreeHex)
        assertEquals(1, box.tokens.size)
        assertEquals(tokenIdHex, box.tokens[0].tokenId)
        assertEquals(500_000_000L, box.tokens[0].amount)
        assertNull(box.spentTransactionId)
        // Register decoding from explorer hex: Coll[Byte] at R4, Long at R8.
        assertTrue(box.registerBytes(4)!!.contentEquals(ByteArray(32) { 1 }))
        assertEquals(1_000_000L, box.registerLong(8))
        assertNull(box.registerBytes(8)) // mistyped accessor
        assertNull(box.registerLong(4))
    }

    @Test
    fun `getBox returns null on 404`() {
        val stub = StubTransport()
        assertNull(ExplorerChainSource(MAIN, stub).getBox("ff".repeat(32)))
    }

    @Test
    fun `getBox propagates non-404 errors`() {
        val stub = StubTransport()
        stub.toThrow = ExplorerChainSource.HttpStatusException(500, "x")
        assertFailsWith<ExplorerChainSource.HttpStatusException> { ExplorerChainSource(MAIN, stub).getBox("aa".repeat(32)) }
    }

    @Test
    fun `getSpendingTransaction follows the spend and parses height outputs and input tokens`() {
        val spendTxId = "ef".repeat(32)
        val txJson = """
        {
            "id": "$spendTxId",
            "blockHeight": 12345,
            "inputs": [
                { "boxId": "${"11".repeat(32)}", "assets": [ { "tokenId": "$oracleNftHex", "amount": 1 } ] }
            ],
            "outputs": [ ${boxJson(boxId = "bb".repeat(32))} ]
        }
        """.trimIndent()
        val stub = StubTransport(
            mapOf(
                "$MAIN/api/v1/boxes/${"aa".repeat(32)}" to boxJson(spentTxId = spendTxId),
                "$MAIN/api/v1/transactions/$spendTxId" to txJson,
            ),
        )
        val src = ExplorerChainSource(MAIN, stub)
        val spend = src.getSpendingTransaction("aa".repeat(32))!!
        assertEquals(listOf("$MAIN/api/v1/boxes/${"aa".repeat(32)}", "$MAIN/api/v1/transactions/$spendTxId"), stub.requested)
        assertEquals(spendTxId, spend.txId)
        assertEquals(12345, spend.height)
        assertEquals(1, spend.outputs.size)
        assertEquals("bb".repeat(32), spend.outputs[0].boxId)
        assertEquals(listOf(oracleNftHex), spend.inputTokenIds)
    }

    @Test
    fun `getSpendingTransaction returns null when unspent or unknown`() {
        val stub = StubTransport(mapOf("$MAIN/api/v1/boxes/${"aa".repeat(32)}" to boxJson()))
        val src = ExplorerChainSource(MAIN, stub)
        assertNull(src.getSpendingTransaction("aa".repeat(32))) // unspent
        assertNull(src.getSpendingTransaction("ff".repeat(32))) // unknown (404)
    }

    @Test
    fun `getCurrentHeight reads the newest block`() {
        val stub = StubTransport(
            mapOf("$MAIN/api/v1/blocks?limit=1" to """{ "items": [ { "height": 987654 } ] }"""),
        )
        assertEquals(987654, ExplorerChainSource(MAIN, stub).getCurrentHeight())
    }

    @Test
    fun `getUnspentBoxes parses the items array`() {
        val body = """{ "items": [ ${boxJson(boxId = "aa".repeat(32))}, ${boxJson(boxId = "bb".repeat(32))} ] }"""
        val stub = StubTransport(mapOf("$MAIN/api/v1/boxes/unspent/byAddress/addr1" to body))
        val boxes = ExplorerChainSource(MAIN, stub).getUnspentBoxes("addr1")
        assertEquals(listOf("aa".repeat(32), "bb".repeat(32)), boxes.map { it.boxId })
    }

    @Test
    fun `default base url is mainnet and testnet stays selectable`() {
        // No-arg base URL: mainnet since 2026-09-17.
        val mainStub = StubTransport(mapOf("$MAIN/api/v1/boxes/${"aa".repeat(32)}" to boxJson()))
        ExplorerChainSource(transport = mainStub).getBox("aa".repeat(32))
        assertTrue(mainStub.requested.single().startsWith(MAIN))
        // Testnet remains reachable via the constructor.
        val testStub = StubTransport(mapOf("$TESTNET/api/v1/boxes/${"aa".repeat(32)}" to boxJson()))
        ExplorerChainSource(TESTNET, testStub).getBox("aa".repeat(32))
        assertTrue(testStub.requested.single().startsWith(TESTNET))
    }

    @Test
    fun `unsupported register type is a parse error`() {
        // An Int constant at R4 is not a vault register type — must fail loudly, not silently skip.
        val intReg = ErgoValue.of(42).toHex()
        val json = boxJson().replace("\"R4\": \"$r4Hex\"", "\"R4\": \"$intReg\"")
        val stub = StubTransport(mapOf("$MAIN/api/v1/boxes/${"aa".repeat(32)}" to json))
        assertFailsWith<IllegalArgumentException> { ExplorerChainSource(MAIN, stub).getBox("aa".repeat(32)) }
    }

    @Test
    fun `malformed json is a parse error`() {
        val stub = StubTransport(mapOf("$MAIN/api/v1/boxes/${"aa".repeat(32)}" to "{ not json"))
        assertFailsWith<IllegalArgumentException> { ExplorerChainSource(MAIN, stub).getBox("aa".repeat(32)) }
    }

    companion object {
        private const val MAIN = ExplorerChainSource.MAINNET_BASE_URL
        private const val TESTNET = ExplorerChainSource.TESTNET_BASE_URL
    }
}
