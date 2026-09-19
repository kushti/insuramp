package p2pgate.app.deal

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import p2pgate.app.R
import p2pgate.dealprotocol.DealState

/**
 * The vertical timeline projection (`onramp-ux.md` §2.2): the happy path
 * FUNDED → PAYMENT_PENDING → PAYMENT_CONFIRMED → RELEASED rendered
 * top-to-bottom, with the dispute branch (CLAIM_OPENED → CLAIMABLE → CLAIMED)
 * replacing the pending rows once a claim opens. Canonical state names from
 * `:core:dealprotocol` are rendered verbatim — the display text is a localized
 * string resource (resolved by the UI layer), the machine name is shown as-is.
 */
data class TimelineRow(
    val state: DealState,
    /** String resource of the row's localized human label ([stateLabelRes]). */
    @StringRes val labelRes: Int,
    val status: RowStatus,
)

enum class RowStatus { DONE, ACTIVE, PENDING }

/**
 * Canonical protocol state → the string resource of its localized human label
 * (`res/values/strings.xml` and the hi/sw/ar translations). Pure mapping with
 * no Resources lookup, so the JVM unit tests assert it directly.
 */
@StringRes
fun stateLabelRes(state: DealState): Int = when (state) {
    DealState.QUOTED -> R.string.state_quoted
    DealState.FUNDED -> R.string.state_funded
    DealState.PAYMENT_PENDING -> R.string.state_payment_pending
    DealState.PAYMENT_CONFIRMED -> R.string.state_payment_confirmed
    DealState.CLAIM_OPENED -> R.string.state_claim_opened
    DealState.CLAIMABLE -> R.string.state_claimable
    DealState.RELEASED -> R.string.state_released
    DealState.RECLAIMED -> R.string.state_reclaimed
    DealState.CLAIMED -> R.string.state_claimed
}

/** The localized human label for a canonical protocol state. */
@Composable
fun stateLabel(state: DealState): String = stringResource(stateLabelRes(state))

object DealTimeline {

    private val happyPath: List<DealState> = listOf(
        DealState.FUNDED,
        DealState.PAYMENT_PENDING,
        DealState.PAYMENT_CONFIRMED,
        DealState.RELEASED,
    )

    private val claimBranch: List<DealState> = listOf(
        DealState.CLAIM_OPENED,
        DealState.CLAIMABLE,
        DealState.CLAIMED,
    )

    fun rowsFor(state: DealState): List<TimelineRow> {
        if (state in claimBranch) {
            // FUNDED + PAYMENT_PENDING happened before any claim.
            val done = happyPath.take(2).map { TimelineRow(it, stateLabelRes(it), RowStatus.DONE) }
            val claimIndex = claimBranch.indexOf(state)
            return done + claimBranch.mapIndexed { i, s ->
                TimelineRow(
                    s, stateLabelRes(s),
                    when {
                        i < claimIndex -> RowStatus.DONE
                        i == claimIndex -> if (s.isTerminal) RowStatus.DONE else RowStatus.ACTIVE
                        else -> RowStatus.PENDING
                    },
                )
            }
        }
        val index = happyPath.indexOf(state)
        if (index < 0) {
            // QUOTED, terminal-without-claim, or unknown: nothing active yet.
            return happyPath.map { TimelineRow(it, stateLabelRes(it), RowStatus.PENDING) }
        }
        return happyPath.mapIndexed { i, s ->
            TimelineRow(
                s, stateLabelRes(s),
                when {
                    i < index -> RowStatus.DONE
                    i == index -> if (s.isTerminal) RowStatus.DONE else RowStatus.ACTIVE
                    else -> RowStatus.PENDING
                },
            )
        }
    }

    /** The permanent dispute button availability (§3.4): from PAYMENT_PENDING on. */
    fun claimAvailable(state: DealState): Boolean =
        state == DealState.PAYMENT_PENDING ||
            state == DealState.PAYMENT_CONFIRMED ||
            state in claimBranch
}
