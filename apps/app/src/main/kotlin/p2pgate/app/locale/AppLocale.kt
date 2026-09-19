package p2pgate.app.locale

/**
 * In-app locale support: the launch set {en, hi, sw, ar} with the language
 * picker names in their NATIVE form (never translated), first-run
 * system-locale detection, and the locale → default-currency tie-in. Pure
 * Kotlin, no Android types — the Android glue (AppCompatDelegate,
 * SharedPreferences) lives in MainActivity / QuoteScreen and calls these.
 */

/** One supported locale: its BCP-47 tag and its name in its own language. */
data class SupportedLocale(val tag: String, val nativeName: String)

/** The launch language set, picker order. */
val SUPPORTED_LOCALES: List<SupportedLocale> = listOf(
    SupportedLocale("en", "English"),
    SupportedLocale("hi", "हिन्दी"),
    SupportedLocale("sw", "Kiswahili"),
    SupportedLocale("ar", "العربية"),
)

/** Fallback when the system language is outside the supported set. */
const val DEFAULT_LOCALE_TAG = "en"

/** SharedPreferences key (`p2pgate_settings`) of the explicit currency pick. */
const val PREF_FIAT_CURRENCY = "fiat_currency"

/**
 * System language subtag → supported locale tag: hi*→hi, sw*→sw, ar*→ar,
 * everything else → en. Input is a bare language subtag (`Locale.language`,
 * already lowercase); region/script never change the outcome.
 */
fun resolveSupportedTag(language: String?): String = when (language) {
    "hi", "sw", "ar" -> language
    else -> DEFAULT_LOCALE_TAG
}

/**
 * Effective locale → default fiat currency: en→USD, hi→INR, sw→KSH, ar→USD
 * (EGP is not in the launch currency list, so Arabic falls back to USD).
 * Input is an already-resolved supported tag; unknown tags fall back to USD.
 */
fun defaultCurrencyFor(supportedLocaleTag: String): String = when (supportedLocaleTag) {
    "hi" -> "INR"
    "sw" -> "KSH"
    else -> "USD"
}

/**
 * The fiat currency the quote screen starts from: the user's explicit pick if
 * one was ever made (and then it sticks across locale switches), else the
 * locale-driven default.
 */
fun initialCurrency(explicit: String?, supportedLocaleTag: String): String =
    explicit?.takeIf { it.isNotBlank() } ?: defaultCurrencyFor(supportedLocaleTag)
