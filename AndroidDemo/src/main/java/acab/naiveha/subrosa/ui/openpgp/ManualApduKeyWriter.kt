package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.core.keys.EllipticCurveValues
import com.yubico.yubikit.core.keys.PrivateKeyValues
import com.yubico.yubikit.core.util.ByteUtils
import com.yubico.yubikit.core.util.Tlv
import com.yubico.yubikit.openpgp.KeyRef
import com.yubico.yubikit.openpgp.OpenPgpSession
import java.math.BigInteger
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

internal object ManualApduKeyWriter {

    private const val PGP_ALGO_RSA = 1
    private const val PGP_ALGO_ECDH = 18
    private const val PGP_ALGO_ECDSA = 19
    private const val PGP_ALGO_EDDSA_LEGACY = 22

    private val OID_ED25519   = byteArrayOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0xDA.toByte(), 0x47, 0x0F, 0x01)
    private val OID_X25519    = byteArrayOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0x97.toByte(), 0x55, 0x01, 0x05, 0x01)
    private val OID_SECP256R1 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)
    private val OID_SECP384R1 = byteArrayOf(0x2B, 0x81.toByte(), 0x04, 0x00, 0x22)
    private val OID_SECP521R1 = byteArrayOf(0x2B, 0x81.toByte(), 0x04, 0x00, 0x23)
    private val OID_BRAINPOOLP256R1 = byteArrayOf(0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x07)
    private val OID_BRAINPOOLP384R1 = byteArrayOf(0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x0B)
    private val OID_BRAINPOOLP512R1 = byteArrayOf(0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x0D)
    private val OID_SECP256K1 = byteArrayOf(0x2B, 0x81.toByte(), 0x04, 0x00, 0x0A)

    private class WeierstrassCurve(val label: String, val oid: ByteArray, val bcName: String)

    private val WEIERSTRASS_CURVES: Map<EllipticCurveValues, WeierstrassCurve> = mapOf(
        EllipticCurveValues.SECP256R1 to WeierstrassCurve("NIST P-256", OID_SECP256R1, "secp256r1"),
        EllipticCurveValues.SECP384R1 to WeierstrassCurve("NIST P-384", OID_SECP384R1, "secp384r1"),
        EllipticCurveValues.SECP521R1 to WeierstrassCurve("NIST P-521", OID_SECP521R1, "secp521r1"),
        EllipticCurveValues.BrainpoolP256R1 to WeierstrassCurve("brainpoolP256r1", OID_BRAINPOOLP256R1, "brainpoolP256r1"),
        EllipticCurveValues.BrainpoolP384R1 to WeierstrassCurve("brainpoolP384r1", OID_BRAINPOOLP384R1, "brainpoolP384r1"),
        EllipticCurveValues.BrainpoolP512R1 to WeierstrassCurve("brainpoolP512r1", OID_BRAINPOOLP512R1, "brainpoolP512r1"),
        EllipticCurveValues.SECP256K1 to WeierstrassCurve("secp256k1", OID_SECP256K1, "secp256k1"),
        )

    private const val EC_PUBLIC_KEY_HEADER: Byte = 0x40

    private const val RSA_PUBLIC_EXPONENT_LEN_BITS = 32

    fun program(
        session: OpenPgpSession,
        bundle: ImportBundle,
        adminPin: CharArray,
        userPin: CharArray,
        tag: String,
        status: (String) -> Unit,
        clearSlotBeforeWrite: Boolean,
        omitEcPublicKeyForNistCurves: Boolean = false,
    ): String? {
        return OpenPgpWriterUtils.programCommon(
            session, bundle, adminPin, userPin, tag, status,
            clearSlotBeforeWrite = clearSlotBeforeWrite,
        ) { s, slot, t, st ->
            val attrBytes = buildAlgorithmAttributes(slot)
            Log.d(t, "  putData(algorithmAttributes, ${attrBytes.size} bytes)…")
            s.putData(slot.ref.algorithmAttributes, attrBytes)
            Log.d(t, "  algorithm attributes written")
            st("Algorithm attributes set for ${slot.ref.name}")

            val template = buildKeyTemplate(slot, omitEcPublicKeyForNistCurves)
            try {
                Log.d(t, "  putRawKeyTemplate(${slot.ref.name}, ${template.size} bytes)…")
                s.putRawKeyTemplate(template)
                Log.d(t, "  putRawKeyTemplate(${slot.ref.name}) done")
                st("Key material written for ${slot.ref.name}")
            } finally {
                template.fill(0)
            }
        }
    }

    fun wipe(session: OpenPgpSession, tag: String, status: (String) -> Unit): String? {
        Log.i(tag, "wipe() — calling forceWipe()")
        var succeeded = false
        try {
            OpenPgpWriterUtils.forceWipe(session, tag, status)
            Log.i(tag, "wipe() complete")
            status(OpenPgpWriter.WIPE_COMPLETE_STATUS)
            succeeded = true
            return null
        } finally {
            if (!succeeded) status("")
        }
    }

    fun validate(
        session: OpenPgpSession,
        bundle: ImportBundle,
        tag: String,
        omitEcPublicKeyForNistCurves: Boolean = false,
    ): List<String> =
        OpenPgpWriterUtils.validate(session, bundle, tag) +
            bundle.slots.mapNotNull { encodingProblem(it, omitEcPublicKeyForNistCurves) }

    fun encodingProblem(slot: SlotData, omitEcPublicKeyForNistCurves: Boolean = false): String? =
        try {
            buildAlgorithmAttributes(slot)
            buildKeyTemplate(slot, omitEcPublicKeyForNistCurves).fill(0)
            null
        } catch (e: IllegalArgumentException) {
            "${slot.ref.name}: ${e.message}"
        }

     fun curveLabel(oid: ByteArray): String? = when {
        oid.contentEquals(OID_ED25519) -> "Ed25519"
        oid.contentEquals(OID_X25519) -> "X25519"
        else -> WEIERSTRASS_CURVES.values.firstOrNull { it.oid.contentEquals(oid) }?.label
    }

    private fun unwritable(key: PrivateKeyValues): IllegalArgumentException {
        val curves = WEIERSTRASS_CURVES.values.joinToString(", ") { it.label }
        return IllegalArgumentException(
            "${CardAlgorithmInfo.describe(key)} cannot be written to this device by this app " +
                "(it can write RSA, $curves, Ed25519 and X25519 keys)"
        )
    }

    private fun buildAlgorithmAttributes(slot: SlotData): ByteArray {
        val ref = slot.ref
        return when (val key = slot.privateKeyValues) {
            is PrivateKeyValues.Rsa ->
                byteArrayOf(PGP_ALGO_RSA.toByte()) +
                    shortBe(key.bitLength) +
                    shortBe(RSA_PUBLIC_EXPONENT_LEN_BITS) +
                    byteArrayOf(0x00)
            is PrivateKeyValues.Ec -> when (key.curveParams) {
                EllipticCurveValues.Ed25519 -> byteArrayOf(PGP_ALGO_EDDSA_LEGACY.toByte()) + OID_ED25519
                EllipticCurveValues.X25519 -> byteArrayOf(PGP_ALGO_ECDH.toByte()) + OID_X25519
                else -> {
                    val curve = WEIERSTRASS_CURVES[key.curveParams] ?: throw unwritable(key)
                    byteArrayOf(ecdsaOrEcdh(ref).toByte()) + curve.oid
                }
            }
            else -> throw unwritable(key)
        }
    }

    private fun shortBe(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun ecdsaOrEcdh(ref: KeyRef): Int =
        if (ref == KeyRef.DEC) PGP_ALGO_ECDH else PGP_ALGO_ECDSA

    private fun buildKeyTemplate(slot: SlotData, omitEcPublicKeyForNistCurves: Boolean): ByteArray {
        val ref = slot.ref
        return when (val key = slot.privateKeyValues) {
            is PrivateKeyValues.Rsa -> buildRsaKeyTemplate(ref, key)
            is PrivateKeyValues.Ec -> when (key.curveParams) {
                EllipticCurveValues.Ed25519 -> buildEd25519Template(ref, key.secret)
                EllipticCurveValues.X25519 -> buildX25519Template(ref, key.secret)
                else -> {
                    val curve = WEIERSTRASS_CURVES[key.curveParams] ?: throw unwritable(key)
                    buildWeierstrassEcTemplate(ref, key.secret, curve.bcName, omitEcPublicKeyForNistCurves)
                }
            }
            else -> throw unwritable(key)
        }
    }

    private fun buildRsaKeyTemplate(ref: KeyRef, rsa: PrivateKeyValues.Rsa): ByteArray {
        val byteLength = rsa.bitLength / 8 / 2
        require(rsa.publicExponent.bitLength() <= RSA_PUBLIC_EXPONENT_LEN_BITS) {
            "the public exponent has ${rsa.publicExponent.bitLength()} bits; " +
                "the card's RSA key format allows at most $RSA_PUBLIC_EXPONENT_LEN_BITS"
        }
        require(rsa.primeP.bitLength() <= byteLength * 8 && rsa.primeQ.bitLength() <= byteLength * 8) {
            "the prime factors do not fit the card's RSA key format " +
                "(at most ${byteLength * 8} bits each for a ${rsa.bitLength}-bit key)"
        }
        val eBytes = ByteUtils.intToLength(rsa.publicExponent, RSA_PUBLIC_EXPONENT_LEN_BITS / 8)
        val pBytes = ByteUtils.intToLength(rsa.primeP, byteLength)
        val qBytes = ByteUtils.intToLength(rsa.primeQ, byteLength)
        try {
            val headerBytes = tlvHeaderBytes(0x91, eBytes) +
                tlvHeaderBytes(0x92, pBytes) +
                tlvHeaderBytes(0x93, qBytes)
            val valueBytes = eBytes + pBytes + qBytes
            try {
                return wrapExtendedHeaderList(ref, headerBytes, valueBytes)
            } finally {
                headerBytes.fill(0)
                valueBytes.fill(0)
            }
        } finally {
            eBytes.fill(0)
            pBytes.fill(0)
            qBytes.fill(0)
        }
    }

    private fun buildEd25519Template(ref: KeyRef, rawSecret: ByteArray): ByteArray {
        val secret = require32ByteSecret(rawSecret)
        val publicKeyBytes = byteArrayOf(EC_PUBLIC_KEY_HEADER) +
            Ed25519PrivateKeyParameters(secret).generatePublicKey().encoded
        try {
            return buildPrivateAndPublicTemplate(ref, secret, publicKeyBytes)
        } finally {
            publicKeyBytes.fill(0)
        }
    }

    private fun buildX25519Template(ref: KeyRef, rawSecret: ByteArray): ByteArray {
        val secret = require32ByteSecret(rawSecret)
        val reversedSecret = secret.reversedArray()
        val publicKeyBytes = try {
            byteArrayOf(EC_PUBLIC_KEY_HEADER) +
                X25519PrivateKeyParameters(reversedSecret).generatePublicKey().encoded
        } finally {
            reversedSecret.fill(0)
        }
        try {
            return buildPrivateAndPublicTemplate(ref, secret, publicKeyBytes)
        } finally {
            publicKeyBytes.fill(0)
        }
    }

    private fun buildWeierstrassEcTemplate(
        ref: KeyRef,
        secret: ByteArray,
        bcCurveName: String,
        omitEcPublicKeyForNistCurves: Boolean,
    ): ByteArray {
        val scalar = weierstrassScalar(secret, bcCurveName)
        try {
            return if (omitEcPublicKeyForNistCurves) {
                buildPrivateOnlyTemplate(ref, scalar)
            } else {
                val publicKeyBytes = weierstrassPublicKeyBytes(scalar, bcCurveName)
                try {
                    buildPrivateAndPublicTemplate(ref, scalar, publicKeyBytes)
                } finally {
                    publicKeyBytes.fill(0)
                }
            }
        } finally {
            scalar.fill(0)
        }
    }

    private fun buildPrivateAndPublicTemplate(
        ref: KeyRef,
        secretScalar: ByteArray,
        publicKeyBytes: ByteArray
    ): ByteArray {
        val headerBytes = tlvHeaderBytes(0x92, secretScalar) + tlvHeaderBytes(0x99, publicKeyBytes)
        val valueBytes = secretScalar + publicKeyBytes
        try {
            return wrapExtendedHeaderList(ref, headerBytes, valueBytes)
        } finally {
            headerBytes.fill(0)
            valueBytes.fill(0)
        }
    }

    private fun buildPrivateOnlyTemplate(ref: KeyRef, secretScalar: ByteArray): ByteArray {
        val headerBytes = tlvHeaderBytes(0x92, secretScalar)
        try {
            return wrapExtendedHeaderList(ref, headerBytes, secretScalar)
        } finally {
            headerBytes.fill(0)
        }
    }

    private fun require32ByteSecret(secret: ByteArray): ByteArray {
        require(secret.size == 32) {
            "Expected a 32-byte EC secret scalar, got ${secret.size} bytes"
        }
        return secret
    }

    private fun domainParameters(bcCurveName: String): X9ECParameters =
        requireNotNull(ECNamedCurveTable.getByName(bcCurveName)) {
            "no domain parameters available for curve $bcCurveName"
        }

    private fun weierstrassScalar(secret: ByteArray, bcCurveName: String): ByteArray {
        val curve = domainParameters(bcCurveName)
        val scalarLength = (curve.curve.fieldSize + 7) / 8
        val d = BigInteger(1, secret)
        return ByteUtils.intToLength(d, scalarLength)
    }

    private fun weierstrassPublicKeyBytes(scalar: ByteArray, bcCurveName: String): ByteArray {
        val curve = domainParameters(bcCurveName)
        val d = BigInteger(1, scalar)
        return curve.g.multiply(d).normalize().getEncoded(false)
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
        try {
            return Tlv(0x4D, body).bytes
        } finally {
            tmpl7f48.fill(0)
            data5f48.fill(0)
            body.fill(0)
        }
    }
}
