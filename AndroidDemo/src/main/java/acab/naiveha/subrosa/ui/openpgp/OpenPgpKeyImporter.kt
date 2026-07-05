package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.core.keys.PrivateKeyValues
import com.yubico.yubikit.openpgp.KeyRef
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.RSAPublicBCPGKey
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.security.auth.DestroyFailedException

data class SlotData(
    val ref: KeyRef,
    val privateKeyValues: PrivateKeyValues,
    val fingerprint: ByteArray,
    val creationTimestamp: Int,
    val declaredModulusBitLength: Int? = null,
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
    private const val TAG = "OpenPgpImporter"
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
        var decSeen = false
        var autSeen = false
        var displayName = ""
        var email = ""
        for (secretKey in ring.secretKeys) {
            val pubKey   = secretKey.publicKey
            val keyIdHex = java.lang.Long.toHexString(pubKey.keyID).uppercase()
            Log.d(TAG, "Processing key 0x$keyIdHex — " +
                       "isMaster=${pubKey.isMasterKey} " +
                       "isEncryption=${pubKey.isEncryptionKey} " +
                       "algorithm=${pubKey.algorithm} " +
                       "keyFlags=0x${getKeyFlags(pubKey).toString(16)} " +
                       "s2KUsage=${secretKey.s2KUsage} " +
                       "(0=plaintext,254=SHA1,255=checksum)")
            val declaredModulusBitLength = extractDeclaredRsaModulusBitLength(pubKey, keyIdHex)
            if (pubKey.isMasterKey) {
                val uid = pubKey.userIDs.asSequence().firstOrNull() ?: ""
                parseUid(uid).let { (n, e) -> displayName = n; email = e }
                Log.d(TAG, "Primary key UID='$uid' → name='$displayName' email='$email'")
            }
            val keyFlags = getKeyFlags(pubKey)
            val ref: KeyRef = when {
                pubKey.isMasterKey -> KeyRef.SIG
                (keyFlags and KeyFlags.AUTHENTICATION) != 0 && !autSeen -> {
                    autSeen = true
                    KeyRef.AUT
                }
                (keyFlags and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)) != 0 && !decSeen -> {
                    decSeen = true
                    KeyRef.DEC
                }
                keyFlags == 0 && pubKey.isEncryptionKey && !decSeen -> {
                    decSeen = true
                    KeyRef.DEC
                }
                keyFlags == 0 && !pubKey.isEncryptionKey && !autSeen -> {
                    autSeen = true
                    KeyRef.AUT
                }
                else -> {
                    Log.d(TAG, "  Key 0x$keyIdHex skipped (no remaining slot, keyFlags=0x${keyFlags.toString(16)})")
                    continue
                }
            }
            Log.d(TAG, "  Key 0x$keyIdHex → slot $ref")
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
            Log.d(TAG, "  Calling PrivateKeyValues.fromPrivateKey()…")
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
                declaredModulusBitLength = declaredModulusBitLength,
            )
            Log.i(TAG, "  Slot $ref populated ✓")
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
    private fun getKeyFlags(pubKey: PGPPublicKey): Int {
        val sigs = pubKey.signatures
        while (sigs.hasNext()) {
            val sig = sigs.next() as org.bouncycastle.openpgp.PGPSignature
            val flags = sig.hashedSubPackets?.keyFlags ?: 0
            if (flags != 0) return flags
        }
        return 0
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

    private fun extractDeclaredRsaModulusBitLength(pubKey: PGPPublicKey, keyIdHex: String): Int? {
        return try {
            val bcKey = pubKey.publicKeyPacket.key as? RSAPublicBCPGKey ?: return null
            val baos = ByteArrayOutputStream()
            BCPGOutputStream(baos).use { bcKey.encode(it) }
            val encoded = baos.toByteArray()
            if (encoded.size < 2) return null
            val declared = ((encoded[0].toInt() and 0xFF) shl 8) or (encoded[1].toInt() and 0xFF)
            val computed = bcKey.modulus.bitLength()
            if (declared != computed) {
                Log.w(TAG, "Key 0x$keyIdHex: declared RSA nLen=$declared but " +
                           "BigInteger.bitLength()=$computed — " +
                           "using declared ($declared) for Algorithm Attributes DO")
            } else {
                Log.d(TAG, "Key 0x$keyIdHex: RSA nLen=$declared (declared==computed)")
            }
            declared
        } catch (e: Exception) {
            Log.w(TAG, "Key 0x$keyIdHex: could not extract declared RSA modulus bit length " +
                       "(${e::class.simpleName}: ${e.message}) — will fall back to BigInteger.bitLength()")
            null
        }
    }
}