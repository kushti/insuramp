package p2pgate.app.keys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android storage for deal keys: the secp256k1 secrets are held by
 * [BouncyCastleDealKeyStore] in memory and, at rest, AES/GCM-encrypted under a
 * hardware-backed-when-available **AES key in Android Keystore** (the Keystore
 * has no secp256k1 support, so the deal key cannot live in it natively — see
 * [BouncyCastleDealKeyStore]). Encrypted blobs sit in the app's private
 * SharedPreferences; deletion wipes both.
 *
 * If Keystore is unavailable (should not happen on API 26+), the store falls
 * back to memory-only — deal keys then don't survive process death, and the
 * buyer re-imports from the exported deal-key seed (recovery, §6.3).
 */
class AndroidKeystoreDealKeyStore(
    context: Context,
    private val delegate: BouncyCastleDealKeyStore = BouncyCastleDealKeyStore(),
) : DealKeyStore {

    private val prefs = context.getSharedPreferences("p2pgate_deal_keys", Context.MODE_PRIVATE)

    override fun ensurePublicKey(dealId: String): ByteArray {
        delegate.publicKey(dealId)?.let { return it }
        load(dealId)?.let { return it }
        val generated = delegate.ensurePublicKey(dealId)
        save(dealId)
        return generated
    }

    override fun publicKey(dealId: String): ByteArray? =
        delegate.publicKey(dealId) ?: load(dealId)

    override fun move(fromDealId: String, toDealId: String) {
        val pub = delegate.publicKey(fromDealId)
        delegate.move(fromDealId, toDealId)
        if (pub != null) {
            // Re-encrypt the secret under the real deal id, drop the pending blobs.
            save(toDealId)
            prefs.edit().remove(blobKey(fromDealId)).remove(ivKey(fromDealId)).apply()
        }
    }

    override fun delete(dealId: String) {
        delegate.delete(dealId)
        prefs.edit().remove(blobKey(dealId)).remove(ivKey(dealId)).apply()
    }

    private fun blobKey(dealId: String) = "secret_$dealId"
    private fun ivKey(dealId: String) = "iv_$dealId"

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getEntry(KEY_ALIAS, null)?.let { return (it as KeyStore.SecretKeyEntry).secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun save(dealId: String) {
        val secret = delegate.secretOf(dealId)?.toByteArray() ?: return
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val blob = cipher.doFinal(secret)
        prefs.edit()
            .putString(blobKey(dealId), android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP))
            .putString(ivKey(dealId), android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .apply()
    }

    private fun load(dealId: String): ByteArray? {
        val blobB64 = prefs.getString(blobKey(dealId), null) ?: return null
        val ivB64 = prefs.getString(ivKey(dealId), null) ?: return null
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                aesKey(),
                GCMParameterSpec(128, android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)),
            )
            val secretBytes = cipher.doFinal(android.util.Base64.decode(blobB64, android.util.Base64.NO_WRAP))
            delegate.load(dealId, java.math.BigInteger(1, secretBytes))
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "p2pgate_deal_key_wrap"
        private const val AES_GCM = "AES/GCM/NoPadding"
    }
}
