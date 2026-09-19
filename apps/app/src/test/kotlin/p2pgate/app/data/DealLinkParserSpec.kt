package p2pgate.app.data

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class DealLinkParserSpec {

    @Test
    fun `full https recovery link`() {
        val link = DealLinkParser.parse("https://api.p2pgate.example/deal/abcd1234#tok-9f8e")
        assertEquals("abcd1234", link.dealId)
        assertEquals("tok-9f8e", link.token)
    }

    @Test
    fun `p2pgate scheme link`() {
        val link = DealLinkParser.parse("p2pgate://deal/abcd1234#tok-9f8e")
        assertEquals("abcd1234", link.dealId)
        assertEquals("tok-9f8e", link.token)
    }

    @Test
    fun `bare id hash token`() {
        val link = DealLinkParser.parse("abcd1234#tok-9f8e")
        assertEquals("abcd1234", link.dealId)
        assertEquals("tok-9f8e", link.token)
    }

    @Test
    fun `missing token fails`() {
        assertThrows<IllegalArgumentException> { DealLinkParser.parse("https://x/deal/abcd1234") }
    }

    @Test
    fun `empty input fails`() {
        assertThrows<IllegalArgumentException> { DealLinkParser.parse("  ") }
    }
}
