package p2pgate.tui.buyer

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.NetworkType

/**
 * secp256k1 key material for the buyer console. Small and local for the same
 * reason the backend's copy is: `:apps:core:ergo`'s equivalents are internal, and
 * pulling the whole backend in as a dependency to reach them would invert the
 * module graph (a UI must not depend on the server it talks to).
 *
 * The generated secret is the R5 deal key of `specs/deal-protocol.md` §2 — it is
 * written straight to the encrypted [KeyVault] file and never persisted anywhere
 * else.
 */
object BuyerKeys {

    private val spec: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
    private val random = SecureRandom()

    /** A fresh 32-byte secret, reduced into the curve order so it is a valid scalar. */
    fun generateSecret(): ByteArray {
        while (true) {
            val candidate = ByteArray(32).also(random::nextBytes)
            val scalar = BigInteger(1, candidate)
            if (scalar > BigInteger.ZERO && scalar < spec.n) return candidate
        }
    }

    /** The secret as a scalar, for the prover builder. */
    fun secretScalar(secret: ByteArray): BigInteger = BigInteger(1, secret)

    /** The 33-byte compressed secp256k1 public point of [secret]. */
    fun publicKeyCompressed(secret: ByteArray): ByteArray =
        params.g.multiply(secretScalar(secret)).normalize().getEncoded(true)

    /**
     * The Ergo P2PK address a buyer publishes as `receiveAddress` at deal
     * creation, and the address a path-D payout change returns to.
     */
    fun address(secret: ByteArray, networkType: NetworkType): String =
        addressOf(publicKeyCompressed(secret), networkType)

    /** The network's address for [compressedPubKey] — e.g. a vault box's R5. */
    fun addressOf(compressedPubKey: ByteArray, networkType: NetworkType): String =
        Address.fromSigmaBoolean(
            sigma.data.ProveDlog.apply(
                sigma.crypto.CryptoContext.default().decodePoint(compressedPubKey),
            ),
            networkType,
        ).toString()
}