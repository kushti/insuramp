package p2pgate.app.locale

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The pure locale logic: system-language → supported-locale resolution, the
 * locale → default-currency tie-in, and explicit-pick precedence (the glue is
 * AppCompatDelegate + `p2pgate_settings`; these functions decide).
 */
class AppLocaleSpec {

    @Test
    fun `system language resolves into the supported set`() {
        assertEquals("hi", resolveSupportedTag("hi"))
        assertEquals("sw", resolveSupportedTag("sw"))
        assertEquals("ar", resolveSupportedTag("ar"))
        assertEquals("ru", resolveSupportedTag("ru"))
        assertEquals("en", resolveSupportedTag("en"))
        assertEquals("en", resolveSupportedTag("fr"))
        assertEquals("en", resolveSupportedTag("zh"))
        assertEquals("en", resolveSupportedTag(null))
        assertEquals("en", resolveSupportedTag(""))
    }

    @Test
    fun `supported locales are the five languages in native names`() {
        assertEquals(listOf("en", "hi", "sw", "ar", "ru"), SUPPORTED_LOCALES.map { it.tag })
        assertEquals(
            listOf("English", "हिन्दी", "Kiswahili", "العربية", "Русский"),
            SUPPORTED_LOCALES.map { it.nativeName },
        )
    }

    @Test
    fun `locale drives the default fiat currency`() {
        assertEquals("USD", defaultCurrencyFor("en"))
        assertEquals("INR", defaultCurrencyFor("hi"))
        assertEquals("KSH", defaultCurrencyFor("sw"))
        // EGP is not offered — Arabic falls back to USD.
        assertEquals("USD", defaultCurrencyFor("ar"))
        assertEquals("RUB", defaultCurrencyFor("ru"))
        assertEquals("USD", defaultCurrencyFor("xx"))
    }

    @Test
    fun `explicit currency pick wins over the locale default`() {
        assertEquals("INR", initialCurrency(explicit = null, supportedLocaleTag = "hi"))
        assertEquals("RUB", initialCurrency(explicit = null, supportedLocaleTag = "ru"))
        assertEquals("USD", initialCurrency(explicit = null, supportedLocaleTag = "ar"))
        assertEquals("INR", initialCurrency(explicit = "INR", supportedLocaleTag = "en"))
        // Switching language after a manual pick must not move the currency.
        assertEquals("KSH", initialCurrency(explicit = "KSH", supportedLocaleTag = "hi"))
        // A blank stored pick is treated as no pick.
        assertEquals("KSH", initialCurrency(explicit = "  ", supportedLocaleTag = "sw"))
    }
}
