package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.core.keys.EllipticCurveValues
import com.yubico.yubikit.core.keys.PrivateKeyValues
import com.yubico.yubikit.core.util.ByteUtils
import com.yubico.yubikit.core.util.Tlv
import com.yubico.yubikit.openpgp.Do
import com.yubico.yubikit.openpgp.KeyRef
import com.yubico.yubikit.openpgp.OpenPgpSession
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

object NitrokeyPgpWriter : OpenPgpWriter {

    private const val TAG = "NitrokeyPgpWriter"

    private const val PGP_ALGO_RSA = 1
    private const val PGP_ALGO_ECDH = 18
    private const val PGP_ALGO_EDDSA_LEGACY = 22

    private val OID_ED25519 = byteArrayOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0xDA.toByte(), 0x47, 0x0F, 0x01)
    private val OID_X25519  = byteArrayOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0x97.toByte(), 0x55, 0x01, 0x05, 0x01)

    private const val EC_PUBLIC_KEY_HEADER: Byte = 0x40

    private const val NITROKEY_RSA_E_LEN_BITS = 32

    override fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        status: (String) -> Unit,
    ): String? {
        Log.i(TAG, "program() — slots: ${bundle.slots.map { it.ref.name }} " +
                   "hasName=${bundle.nameBytes.isNotEmpty()} " +
                   "hasLogin=${bundle.loginBytes.isNotEmpty()} " +
                   "adminPinLength=${adminPin.size}")
        var succeeded = false
        try {
            Log.d(TAG, "Calling verifyAdminPin() (PIN length=${adminPin.size})…")
            status("Verifying admin PIN…")
            session.verifyAdminPin(adminPin)
            Log.i(TAG, "verifyAdminPin() succeeded")
            status("Admin PIN verified")

            OpenPgpWriterUtils.factoryResetAndRestorePin(session, adminPin, TAG, status)

            if (bundle.nameBytes.isNotEmpty()) {
                Log.d(TAG, "Writing DO.NAME (${bundle.nameBytes.size} bytes)…")
                session.putData(Do.NAME, bundle.nameBytes)
                Log.d(TAG, "DO.NAME written")
                status("NAME set")
            }
            if (bundle.loginBytes.isNotEmpty()) {
                Log.d(TAG, "Writing DO.LOGIN_DATA (${bundle.loginBytes.size} bytes)…")
                session.putData(Do.LOGIN_DATA, bundle.loginBytes)
                Log.d(TAG, "DO.LOGIN_DATA written")
                status("LOGIN_DATA set")
            }

            for (slot in bundle.slots) {
                Log.d(TAG, "Writing slot ${slot.ref.name} — " +
                           "fingerprintLength=${slot.fingerprint.size} " +
                           "creationTimestamp=${slot.creationTimestamp}")
                status("Writing slot ${slot.ref.name}…")

                Log.d(TAG, "  clearSlot(${slot.ref.name})…")
                OpenPgpWriterUtils.clearSlot(session, slot.ref, TAG)
                status("Slot ${slot.ref.name} cleared")

                val attrBytes = buildAlgorithmAttributes(
                    slot.privateKeyValues,
                    slot.ref,
                    slot.declaredModulusBitLength,
                )
                Log.d(TAG, "  putData(algorithmAttributes, ${attrBytes.size} bytes)…")
                session.putData(slot.ref.algorithmAttributes, attrBytes)
                Log.d(TAG, "  algorithm attributes written")
                status("Algorithm attributes set for ${slot.ref.name}")

                val template = buildKeyTemplate(slot.ref, slot.privateKeyValues)
                Log.d(TAG, "  putRawKeyTemplate(${slot.ref.name}, ${template.size} bytes)…")
                session.putRawKeyTemplate(template)
                Log.d(TAG, "  putRawKeyTemplate(${slot.ref.name}) done")
                status("Key material written for ${slot.ref.name}")

                Log.d(TAG, "  setFingerprint(${slot.ref.name})…")
                session.setFingerprint(slot.ref, slot.fingerprint)
                Log.d(TAG, "  setFingerprint(${slot.ref.name}) done")
                status("Fingerprint set for ${slot.ref.name}")

                Log.d(TAG, "  setGenerationTime(${slot.ref.name}, ${slot.creationTimestamp})…")
                session.setGenerationTime(slot.ref, slot.creationTimestamp)
                Log.d(TAG, "  setGenerationTime(${slot.ref.name}) done")
                status("Generation time set for ${slot.ref.name}")

                Log.i(TAG, "Slot ${slot.ref.name} programmed ✓")
            }

            val slotNames = bundle.slots.joinToString(", ") { it.ref.name }
            Log.i(TAG, "program() complete — $slotNames")
            status(OpenPgpWriter.WRITE_COMPLETE_STATUS)
            succeeded = true
            return null

        } catch (e: Exception) {
            Log.e(TAG, "program() FAILED: ${e::class.simpleName}: ${e.message}", e)
            throw e
        } finally {
            adminPin.fill('\u0000')
            bundle.destroy()
            Log.d(TAG, "adminPin zeroed and bundle destroyed")
            if (!succeeded) status("")
        }
    }

    override fun wipe(session: OpenPgpSession, status: (String) -> Unit): String? {
        Log.i(TAG, "wipe() — calling forceWipe()")
        var succeeded = false
        try {
            OpenPgpWriterUtils.forceWipe(session, TAG, status)
            Log.i(TAG, "wipe() complete")
            status(OpenPgpWriter.WIPE_COMPLETE_STATUS)
            succeeded = true
            return null
        } finally {
            if (!succeeded) status("")
        }
    }

    private fun buildAlgorithmAttributes(
        values: PrivateKeyValues,
        ref: KeyRef,
        declaredBitLength: Int?,
    ): ByteArray =
        when (values) {
            is PrivateKeyValues.Rsa -> {
                val nLenBits = declaredBitLength ?: run {
                    Log.w(TAG, "declaredBitLength unavailable for slot ${ref.name}, " +
                               "falling back to BigInteger.bitLength()=${values.bitLength}")
                    values.bitLength
                }
                val eLenBits = NITROKEY_RSA_E_LEN_BITS
                byteArrayOf(PGP_ALGO_RSA.toByte()) +
                    shortBe(nLenBits) +
                    shortBe(eLenBits) +
                    byteArrayOf(0x00)
            }
            is PrivateKeyValues.Ec -> when (values.curveParams) {
                EllipticCurveValues.Ed25519 ->
                    byteArrayOf(PGP_ALGO_EDDSA_LEGACY.toByte()) + OID_ED25519
                EllipticCurveValues.X25519 ->
                    byteArrayOf(PGP_ALGO_ECDH.toByte()) + OID_X25519
                else -> throw UnsupportedOperationException(
                    "Nitrokey import only supports Ed25519/X25519, got ${values.curveParams}"
                )
            }
            else -> throw UnsupportedOperationException(
                "Unsupported private key type for Nitrokey import: ${values::class.simpleName}"
            )
        }

    private fun shortBe(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun buildKeyTemplate(ref: KeyRef, values: PrivateKeyValues): ByteArray =
        when (values) {
            is PrivateKeyValues.Rsa -> buildRsaKeyTemplate(ref, values)
            is PrivateKeyValues.Ec -> buildEcKeyTemplate(ref, values)
            else -> throw UnsupportedOperationException(
                "Unsupported private key type for Nitrokey import: ${values::class.simpleName}"
            )
        }

    private fun buildRsaKeyTemplate(ref: KeyRef, rsa: PrivateKeyValues.Rsa): ByteArray {
        val byteLength = rsa.bitLength / 8 / 2
        val eBytes = rsa.publicExponent.toByteArray()
        val pBytes = ByteUtils.intToLength(rsa.primeP, byteLength)
        val qBytes = ByteUtils.intToLength(rsa.primeQ, byteLength)

        val headerBytes = tlvHeaderBytes(0x91, eBytes) +
            tlvHeaderBytes(0x92, pBytes) +
            tlvHeaderBytes(0x93, qBytes)
        val valueBytes = eBytes + pBytes + qBytes

        return wrapExtendedHeaderList(ref, headerBytes, valueBytes)
    }

    private fun buildEcKeyTemplate(ref: KeyRef, ec: PrivateKeyValues.Ec): ByteArray {
        val secretScalar = ec.secret
        require(secretScalar.size == 32) {
            "Expected a 32-byte EC secret scalar, got ${secretScalar.size} bytes"
        }

        val rawPublicKey = when (ec.curveParams) {
            EllipticCurveValues.Ed25519 ->
                Ed25519PrivateKeyParameters(secretScalar).generatePublicKey().encoded
            EllipticCurveValues.X25519 ->
                X25519PrivateKeyParameters(secretScalar.reversedArray()).generatePublicKey().encoded
            else -> throw UnsupportedOperationException(
                "Nitrokey import only supports Ed25519/X25519, got ${ec.curveParams}"
            )
        }
        val publicKeyWithHeader = byteArrayOf(EC_PUBLIC_KEY_HEADER) + rawPublicKey

        val headerBytes = tlvHeaderBytes(0x92, secretScalar) +
            tlvHeaderBytes(0x99, publicKeyWithHeader)
        val valueBytes = secretScalar + publicKeyWithHeader

        return wrapExtendedHeaderList(ref, headerBytes, valueBytes)
    }

    private fun tlvHeaderBytes(tag: Int, value: ByteArray): ByteArray {
        val tlv = Tlv(tag, value)
        val full = tlv.bytes
        return full.copyOfRange(0, full.size - tlv.length)
    }

    private fun wrapExtendedHeaderList(ref: KeyRef, headerBytes: ByteArray, valueBytes: ByteArray): ByteArray {
        val crt = ref.crt // "<CRT tag> 00", 2 bytes — same as Crt.SIG/DEC/AUT in yubikit
        val tmpl7f48 = Tlv(0x7F48, headerBytes).bytes
        val data5f48 = Tlv(0x5F48, valueBytes).bytes
        val body = crt + tmpl7f48 + data5f48
        return Tlv(0x4D, body).bytes
    }
}
