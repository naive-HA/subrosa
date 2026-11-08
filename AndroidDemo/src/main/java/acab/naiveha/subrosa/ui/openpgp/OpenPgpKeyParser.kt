package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import org.bouncycastle.bcpg.ECPublicBCPGKey
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.util.encoders.Hex
import java.util.Date

data class OpenPgpSubkeyInfo(
    val fingerprint: String,
    val algorithm: String,
    val creationDate: Date,
    val expirationDate: Date?,
    val usage: String,
    val plan: KeyPlan = KeyPlan(),
    val validity: KeyValidity = KeyValidity.VALID,
)

data class OpenPgpKeyInfo(
    val userId: String,
    val fingerprint: String,
    val creationDate: Date,
    val expirationDate: Date?,
    val algorithm: String,
    val keyCount: Int,
    val keys: List<OpenPgpSubkeyInfo>,
    val isPassphraseProtected: Boolean,
    val asOf: Date = Date(),
)
object OpenPgpKeyParser {
    private const val TAG = "OpenPgpKeyParser"
    private const val PGP_PRIVATE_ARMOR_HEADER = "-----BEGIN PGP PRIVATE KEY BLOCK-----"
    fun isOpenPgpPrivateKey(text: String): Boolean =
        text.trimStart().startsWith(PGP_PRIVATE_ARMOR_HEADER)
    fun parse(armor: String): Result<OpenPgpKeyInfo> = runCatching {
        Log.d(TAG, "parse() called — armor length=${armor.length} chars")
        val asOf = Date()
        val ring = OpenPgpKeySupport.readSupportedRing(armor, asOf)
            ?: error("Decoded object is not a PGP secret key ring")
        val primaryPublicKey = ring.secretKey.publicKey
        val userId = primaryPublicKey.userIDs.asSequence().firstOrNull() ?: "<no user ID>"
        Log.d(TAG, "Primary key hasUserId=${userId != "<no user ID>"} algorithm=${primaryPublicKey.algorithm} " +
                   "bitStrength=${primaryPublicKey.bitStrength}")
        val creationDate = primaryPublicKey.creationTime
        val expirationDate = primaryPublicKey.expirationDate()
        val formatFingerprint = { raw: ByteArray ->
            val chunks = Hex.toHexString(raw).uppercase().chunked(4)
            if (chunks.size >= 10) {
                val l1 = chunks.subList(0, 5).joinToString(" ")
                val l2 = chunks.subList(5, 10).joinToString(" ")
                "$l1\n$l2"
            } else {
                chunks.joinToString(" ")
            }
        }
        val fingerprint = formatFingerprint(primaryPublicKey.fingerprint)
        val algorithm = algorithmLabel(primaryPublicKey)
        
        val allSecretKeys = ring.secretKeys.asSequence().toList()
        val allPublicKeys = ring.publicKeys.asSequence().toList()

        val plans = SlotPlanner.explain(allSecretKeys.map { it.publicKey.toKeyTraits(asOf) })
        val plansByFingerprint = allSecretKeys.zip(plans).associate { (sk, plan) ->
            Hex.toHexString(sk.publicKey.fingerprint) to plan
        }

        val keyInfos = allPublicKeys.map { pk ->
            val subCreationDate = pk.creationTime
            val subExpirationDate = pk.expirationDate()

            val flags = pk.keyFlags()
            val usage = mutableListOf<String>()
            if (pk.isMasterKey) usage.add("Primary")

            if (flags != 0) {
                if (flags and KeyFlags.CERTIFY_OTHER != 0) usage.add("Certify")
                if (flags and KeyFlags.SIGN_DATA != 0) usage.add("Signature")
                if (flags and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0) usage.add("Encryption")
                if (flags and KeyFlags.AUTHENTICATION != 0) usage.add("Authentication")
            } else {
                if (pk.isEncryptionKey) usage.add("Encryption")
                if (pk.algorithm == PublicKeyAlgorithmTags.DSA ||
                    pk.algorithm == PublicKeyAlgorithmTags.ECDSA ||
                    pk.algorithm == PublicKeyAlgorithmTags.EDDSA_LEGACY ||
                    pk.algorithm == 27) usage.add("Signature")
                if (!pk.isMasterKey && usage.isEmpty()) usage.add("Authentication")
            }

            OpenPgpSubkeyInfo(
                fingerprint = formatFingerprint(pk.fingerprint),
                algorithm = algorithmLabel(pk),
                creationDate = subCreationDate,
                expirationDate = subExpirationDate,
                usage = usage.joinToString(", ").ifEmpty { "General" },
                plan = plansByFingerprint[Hex.toHexString(pk.fingerprint)]
                    ?: KeyPlan(skipReason = SkipReason.NO_PRIVATE_KEY),
                validity = pk.validity(asOf),
            )
        }

        val keyCount = keyInfos.size
        Log.d(TAG, "Ring contains $keyCount key(s) total")
        
        allSecretKeys.forEachIndexed { i, sk ->
            val role = if (sk.publicKey.isMasterKey) "primary" else "subkey[$i]"
            Log.d(TAG, "  $role: algorithm=${sk.publicKey.algorithm} " +
                       "keyFlags=${sk.publicKey.keyFlags()} " +
                       "isEncryptionKey=${sk.publicKey.isEncryptionKey} " +
                       "s2KUsage=${sk.s2KUsage} " +
                       "(0=plaintext, 254=SHA1-protected, 255=checksum-protected)")
        }
        val isPassphraseProtected = allSecretKeys.any { it.s2KUsage != 0 }
        Log.i(TAG, "isPassphraseProtected=$isPassphraseProtected")

        OpenPgpKeyInfo(
            userId                = userId,
            fingerprint           = fingerprint,
            creationDate          = creationDate,
            expirationDate        = expirationDate,
            algorithm             = algorithm,
            keyCount              = keyCount,
            keys                  = keyInfos,
            isPassphraseProtected = isPassphraseProtected,
            asOf                  = asOf,
        ).also {
            Log.i(TAG, "parse() success — algorithm=$algorithm fingerprint=$fingerprint " +
                       "expires=${expirationDate ?: "never"}")
        }
    }.onFailure { e ->
        Log.e(TAG, "parse() failed: ${e::class.simpleName}: ${e.message}", e)
    }
    @Suppress("DEPRECATION")
    private fun algorithmLabel(key: PGPPublicKey): String {
        val algo = key.algorithm
        val bits = if (key.bitStrength > 0) " ${key.bitStrength}" else ""
        return when (algo) {
            PublicKeyAlgorithmTags.RSA_GENERAL,
            PublicKeyAlgorithmTags.RSA_SIGN,
            PublicKeyAlgorithmTags.RSA_ENCRYPT     -> "RSA$bits"
            PublicKeyAlgorithmTags.DSA             -> "DSA$bits"
            PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT,
            PublicKeyAlgorithmTags.ELGAMAL_GENERAL -> "ElGamal$bits"
            PublicKeyAlgorithmTags.ECDSA           -> ecAlgorithmLabel("ECDSA", curveLabel(key))
            PublicKeyAlgorithmTags.ECDH            -> ecAlgorithmLabel("ECDH", curveLabel(key))
            PublicKeyAlgorithmTags.EDDSA_LEGACY    -> ecAlgorithmLabel("EdDSA", curveLabel(key) ?: "Ed25519")
            25                                     -> "X25519 (RFC 9580)"
            26                                     -> "X448 (RFC 9580)"
            27                                     -> "Ed25519 (RFC 9580)"
            28                                     -> "Ed448 (RFC 9580)"
            else                                   -> "Unknown (tag $algo)"
        }
    }

    private fun curveLabel(key: PGPPublicKey): String? {
        val oid = (key.publicKeyPacket.key as? ECPublicBCPGKey)?.curveOID?.id ?: return null
        return CurveRegistry.forDottedOid(oid)?.label ?: "OID $oid"
    }
}
