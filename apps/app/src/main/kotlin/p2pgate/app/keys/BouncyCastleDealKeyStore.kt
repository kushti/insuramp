package p2pgate.app.keys

import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import java.math.BigInteger
import java.security.SecureRandom

/**
 * BouncyCastle secp256k1 keypair store (JVM-pure; the unit-test double and the
 * in-process fallback on devices). Deal keys are secp256k1 because Ergo is —
 * and Android Keystore does not support that curve (its EC support is the
 * NIST set), which is why the Android wrapper encrypts these secrets under a
 * Keystore-held AES key instead of storing the keypair natively.
 */
open class BouncyCastleDealKeyStore : DealKeyStore {
    protected val spec = CustomNamedCurves.getByName("secp256k1")
    protected val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
    protected val random = SecureRandom()
    private val secrets = HashMap<String, BigInteger>()

    override fun ensurePublicKey(dealId: String): ByteArray =
        publicKey(dealId) ?: generate(dealId)

    override fun publicKey(dealId: String): ByteArray? =
        secrets[dealId]?.let { secret ->
            params.g.multiply(secret).normalize().getEncoded(true)
        }

    override fun move(fromDealId: String, toDealId: String) {
        secrets.remove(fromDealId)?.let { secrets[toDealId] = it }
    }

    override fun delete(dealId: String) {
        secrets.remove(dealId)
    }

    internal fun load(dealId: String, secret: BigInteger): ByteArray {
        secrets[dealId] = secret
        return params.g.multiply(secret).normalize().getEncoded(true)
    }

    /** The in-memory secret — for at-rest encryption by the Android wrapper. */
    internal fun secretOf(dealId: String): BigInteger? = secrets[dealId]

    private fun generate(dealId: String): ByteArray {
        val gen = ECKeyPairGenerator()
        gen.init(ECKeyGenerationParameters(params, random))
        val kp = gen.generateKeyPair()
        val priv = kp.private as ECPrivateKeyParameters
        val pub = kp.public as ECPublicKeyParameters
        secrets[dealId] = priv.d
        return pub.q.normalize().getEncoded(true)
    }
}
