package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPBEEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBEDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object OpenPgpFileImporter {
    private const val TAG = "OpenPgpFileImporter"
    fun decrypt(bytes: ByteArray, passphrase: CharArray): PGPSecretKeyRing {
        Log.d(TAG, "decrypt() — fileSize=${bytes.size} B passphraseLength=${passphrase.size}")
        try {
            val decoderStream = PGPUtil.getDecoderStream(ByteArrayInputStream(bytes))
            val factory = PGPObjectFactory(decoderStream, BcKeyFingerprintCalculator())

            val obj: Any = factory.nextObject()
                ?: throw IllegalArgumentException("File is empty or not a valid PGP file")
            Log.d(TAG, "Outer PGP object: ${obj::class.simpleName}")

            val innerFactory: PGPObjectFactory = if (obj is PGPEncryptedDataList) {
                val pbeData = obj.encryptedDataObjects.asSequence()
                    .filterIsInstance<PGPPBEEncryptedData>()
                    .firstOrNull()
                    ?: throw IllegalArgumentException(
                        "File is encrypted but not with a passphrase. " +
                        "Re-export from OpenKeychain using symmetric (passphrase) encryption."
                    )

                Log.d(TAG, "Decrypting PGPPBEEncryptedData (isIntegrityProtected=${pbeData.isIntegrityProtected})…")

                val decryptorFactory = BcPBEDataDecryptorFactory(
                    passphrase, BcPGPDigestCalculatorProvider()
                )

                val clearBytes = pbeData.getDataStream(decryptorFactory).readBytes()
                Log.d(TAG, "Decrypted ${clearBytes.size} B")

                if (pbeData.isIntegrityProtected) {
                    if (!pbeData.verify()) {
                        throw PGPException(
                            "Integrity check failed — passphrase may be wrong or the file is corrupt"
                        )
                    }
                    Log.d(TAG, "MDC integrity check passed")
                } else {
                    Log.w(TAG, "File has no MDC — integrity cannot be verified")
                }

                PGPObjectFactory(ByteArrayInputStream(clearBytes), BcKeyFingerprintCalculator())

            } else {
                Log.w(TAG, "File is not symmetrically encrypted — attempting direct parse")
                PGPObjectFactory(
                    PGPUtil.getDecoderStream(ByteArrayInputStream(bytes)),
                    BcKeyFingerprintCalculator()
                )
            }

            return findSecretKeyRing(innerFactory)
                ?: throw IllegalArgumentException(
                    "No secret key ring found in file. " +
                    "Ensure the file contains a secret key exported from OpenKeychain."
                )

        } finally {
            passphrase.fill('\u0000')
            Log.d(TAG, "Passphrase zeroed in finally")
        }
    }

    fun toArmoredString(ring: PGPSecretKeyRing): String {
        val baos = ByteArrayOutputStream()
        ArmoredOutputStream.builder()
            .setVersion(null)
            .build(baos).use { aos ->
                ring.encode(aos)
            }
        return baos.toString(Charsets.UTF_8.name())
    }

    private fun findSecretKeyRing(factory: PGPObjectFactory): PGPSecretKeyRing? {
        var obj = factory.nextObject()
        while (obj != null) {
            Log.d(TAG, "findSecretKeyRing: ${obj::class.simpleName}")
            when (obj) {
                is PGPSecretKeyRing -> {
                    val n = obj.secretKeys.asSequence().count()
                    Log.i(TAG, "PGPSecretKeyRing found — $n key packet(s)")
                    return obj
                }
                is PGPPublicKeyRing ->
                    Log.d(TAG, "Skipping PGPPublicKeyRing (public+private export format)")

                is PGPCompressedData -> {
                    Log.d(TAG, "Decompressing (algorithm=${obj.algorithm})…")
                    val inner = PGPObjectFactory(obj.dataStream, BcKeyFingerprintCalculator())
                    findSecretKeyRing(inner)?.let { return it }
                }

                is PGPLiteralData -> {
                    Log.d(TAG, "Unwrapping PGPLiteralData (filename='${obj.fileName}')…")
                    val literalBytes = obj.inputStream.readBytes()
                    Log.d(TAG, "LiteralData content (${literalBytes.size} B):\n" +
                               String(literalBytes, Charsets.UTF_8).take(800))

                    for (block in splitArmoredBlocks(literalBytes)) {
                        val inner = PGPObjectFactory(
                            PGPUtil.getDecoderStream(ByteArrayInputStream(block)),
                            BcKeyFingerprintCalculator()
                        )
                        findSecretKeyRing(inner)?.let { return it }
                    }
                }

                else ->
                    Log.w(TAG, "Unexpected PGP object: ${obj::class.simpleName} — skipping")
            }
            obj = factory.nextObject()
        }
        return null
    }

    private fun splitArmoredBlocks(bytes: ByteArray): List<ByteArray> {
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return listOf(bytes)
        }

        if (!text.contains("-----BEGIN PGP")) {
            return listOf(bytes)
        }

        val blocks = mutableListOf<ByteArray>()
        var cursor = 0
        while (cursor < text.length) {
            val beginIdx = text.indexOf("-----BEGIN PGP", cursor)
            if (beginIdx == -1) break

            val endTagIdx = text.indexOf("-----END PGP", beginIdx)
            if (endTagIdx == -1) break

            var endIdx = text.indexOf("-----", endTagIdx + 12)
            if (endIdx == -1) break
            endIdx += 5
            if (endIdx < text.length && text[endIdx] == '\r') endIdx++
            if (endIdx < text.length && text[endIdx] == '\n') endIdx++

            blocks.add(text.substring(beginIdx, endIdx).toByteArray(Charsets.UTF_8))
            Log.d(TAG, "splitArmoredBlocks: extracted block [${blocks.size}] " +
                       "(chars ${beginIdx}–${endIdx})")
            cursor = endIdx
        }

        return if (blocks.isEmpty()) listOf(bytes) else blocks
    }
}
