package p2pgate.ergo

/**
 * Chain-read seam for the apps, `specs/android-app.md` §4.1: the deal-protocol
 * layer and the vault tracker consume chain facts only through this interface,
 * so the public explorer API (`ExplorerChainSource`, the default) can be swapped
 * for a user-configured full node without touching call sites.
 *
 * The shapes are plain values — no appkit/sigma types leak across the interface —
 * so Android-facing code never has to link the JVM chain stack to *read* state.
 * Builders ([ClaimTxBuilder]) convert these shapes to chain types internally.
 */
interface ChainSource {

    /** The box with [boxId], or `null` when it does not exist (spent-and-pruned or unknown). */
    fun getBox(boxId: String): ChainBox?

    /**
     * The transaction that spent [boxId] (with its outputs and inclusion height),
     * or `null` when the box is unknown or still unspent.
     */
    fun getSpendingTransaction(boxId: String): ChainSpend?

    /** Current best height. */
    fun getCurrentHeight(): Int

    /** Unspent boxes at [address] — used for miner-fee funding (app-side selection). */
    fun getUnspentBoxes(address: String): List<ChainBox>
}

/** A token amount carried by a box: [tokenId] is the 32-byte base16 token id. */
data class ChainToken(val tokenId: String, val amount: Long)

/**
 * A decoded non-Mandatory register value (R4–R9). Explorers serve registers as
 * sigma-serialized constant hex; both the raw wire bytes and the decoded value
 * are kept so boxes can be re-serialized for transaction building unchanged.
 */
sealed interface ChainRegister {

    /** The sigma-serialized constant bytes (exactly what explorers serve as hex). */
    val serialized: ByteArray

    /** `Coll[Byte]` register (e.g. dealId, pubkeys, oracleNftId, record id). */
    class CollBytes(
        val value: ByteArray,
        override val serialized: ByteArray = ErgoValues.serializedBytes(value),
    ) : ChainRegister

    /** `Long` register (FUNDED R8 timeoutHeight / PAYMENT_PROVEN R7 proofHeight). */
    class Int64(
        val value: Long,
        override val serialized: ByteArray = ErgoValues.serializedBytes(value),
    ) : ChainRegister
}

/**
 * An Ergo box as chain-agnostic data. [registers] has exactly 6 slots, index
 * `r - 4` = register Rr (a `null` slot means the register is absent).
 */
class ChainBox(
    val boxId: String,
    val transactionId: String,
    val index: Int,
    val value: Long,
    val creationHeight: Int,
    val ergoTreeHex: String,
    val address: String,
    val tokens: List<ChainToken>,
    val registers: List<ChainRegister?>,
    /** Id of the transaction that spent this box, or `null` while unspent. */
    val spentTransactionId: String? = null,
) {
    init {
        require(registers.size == 6) { "registers must cover R4..R9 (6 slots), got ${registers.size}" }
        require(value >= 0) { "box value must be non-negative, got $value" }
    }

    /** Raw bytes of Coll[Byte] register Rr (r in 4..9), or `null` if absent/mistyped. */
    fun registerBytes(r: Int): ByteArray? = registers.getOrNull(r - 4)?.let { (it as? ChainRegister.CollBytes)?.value }

    /** Value of Long register Rr (r in 4..9), or `null` if absent/mistyped. */
    fun registerLong(r: Int): Long? = registers.getOrNull(r - 4)?.let { (it as? ChainRegister.Int64)?.value }

    /** Total nanoERG of [tokens] id [tokenId] (0 when the box carries none). */
    fun tokenAmount(tokenId: String): Long = tokens.filter { it.tokenId.equals(tokenId, ignoreCase = true) }.sumOf { it.amount }

    override fun toString(): String =
        "ChainBox(boxId=$boxId, value=$value, height=$creationHeight, tokens=${tokens.size}, spent=$spentTransactionId)"
}

/** The transaction that spent a watched box: outputs plus inclusion height. */
class ChainSpend(
    val txId: String,
    val height: Int,
    val outputs: List<ChainBox>,
    /**
     * Token ids carried by the spending tx's inputs (best-effort, as reported
     * by the chain backend). Legacy release discriminator: releases used to
     * carry the oracle box as a full input (its NFT among the input tokens);
     * since the oracle box became a release DATA input this signal no longer
     * fires on backends that report only spent inputs — the spend-height
     * fallback discriminates release from reclaim instead.
     */
    val inputTokenIds: List<String> = emptyList(),
)
