package p2pgate.app.keys

/**
 * Deal-scoped key storage (`specs/android-app.md` §2.1): one secp256k1 keypair
 * per deal, generated at deal creation, deletable. In v2 the buyer key signs
 * **nothing** on the protocol paths — it identifies the buyer in the deal
 * terms (vault R6) and backs the future claim/recovery path, so this seam
 * exposes no signing surface yet.
 */
interface DealKeyStore {
    /** Returns the deal's 33-byte compressed public key, generating the keypair if absent. */
    fun ensurePublicKey(dealId: String): ByteArray

    fun publicKey(dealId: String): ByteArray?

    /** Re-keys a deal entry (a key minted before the deal id was known). */
    fun move(fromDealId: String, toDealId: String)

    fun delete(dealId: String)
}
