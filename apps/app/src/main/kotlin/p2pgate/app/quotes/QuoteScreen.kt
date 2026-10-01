package p2pgate.app.quotes

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch
import p2pgate.app.AppContainer
import p2pgate.app.R
import p2pgate.app.data.DealRepository
import p2pgate.app.locale.DEFAULT_LOCALE_TAG
import p2pgate.app.locale.PREF_FIAT_CURRENCY
import p2pgate.app.locale.SUPPORTED_LOCALES
import p2pgate.app.locale.initialCurrency
import p2pgate.app.net.CreateDealRequest
import p2pgate.app.net.QuoteDto
import p2pgate.app.verify.Hex
import p2pgate.dealprotocol.FiatAmounts

/**
 * Quote discovery (`onramp-ux.md` §2.1): the buyer enters the USDT amount they
 * want, their USDT receive address and the cash currency, sees the operator's
 * pre-published quote with the rate and the collateral line (actual vault
 * capacity — never a marketing string), and chooses it to create the deal. The
 * cash leg is *derived* from the quote's rate (`QuoteOffer`), never typed — a
 * buyer who typed both legs could disagree with the seller's rate, and the
 * backend rejects that. A refresh action + the quote WS keep the row live (the
 * pull-to-refresh gesture is deliberately a plain button — one job per
 * screen). The quote can be browsed as a list (default) or as seller-location
 * pins on a map; a pin tap is the same Choose action.
 */
/** The cash currencies offered (owner decision, 2026-09-19; RUB added 2026-09-19). Codes are never translated. */
private data class FiatCurrency(val code: String, @StringRes val labelRes: Int)

