package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.core.keys.EllipticCurveValues

internal open class CurveInfo(
    val label: String,
    private val oidBytes: ByteArray,
    val sdkCurve: EllipticCurveValues? = null,
) {
    val oid: ByteArray get() = oidBytes.copyOf()

    val dottedOid: String = oidToDotted(oidBytes) ?: error("$label has a malformed OID")
}

internal class WeierstrassCurve(
    label: String,
    oid: ByteArray,
    sdkCurve: EllipticCurveValues,
    val bcName: String,
) : CurveInfo(label, oid, sdkCurve)

internal object CurveRegistry {

    val ED25519 = CurveInfo(
        "Ed25519",
        oidOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0xDA, 0x47, 0x0F, 0x01),
        EllipticCurveValues.Ed25519,
    )
    val X25519 = CurveInfo(
        "X25519",
        oidOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0x97, 0x55, 0x01, 0x05, 0x01),
        EllipticCurveValues.X25519,
    )

    val all: List<CurveInfo> = listOf(
        WeierstrassCurve(
            "NIST P-256",
            oidOf(0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07),
            EllipticCurveValues.SECP256R1,
            "secp256r1",
        ),
        WeierstrassCurve(
            "NIST P-384",
            oidOf(0x2B, 0x81, 0x04, 0x00, 0x22),
            EllipticCurveValues.SECP384R1,
            "secp384r1",
        ),
        WeierstrassCurve(
            "NIST P-521",
            oidOf(0x2B, 0x81, 0x04, 0x00, 0x23),
            EllipticCurveValues.SECP521R1,
            "secp521r1",
        ),
        WeierstrassCurve(
            "brainpoolP256r1",
            oidOf(0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x07),
            EllipticCurveValues.BrainpoolP256R1,
            "brainpoolP256r1",
        ),
        WeierstrassCurve(
            "brainpoolP384r1",
            oidOf(0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x0B),
            EllipticCurveValues.BrainpoolP384R1,
            "brainpoolP384r1",
        ),
        WeierstrassCurve(
            "brainpoolP512r1",
            oidOf(0x2B, 0x24, 0x03, 0x03, 0x02, 0x08, 0x01, 0x01, 0x0D),
            EllipticCurveValues.BrainpoolP512R1,
            "brainpoolP512r1",
        ),
        WeierstrassCurve(
            "secp256k1",
            oidOf(0x2B, 0x81, 0x04, 0x00, 0x0A),
            EllipticCurveValues.SECP256K1,
            "secp256k1",
        ),
        ED25519,
        X25519,
        // Not curves the app can write, but ones a card may list among its supported algorithms.
        CurveInfo("Ed25519 (RFC 8410 OID)", oidOf(0x2B, 0x65, 0x70)),
        CurveInfo("X25519 (RFC 8410 OID)", oidOf(0x2B, 0x65, 0x6E)),
        CurveInfo("Ed448", oidOf(0x2B, 0x65, 0x71)),
        CurveInfo("X448", oidOf(0x2B, 0x65, 0x6F)),
    )

    val writable: List<CurveInfo> = all.filter { it.sdkCurve != null }

    private val byDottedOid: Map<String, CurveInfo> = all.associateBy { it.dottedOid }

    private val bySdkCurve: Map<EllipticCurveValues, CurveInfo> =
        all.mapNotNull { curve -> curve.sdkCurve?.let { it to curve } }.toMap()

    fun forOid(oid: ByteArray): CurveInfo? = oidToDotted(oid)?.let { byDottedOid[it] }

    fun forDottedOid(oid: String): CurveInfo? = byDottedOid[oid]

    fun forSdkCurve(curve: EllipticCurveValues): CurveInfo? = bySdkCurve[curve]

    fun weierstrass(curve: EllipticCurveValues): WeierstrassCurve? = bySdkCurve[curve] as? WeierstrassCurve
}

internal fun oidToDotted(oid: ByteArray): String? {
    if (oid.isEmpty() || (oid.last().toInt() and 0x80) != 0) return null
    val arcs = mutableListOf<Long>()
    var arc = 0L
    for (byte in oid) {
        val value = byte.toInt() and 0xFF
        arc = (arc shl 7) or (value and 0x7F).toLong()
        if (arc > 0xFFFFFFFFL) return null
        if ((value and 0x80) == 0) {
            arcs += arc
            arc = 0
        }
    }
    val first = arcs[0]
    val head = when {
        first < 40 -> listOf(0L, first)
        first < 80 -> listOf(1L, first - 40)
        else -> listOf(2L, first - 80)
    }
    return (head + arcs.drop(1)).joinToString(".")
}

private fun oidOf(vararg octets: Int) = ByteArray(octets.size) { octets[it].toByte() }
