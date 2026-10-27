package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.core.keys.PrivateKeyValues
import com.yubico.yubikit.openpgp.KeyRef
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter
import java.io.ByteArrayInputStream
import javax.security.auth.DestroyFailedException

data class SlotData(
    val ref: KeyRef,
    val privateKeyValues: PrivateKeyValues,
    val fingerprint: ByteArray,
    val creationTimestamp: Int,
)
data class ImportBundle(
    val nameBytes: ByteArray,
    val loginBytes: ByteArray,
    val slots: List<SlotData>,
) {
    fun destroy() {
        slots.forEach {
            try { it.privateKeyValues.destroy() } catch (_: DestroyFailedException) {}
        }
    }
}
object OpenPgpKeyImporter {
    private const val TAG = "OpenPgpKeyImporter"
    private val bcProvider = BouncyCastleProvider()
    fun prepare(armor: String, passphrase: CharArray = CharArray(0)): ImportBundle {
        Log.d(TAG, "prepare() — armor length=${armor.length} chars, passphrase length=${passphrase.size} (isEmpty=${passphrase.isEmpty()})")
        Log.d(TAG, "Decoding armored input…")
        val inputStream = ArmoredInputStream(ByteArrayInputStream(armor.toByteArray(Charsets.UTF_8)))
        val factory = PGPObjectFactory(inputStream, BcKeyFingerprintCalculator())
        val ring = factory.nextObject() as? PGPSecretKeyRing?: throw IllegalArgumentException("Input is not a PGP secret key ring")
        Log.d(TAG, "Armor decoded successfully")
        Log.d(TAG, "Building PBE decryptor (passphrase length=${passphrase.size})")
        val decryptor = BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(passphrase)
        val slots = mutableListOf<SlotData>()
        var displayName = ""
        var email = ""
        val secretKeys = ring.secretKeys.asSequence().toList()
        val plan = SlotPlanner.plan(secretKeys.map { sk ->
            val pk = sk.publicKey
            KeyTraits(
                isPrimary  = pk.isMasterKey,
                keyFlags   = pk.keyFlags(),
                canEncrypt = pk.isEncryptionKey,
            )
        })
        for ((index, secretKey) in secretKeys.withIndex()) {
            val pubKey   = secretKey.publicKey
            val keyIdHex = java.lang.Long.toHexString(pubKey.keyID).uppercase()
            Log.d(TAG, "Processing key 0x$keyIdHex — " +
                       "isMaster=${pubKey.isMasterKey} " +
                       "isEncryption=${pubKey.isEncryptionKey} " +
                       "algorithm=${pubKey.algorithm} " +
                       "keyFlags=0x${pubKey.keyFlags().toString(16)} " +
                       "s2KUsage=${secretKey.s2KUsage} " +
                       "(0=plaintext,254=SHA1,255=checksum)")
            if (pubKey.isMasterKey) {
                val uid = pubKey.userIDs.asSequence().firstOrNull() ?: ""
                parseUid(uid).let { (n, e) -> displayName = n; email = e }
                Log.d(TAG, "Primary key UID='$uid' → name='$displayName' email='$email'")
            }
            val refs = plan[index]
            if (refs.isEmpty()) {
                Log.d(TAG, "  Key 0x$keyIdHex skipped (no slot assigned, keyFlags=0x${pubKey.keyFlags().toString(16)})")
                continue
            }
            Log.d(TAG, "  Key 0x$keyIdHex → slot ${refs.joinToString(", ") { it.name }}")
            Log.d(TAG, "  Calling extractPrivateKey() on 0x$keyIdHex…")
            val pgpPrivKey = try {
                secretKey.extractPrivateKey(decryptor)
            } catch (e: Exception) {
                Log.e(TAG, "  extractPrivateKey() FAILED for 0x$keyIdHex " +
                           "(s2KUsage=${secretKey.s2KUsage}, " +
                           "passphraseLength=${passphrase.size}): " +
                           "${e::class.simpleName}: ${e.message}", e)
                throw e
            }
            Log.d(TAG, "  extractPrivateKey() succeeded for 0x$keyIdHex")
            Log.d(TAG, "  Converting to JCA PrivateKey via JcaPGPKeyConverter…")
            val jcaKey = try {
                JcaPGPKeyConverter()
                    .setProvider(bcProvider)
                    .getPrivateKey(pgpPrivKey)
            } catch (e: Exception) {
                Log.e(TAG, "  JcaPGPKeyConverter.getPrivateKey() FAILED for 0x$keyIdHex: ${e::class.simpleName}: ${e.message}", e)
                throw e
            }
            Log.d(TAG, "  JCA conversion succeeded — keyAlgorithm=${jcaKey.algorithm} format=${jcaKey.format}")
            for (ref in refs) {
                Log.d(TAG, "  Calling PrivateKeyValues.fromPrivateKey() for slot $ref…")
                val privateKeyValues = try {
                    PrivateKeyValues.fromPrivateKey(jcaKey)
                } catch (e: Exception) {
                    Log.e(TAG, "  PrivateKeyValues.fromPrivateKey() FAILED for 0x$keyIdHex: ${e::class.simpleName}: ${e.message}", e)
                    throw e
                }
                Log.d(TAG, "  PrivateKeyValues created — type=${privateKeyValues::class.simpleName}")
                slots += SlotData(
                    ref                      = ref,
                    privateKeyValues         = privateKeyValues,
                    fingerprint              = pubKey.fingerprint,
                    creationTimestamp        = (pubKey.creationTime.time / 1_000L).toInt(),
                )
                Log.i(TAG, "  Slot $ref populated ✓")
            }
        }
        require(slots.isNotEmpty()) { "No usable private key slots found in the key ring" }
        val bundle = ImportBundle(
            nameBytes  = formatName(displayName),
            loginBytes = email.toByteArray(Charsets.UTF_8),
            slots      = slots,
        )
        Log.i(TAG, "prepare() complete — slots=${slots.map { it.ref.name }} name='${String(bundle.nameBytes)}' loginLength=${bundle.loginBytes.size}")
        return bundle
    }
    private fun parseUid(uid: String): Pair<String, String> {
        val match = Regex("""^(.*?)\s*<([^>]+)>\s*$""").find(uid.trim())
        return if (match != null) {
            match.groupValues[1].trim() to match.groupValues[2].trim()
        } else {
            uid.trim() to ""
        }
    }
    private fun formatName(displayName: String): ByteArray {
        if (displayName.isBlank()) return ByteArray(0)
        val parts     = displayName.trim().split(Regex("\\s+"))
        val formatted = if (parts.size >= 2) {
            val surname = parts.last().uppercase().replace(' ', '<')
            val given   = parts.dropLast(1).joinToString("<") { it.uppercase() }
            "$surname<<$given"
        } else {
            parts[0].uppercase()
        }
        val bytes = formatted.toByteArray(Charsets.UTF_8)
        return if (bytes.size > 39) bytes.copyOf(39) else bytes
    }

}