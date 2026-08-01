package acab.naiveha.subrosa.ui.openpgp

import android.util.Log
import com.yubico.yubikit.core.keys.EllipticCurveValues
import com.yubico.yubikit.core.keys.PrivateKeyValues
import com.yubico.yubikit.core.util.ByteUtils
import com.yubico.yubikit.core.util.Tlv
import com.yubico.yubikit.openpgp.KeyRef
import com.yubico.yubikit.openpgp.OpenPgpSession
import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
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
    private val OID_SECP521R1 = byteArrayOf(0x2B, 0x81.toByte(), 0x04, 0x00, 0x23)

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
    ): String? =
        OpenPgpWriterUtils.programCommon(
            session, bundle, adminPin, userPin, tag, status,
            clearSlotBeforeWrite = clearSlotBeforeWrite,
        ) { s, slot, t, st ->
            val attrBytes = buildAlgorithmAttributes(
                slot.privateKeyValues,
                slot.ref,
                slot.declaredModulusBitLength,
                t,
            )
            Log.d(t, "  putData(algorithmAttributes, ${attrBytes.size} bytes)…")
            s.putData(slot.ref.algorithmAttributes, attrBytes)
            Log.d(t, "  algorithm attributes written")
            st("Algorithm attributes set for ${slot.ref.name}")

            val template = buildKeyTemplate(slot.ref, slot.privateKeyValues, omitEcPublicKeyForNistCurves)
            try {
                Log.d(t, "  putRawKeyTemplate(${slot.ref.name}, ${template.size} bytes)…")
                s.putRawKeyTemplate(template)
                Log.d(t, "  putRawKeyTemplate(${slot.ref.name}) done")
                st("Key material written for ${slot.ref.name}")
            } finally {
                template.fill(0)
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

    private fun buildAlgorithmAttributes(
        values: PrivateKeyValues,
        ref: KeyRef,
        declaredBitLength: Int?,
        tag: String,
    ): ByteArray =
        when (values) {
            is PrivateKeyValues.Rsa -> {
                val nLenBits = declaredBitLength ?: run {
                    Log.w(tag, "declaredBitLength unavailable for slot ${ref.name}, " +
                               "falling back to BigInteger.bitLength()=${values.bitLength}")
                    values.bitLength
                }
                val eLenBits = RSA_PUBLIC_EXPONENT_LEN_BITS
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
                EllipticCurveValues.SECP256R1 ->
                    byteArrayOf(ecdsaOrEcdh(ref).toByte()) + OID_SECP256R1
                EllipticCurveValues.SECP521R1 ->
                    byteArrayOf(ecdsaOrEcdh(ref).toByte()) + OID_SECP521R1
                else -> throw UnsupportedOperationException(
                    "Manual PGP import only supports Ed25519/X25519/NIST P-256/NIST P-521, " +
                        "got ${values.curveParams}"
                )
            }
            else -> throw UnsupportedOperationException(
                "Unsupported private key type for manual PGP import: ${values::class.simpleName}"
            )
        }

    private fun shortBe(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun ecdsaOrEcdh(ref: KeyRef): Int =
        if (ref == KeyRef.DEC) PGP_ALGO_ECDH else PGP_ALGO_ECDSA

    private fun buildKeyTemplate(
        ref: KeyRef,
        values: PrivateKeyValues,
        omitEcPublicKeyForNistCurves: Boolean,
    ): ByteArray =
        when (values) {
            is PrivateKeyValues.Rsa -> buildRsaKeyTemplate(ref, values)
            is PrivateKeyValues.Ec -> buildEcKeyTemplate(ref, values, omitEcPublicKeyForNistCurves)
            else -> throw UnsupportedOperationException(
                "Unsupported private key type for manual PGP import: ${values::class.simpleName}"
            )
        }

    private fun buildRsaKeyTemplate(ref: KeyRef, rsa: PrivateKeyValues.Rsa): ByteArray {
        val byteLength = rsa.bitLength / 8 / 2
        // e/p/q are freshly derived here (not aliases of caller-owned data), so it's safe to
        // wipe them locally once they've been copied into headerBytes/valueBytes below.
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

    private fun buildEcKeyTemplate(
        ref: KeyRef,
        ec: PrivateKeyValues.Ec,
        omitEcPublicKeyForNistCurves: Boolean,
    ): ByteArray {
        return when (ec.curveParams) {
            EllipticCurveValues.Ed25519 -> {
                // `secret` aliases ec.secret (owned by ImportBundle, wiped by its destroy()) —
                // not wiped here. publicKeyBytes is a fresh local, safe to wipe once consumed.
                val secret = require32ByteSecret(ec.secret)
                val publicKeyBytes = byteArrayOf(EC_PUBLIC_KEY_HEADER) +
                    Ed25519PrivateKeyParameters(secret).generatePublicKey().encoded
                try {
                    buildPrivateAndPublicTemplate(ref, secret, publicKeyBytes)
                } finally {
                    publicKeyBytes.fill(0)
                }
            }
            EllipticCurveValues.X25519 -> {
                val secret = require32ByteSecret(ec.secret)
                val reversedSecret = secret.reversedArray()
                val publicKeyBytes = try {
                    byteArrayOf(EC_PUBLIC_KEY_HEADER) +
                        X25519PrivateKeyParameters(reversedSecret).generatePublicKey().encoded
                } finally {
                    reversedSecret.fill(0)
                }
                try {
                    buildPrivateAndPublicTemplate(ref, secret, publicKeyBytes)
                } finally {
                    publicKeyBytes.fill(0)
                }
            }
            EllipticCurveValues.SECP256R1 ->
                buildNistEcTemplate(ref, ec.secret, "secp256r1", omitEcPublicKeyForNistCurves)
            EllipticCurveValues.SECP521R1 ->
                buildNistEcTemplate(ref, ec.secret, "secp521r1", omitEcPublicKeyForNistCurves)
            else -> throw UnsupportedOperationException(
                "Manual PGP import only supports Ed25519/X25519/NIST P-256/NIST P-521, " +
                    "got ${ec.curveParams}"
            )
        }
    }

    /**
     * NIST curves (P-256/P-521): most OpenPGP-card implementations (genuine YubiKey, Gnuk) can
     * derive Q from the private scalar and accept a private-key-only Extended Header List. The
     * Nitrokey 3's OpenPGP applet does not — it rejects a private-only template for these curves
     * with SW=6A80, so the public point has to be computed and included explicitly, the same way
     * the Ed25519/X25519 branches above already do. Gnuk (Librem Key) is the opposite: an earlier
     * fix (confirmed against a real scdaemon capture) found it rejects the write if a public key
     * is present at all, so callers that target Gnuk pass omitEcPublicKeyForNistCurves=true.
     */
    private fun buildNistEcTemplate(
        ref: KeyRef,
        secret: ByteArray,
        bcCurveName: String,
        omitEcPublicKeyForNistCurves: Boolean,
    ): ByteArray {
        // Unlike the Ed25519/X25519 case, `scalar` is a fresh derivation (BigInteger → bytes),
        // not an alias of caller-owned data — safe to wipe once it's been consumed below.
        val scalar = nistScalar(secret, bcCurveName)
        try {
            return if (omitEcPublicKeyForNistCurves) {
                buildPrivateOnlyTemplate(ref, scalar)
            } else {
                val publicKeyBytes = nistPublicKeyBytes(scalar, bcCurveName)
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

    private fun nistScalar(secret: ByteArray, bcCurveName: String): ByteArray {
        val curve = SECNamedCurves.getByName(bcCurveName)
        val scalarLength = (curve.curve.fieldSize + 7) / 8
        val d = BigInteger(1, secret)
        return ByteUtils.intToLength(d, scalarLength)
    }

    /** Computes the uncompressed public point Q = d*G (0x04 || X || Y) for a NIST curve. */
    private fun nistPublicKeyBytes(scalar: ByteArray, bcCurveName: String): ByteArray {
        val curve = SECNamedCurves.getByName(bcCurveName)
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
