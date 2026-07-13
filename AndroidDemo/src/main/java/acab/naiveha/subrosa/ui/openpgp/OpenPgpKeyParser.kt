package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayInputStream
import java.util.Date

data class OpenPgpSubkeyInfo(
    val fingerprint: String,
    val algorithm: String,
    val creationDate: Date,
    val expirationDate: Date?,
    val usage: String,
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
)
object OpenPgpKeyParser {
    private const val TAG = "OpenPgpParser"
    private const val PGP_PRIVATE_ARMOR_HEADER = "-----BEGIN PGP PRIVATE KEY BLOCK-----"
    fun isOpenPgpPrivateKey(text: String): Boolean =
        text.trimStart().startsWith(PGP_PRIVATE_ARMOR_HEADER)
    fun parse(armor: String): Result<OpenPgpKeyInfo> = runCatching {
        Log.d(TAG, "parse() called — armor length=${armor.length} chars")
        val inputStream = ArmoredInputStream(
            ByteArrayInputStream(armor.toByteArray(Charsets.UTF_8))
        )
        val factory = PGPObjectFactory(inputStream, BcKeyFingerprintCalculator())
        val ring = factory.nextObject() as? PGPSecretKeyRing
            ?: error("Decoded object is not a PGP secret key ring")
        val primaryPublicKey = ring.secretKey.publicKey
        val userId = primaryPublicKey.userIDs.asSequence().firstOrNull() ?: "<no user ID>"
        Log.d(TAG, "Primary key userId='$userId' algorithm=${primaryPublicKey.algorithm} " +
                   "bitStrength=${primaryPublicKey.bitStrength}")
        val creationDate = primaryPublicKey.creationTime
        val validSeconds = primaryPublicKey.validSeconds
        val expirationDate = if (validSeconds > 0L) {
            Date(creationDate.time + validSeconds * 1_000L)
        } else null
        val fingerprint = Hex.toHexString(primaryPublicKey.fingerprint)
            .uppercase()
            .chunked(4)
            .joinToString(" ")
        val algorithm = algorithmLabel(primaryPublicKey.algorithm, primaryPublicKey.bitStrength)
        
        val allSecretKeys = ring.secretKeys.asSequence().toList()
        val allPublicKeys = ring.publicKeys.asSequence().toList()
        
        val keyInfos = allPublicKeys.map { pk ->
            val subCreationDate = pk.creationTime
            val subValidSeconds = pk.validSeconds
            val subExpirationDate = if (subValidSeconds > 0L) {
                Date(subCreationDate.time + subValidSeconds * 1_000L)
            } else null
            
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
                fingerprint = Hex.toHexString(pk.fingerprint).uppercase().chunked(4).joinToString(" "),
                algorithm = algorithmLabel(pk.algorithm, pk.bitStrength),
                creationDate = subCreationDate,
                expirationDate = subExpirationDate,
                usage = usage.joinToString(", ").ifEmpty { "General" }
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
        ).also {
            Log.i(TAG, "parse() success — algorithm=$algorithm fingerprint=$fingerprint " +
                       "expires=${expirationDate ?: "never"}")
        }
    }.onFailure { e ->
        Log.e(TAG, "parse() failed: ${e::class.simpleName}: ${e.message}", e)
    }
    @Suppress("DEPRECATION")
    private fun algorithmLabel(algo: Int, bitStrength: Int): String {
        val bits = if (bitStrength > 0) " $bitStrength" else ""
        return when (algo) {
            PublicKeyAlgorithmTags.RSA_GENERAL,
            PublicKeyAlgorithmTags.RSA_SIGN,
            PublicKeyAlgorithmTags.RSA_ENCRYPT     -> "RSA$bits"
            PublicKeyAlgorithmTags.DSA             -> "DSA$bits"
            PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT,
            PublicKeyAlgorithmTags.ELGAMAL_GENERAL -> "ElGamal$bits"
            PublicKeyAlgorithmTags.ECDSA           -> "ECDSA"
            PublicKeyAlgorithmTags.ECDH            -> "ECDH"
            PublicKeyAlgorithmTags.EDDSA_LEGACY    -> "EdDSA (Ed25519)"
            27                                     -> "Ed25519 (RFC 9580)"
            29                                     -> "Ed448 (RFC 9580)"
            25                                     -> "X25519 (RFC 9580)"
            30                                     -> "X448 (RFC 9580)"
            else                                   -> "Unknown (tag $algo)"
        }
    }
}