private val FIAT_CURRENCIES = listOf(
    FiatCurrency("INR", R.string.fiat_inr),
    FiatCurrency("USD", R.string.fiat_usd),
    FiatCurrency("KSH", R.string.fiat_ksh),
    FiatCurrency("RUB", R.string.fiat_rub),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteScreen(
    container: AppContainer,
    onDealCreated: (String) -> Unit,
) {
    val viewModel = remember { QuotesViewModel(container.backendClient) }
    val ui by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var usdtAmount by remember { mutableStateOf("") }
    // Currency default follows the effective locale until the user picks one
    // explicitly; an explicit pick is persisted and wins across locale
    // switches and process death. A stale stored code falls back to the
    // locale default.
    val settings = container.settings
    var fiatCurrency by remember {
        mutableStateOf(
            initialCurrency(
                explicit = settings.getString(PREF_FIAT_CURRENCY, null),
                supportedLocaleTag = AppCompatDelegate.getApplicationLocales().get(0)?.language
                    ?: DEFAULT_LOCALE_TAG,
            ).takeIf { code -> FIAT_CURRENCIES.any { it.code == code } }
                ?: FIAT_CURRENCIES.first().code,
        )
    }
    var receiveAddress by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    // One selection handler for both views: the list's Choose button and a
    // map-pin tap land here. The composed offer is validated against the
    // chosen quote (its min/max bounds and its rate) before the backend is
    // called; createError holds a fully resolved localized message.
    val context = LocalContext.current
    val chooseQuote: (QuoteDto) -> Unit = choose@{ q ->
        if (creating) return@choose
        // Cards/pins are currency-filtered, so this cannot mismatch; the
        // backend enforces the same equality — guard anyway.
        if (q.fiatCurrency != fiatCurrency) return@choose
        scope.launch {
            creating = true
            createError = null
            try {
                val problem = QuoteOffer.problem(usdtAmount, receiveAddress, q)
                if (problem != null) {
                    createError = problemText(context, problem, q)
                    return@launch
                }
                // The cash leg is the seller's rate applied to the typed USDT
                // amount — the same derivation the backend re-runs on arrival.
                val usdtBase = QuoteOffer.parseUsdtBase(usdtAmount)!!
                val cash = QuoteOffer.cashFor(usdtBase, q)!!
                val deal = createDeal(
                    container.dealRepository,
                    container,
                    q.id,
                    usdtBase,
                    cash,
                    fiatCurrency,
                    receiveAddress.trim(),
                    q.expiresAtEpochMs,
                )
                onDealCreated(deal.dealId)
            } catch (e: Exception) {
                createError = context.getString(R.string.quote_create_error, e.message ?: "")
            } finally {
                creating = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.quote_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            LanguagePicker(onLanguageChange = {
                // Language switch re-derives the currency from the new locale
                // (clears any explicit currency pick — it is the older choice).
                settings.edit().remove(PREF_FIAT_CURRENCY).apply()
            })
        }

        // The supported cash currencies are fixed — no free text (a typo'd
        // currency code would poison the deal terms and the handoff record).
        var currencyMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = currencyMenu,
            onExpandedChange = { currencyMenu = it },
        ) {
            val selected = FIAT_CURRENCIES.first { it.code == fiatCurrency }
            OutlinedTextField(
                value = stringResource(R.string.fiat_currency_option, selected.code, stringResource(selected.labelRes)),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.fiat_currency_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyMenu) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = currencyMenu,
                onDismissRequest = { currencyMenu = false },
            ) {
                FIAT_CURRENCIES.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.fiat_currency_option, c.code, stringResource(c.labelRes))) },
                        onClick = {
                            fiatCurrency = c.code
                            currencyMenu = false
                            // The explicit pick sticks across locale switches.
                            settings.edit().putString(PREF_FIAT_CURRENCY, c.code).apply()
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = usdtAmount,
            onValueChange = { usdtAmount = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.quote_usdt_amount_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            // Whole USDT units only — open the numeric keypad, not the text layout.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = receiveAddress,
            onValueChange = { receiveAddress = it.trim() },
            label = { Text(stringResource(R.string.quote_address_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = QuoteOffer.addressProblem(receiveAddress) == QuoteOffer.Problem(
                QuoteOffer.Field.ADDRESS,
                QuoteOffer.Kind.NOT_TRON,
            ),
            supportingText = {
                if (QuoteOffer.addressProblem(receiveAddress)?.kind == QuoteOffer.Kind.NOT_TRON) {
                    Text(stringResource(R.string.quote_address_invalid))
                }
            },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.quote_best),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            SingleChoiceSegmentedButtonRow {
                QuoteViewMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = ui.viewMode == mode,
                        onClick = { viewModel.setViewMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = QuoteViewMode.entries.size,
                        ),
                    ) {
                        Text(
                            stringResource(
                                if (mode == QuoteViewMode.LIST) R.string.quote_view_list else R.string.quote_view_map,
                            ),
                        )
                    }
                }
            }
            TextButton(onClick = { viewModel.refresh() }, enabled = !ui.refreshing) {
                Text(stringResource(if (ui.refreshing) R.string.quote_refreshing else R.string.quote_refresh))
            }
        }
        if (ui.stale) {
            Text(
                stringResource(R.string.quote_stale),
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Currency scoping: both views show only quotes serving the selected
        // fiat currency; recomputed on every dropdown change. The empty text
        // distinguishes "nothing published at all" from "nothing in this
        // currency".
        val currencyQuotes = quotesForCurrency(ui.quotes, fiatCurrency)
        val emptyQuotesText = if (ui.quotes.isEmpty()) {
            stringResource(R.string.quote_none)
        } else {
            stringResource(R.string.quote_none_for_currency, fiatCurrency)
        }
        // The best-ETA quote in this currency is the one the derived cash leg
        // below is priced against — the buyer sees what they will hand over
        // before choosing a seller.
        val previewQuote = remember(currencyQuotes) { bestFirst(currencyQuotes).firstOrNull() }
        val derivedCash = previewQuote?.let { QuoteOffer.cashLabel(QuoteOffer.parseUsdtBase(usdtAmount), it) }
        if (previewQuote != null && derivedCash != null) {
            Text(
                stringResource(
                    R.string.quote_you_hand,
                    derivedCash,
                    fiatCurrency,
                    QuoteOffer.rateLabel(previewQuote),
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        when (ui.viewMode) {
            QuoteViewMode.LIST -> Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (currencyQuotes.isEmpty()) {
                    Text(emptyQuotesText)
                } else {
                    // One card per active quote, best ETA first; each card's
                    // Choose runs the same per-quote-id flow as a map-pin tap.
                    bestFirst(currencyQuotes).forEach { q ->
                        QuoteCard(
                            quote = q,
                            creating = creating,
                            createError = createError,
                            onChoose = { chooseQuote(q) },
                        )
                    }
                }
            }
            QuoteViewMode.MAP -> {
                val model = quoteMapModel(currencyQuotes)
                if (currencyQuotes.isEmpty()) {
                    Text(emptyQuotesText)
                } else {
                    QuoteMapView(
                        markers = model.markers,
                        onMarkerChoose = { quoteId ->
                            currencyQuotes.firstOrNull { it.id == quoteId }?.let(chooseQuote)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    if (model.hasUnlocated) {
                        Text(
                            stringResource(R.string.quote_unlocated),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (creating) {
                        Text(stringResource(R.string.quote_creating), style = MaterialTheme.typography.bodySmall)
                    }
                    createError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/**
 * The language entry point: a translate glyph opening the four supported
 * languages by their native names; the effective language is ticked. The
 * choice goes through AppCompatDelegate.setApplicationLocales, which persists
 * it and recreates the activity with the new resources. A language switch
 * also RESETS the fiat currency to the new locale's default (en→USD,
 * ru→RUB…): the language pick is the newer explicit action, so it clears any
 * earlier explicit currency choice (owner decision, 2026-09-21).
 */
@Composable
private fun LanguagePicker(onLanguageChange: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: DEFAULT_LOCALE_TAG
    Box {
        TextButton(onClick = { open = true }) {
            Text("🌐 " + (SUPPORTED_LOCALES.firstOrNull { it.tag == current }?.nativeName ?: current))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SUPPORTED_LOCALES.forEach { locale ->
                DropdownMenuItem(
                    text = { Text((if (locale.tag == current) "✓ " else "") + locale.nativeName) },
                    onClick = {
                        open = false
                        onLanguageChange()
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(locale.tag),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun QuoteCard(
    quote: QuoteDto,
    creating: Boolean,
    createError: String?,
    onChoose: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.quote_eta, quote.etaMinutes), fontWeight = FontWeight.Bold)
            // The rate the cash leg is derived from — the quote's own number,
            // not a marketing claim.
            Text(
                stringResource(R.string.quote_rate, QuoteOffer.rateLabel(quote), quote.fiatCurrency),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            // The per-deal amount range the seller honors, in whole USDT (the
            // DTO carries base units).
            Text(
                stringResource(
                    R.string.quote_amount_range,
                    FiatAmounts.formatUsdt(quote.minAmount),
                    FiatAmounts.formatUsdt(quote.maxAmount),
                ),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            // The collateral line: actual vault collateral behind these deals.
            Text(
                stringResource(R.string.quote_collateral_line),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onChoose,
                enabled = !creating,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (creating) R.string.quote_creating else R.string.offer_action)) }
            createError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Resolves a [QuoteOffer.Problem] into a localized message. Out-of-range is the
 * only one that needs the chosen quote (its bounds are per seller); the rest
 * are about the inputs alone.
 */
private fun problemText(
    context: android.content.Context,
    problem: QuoteOffer.Problem,
    quote: QuoteDto,
): String = when (problem.field to problem.kind) {
    QuoteOffer.Field.ADDRESS to QuoteOffer.Kind.EMPTY -> context.getString(R.string.offer_fields_required)
    QuoteOffer.Field.ADDRESS to QuoteOffer.Kind.NOT_TRON -> context.getString(R.string.quote_address_invalid)
    QuoteOffer.Field.AMOUNT to QuoteOffer.Kind.EMPTY -> context.getString(R.string.offer_fields_required)
    QuoteOffer.Field.AMOUNT to QuoteOffer.Kind.ZERO,
    QuoteOffer.Field.AMOUNT to QuoteOffer.Kind.UNPARSEABLE -> context.getString(R.string.quote_amount_invalid)
    QuoteOffer.Field.AMOUNT to QuoteOffer.Kind.OUT_OF_RANGE -> context.getString(
        R.string.quote_amount_out_of_range,
        FiatAmounts.formatUsdt(quote.minAmount),
        FiatAmounts.formatUsdt(quote.maxAmount),
    )
    QuoteOffer.Field.AMOUNT to QuoteOffer.Kind.RATE_UNUSABLE -> context.getString(R.string.quote_rate_unusable)
    else -> context.getString(R.string.quote_create_error, "")
}

/**
 * Deal creation (`specs/android-app.md` §3.1): an OFFER to the seller — the
 * deal sits in QUOTED until the seller funds the vault or the offer expires
 * (the quote's `expiresAtEpochMs` is persisted for the pending-offer
 * countdown). Mint the deal key first (it is keyed by a pending id and moved
 * once the server assigns the deal id), then POST /v1/deals with the buyer
 * pubkey, the typed USDT leg, the rate-derived cash leg and the receive
 * address.
 */
private suspend fun createDeal(
    repository: DealRepository,
    container: AppContainer,
    quoteId: String,
    amount: Long,
    fiatAmount: Long,
    fiatCurrency: String,
    receiveAddress: String,
    offerExpiresAtEpochMs: Long,
): p2pgate.app.data.DealSnapshot {
    val pendingKeyId = "pending-${System.nanoTime()}"
    val buyerPubKey = Hex.encode(container.dealKeyStore.ensurePublicKey(pendingKeyId))
    val snapshot = repository.create(
        request = CreateDealRequest(
            quoteId = quoteId,
            amount = amount,
            receiveAddress = receiveAddress,
            buyerPubKey = buyerPubKey,
            fiatCurrency = fiatCurrency,
            fiatAmount = fiatAmount,
        ),
        fiatAmount = fiatAmount,
        fiatCurrency = fiatCurrency,
        recoveryLink = null,
        offerExpiresAtEpochMs = offerExpiresAtEpochMs,
    )
    container.dealKeyStore.move(pendingKeyId, snapshot.dealId)
    return snapshot
}
