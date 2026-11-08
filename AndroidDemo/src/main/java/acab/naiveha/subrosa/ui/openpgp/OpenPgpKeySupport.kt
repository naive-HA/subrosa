package acab.naiveha.subrosa.ui.openpgp

import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.BCPGInputStream
import org.bouncycastle.bcpg.PacketTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import java.io.ByteArrayInputStream
import java.util.Date

class UnsupportedKeyException(val problems: List<String>) : PgpImportRejectedException(
    "This key can't be imported\n" +
        "Only version 4 keys using RSA, ECDSA, ECDH or EdDSA (including the legacy Ed25519 and X25519 form) can be imported")

class ExpiredPrimaryKeyException(val expiredOn: Date) : PgpImportRejectedException(
    "This key can't be imported\n" +
        "Its primary key expired on ${expiredOn.toDisplayDate()}\n" +
        "Extend the expiry date, then export the key again")

class RevokedPrimaryKeyException : PgpImportRejectedException(
    "This key can't be imported\n" +
        "Its primary key has been revoked")

internal object OpenPgpKeySupport {
    private const val SUPPORTED_VERSION = 4

    private val KNOWN_ALGORITHMS = setOf(
        PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_ENCRYPT, PublicKeyAlgorithmTags.RSA_SIGN,
        PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT, PublicKeyAlgorithmTags.ELGAMAL_GENERAL, PublicKeyAlgorithmTags.DSA,
        PublicKeyAlgorithmTags.ECDH, PublicKeyAlgorithmTags.ECDSA, PublicKeyAlgorithmTags.EDDSA_LEGACY,
    )

    private val CARD_ALGORITHMS = setOf(
        PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_ENCRYPT, PublicKeyAlgorithmTags.RSA_SIGN,
        PublicKeyAlgorithmTags.ECDH, PublicKeyAlgorithmTags.ECDSA, PublicKeyAlgorithmTags.EDDSA_LEGACY,
    )

    private val EC_ALGORITHMS = setOf(
        PublicKeyAlgorithmTags.ECDH, PublicKeyAlgorithmTags.ECDSA, PublicKeyAlgorithmTags.EDDSA_LEGACY,
    )

    private class RawPacket(val tag: Int, val start: Int, val bodyStart: Int, val end: Int, val partial: Boolean)

    internal class RawKey(
        val ordinal: Int,
        val tag: Int,
        val version: Int,
        val algorithm: Int,
        val curve: String?,
        val decodeError: String?,
    ) {
        val isPrimary get() = ordinal == 0
        val isSecret get() = tag == PacketTags.SECRET_KEY || tag == PacketTags.SECRET_SUBKEY
    }

    internal class Scan(val keys: List<RawKey>, val damaged: Boolean)

    fun readSupportedRing(armor: String, asOf: Date): PGPSecretKeyRing? {
        val bytes = ArmoredInputStream(ByteArrayInputStream(armor.toByteArray(Charsets.UTF_8))).use { it.readBytes() }
        try {
            val scan = scan(bytes)
            requireSupported(scan)
            val ring = PGPObjectFactory(ByteArrayInputStream(bytes), BcKeyFingerprintCalculator()).nextObject() as? PGPSecretKeyRing
                ?: return null
            requireComplete(scan, ring)
            requirePrimaryUsable(ring, asOf)
            requireWritable(ring, asOf)
            return ring
        } finally {
            bytes.fill(0)
        }
    }

    fun requireDecodable(keyBytes: ByteArray, ring: PGPSecretKeyRing) {
        val scan = scan(keyBytes)
        requireSupported(scan)
        requireComplete(scan, ring)
    }

    fun requireSupported(scan: Scan) {
        val problems = buildList {
            for (key in scan.keys) problemWith(key)?.let { add("${name(key)}: $it") }
            if (scan.damaged && scan.keys.isNotEmpty()) add("The key data is damaged or cut off")
        }
        if (problems.isNotEmpty()) throw UnsupportedKeyException(problems)
    }

    fun requireComplete(scan: Scan, ring: PGPSecretKeyRing) {
        val inFile = scan.keys.count { it.isSecret }
        val read = ring.secretKeys.asSequence().count()
        if (inFile != read) {
            throw UnsupportedKeyException(listOf("$inFile keys are in the file but only $read could be read"))
        }
    }

    fun requirePrimaryUsable(ring: PGPSecretKeyRing, asOf: Date) {
        val primary = ring.secretKey.publicKey
        if (primary.validity(asOf) == KeyValidity.REVOKED) throw RevokedPrimaryKeyException()
        val expiredOn = primary.expirationDate()?.takeIf { it.before(asOf) } ?: return
        throw ExpiredPrimaryKeyException(expiredOn)
    }

    fun requireWritable(ring: PGPSecretKeyRing, asOf: Date) {
        val keys = ring.secretKeys.asSequence().toList()
        val plan = SlotPlanner.plan(keys.map { it.publicKey.toKeyTraits(asOf) })
        val problems = buildList {
            keys.forEachIndexed { index, key ->
                if (plan[index].isEmpty()) return@forEachIndexed
                val target = plan[index].joinToString("/") { it.name }
                val who = if (index == 0) "Primary key" else "Subkey $index"
                when {
                    key.publicKey.algorithm !in CARD_ALGORITHMS ->
                        add("$who would be written to $target, but ${algorithmName(key.publicKey.algorithm)} can't be written to the card")
                    key.isPrivateKeyEmpty ->
                        add("$who would be written to $target, but the file contains no private key for it")
                }
            }
        }
        if (problems.isNotEmpty()) throw UnsupportedKeyException(problems)
    }

    private fun name(key: RawKey) = if (key.isPrimary) "Primary key" else "Subkey ${key.ordinal}"

    private fun problemWith(key: RawKey): String? = when {
        key.version != SUPPORTED_VERSION ->
            "version ${key.version} key" + (if (key.algorithm >= 0) " using ${algorithmName(key.algorithm)}" else "") +
                " is not supported" + (if (key.version >= 5) " (the card only stores version 4 fingerprints)" else "")
        key.algorithm !in KNOWN_ALGORITHMS -> "${algorithmName(key.algorithm)} is not supported"
        !key.isPrimary && key.algorithm !in CARD_ALGORITHMS -> "${algorithmName(key.algorithm)} can't be written to the card"
        key.algorithm in EC_ALGORITHMS && (key.curve == null || CurveRegistry.forDottedOid(key.curve) == null) ->
            "curve ${key.curve ?: "(unreadable)"} is not supported"
        key.decodeError != null -> "could not be decoded (${key.decodeError})"
        else -> null
    }

    private fun algorithmName(tag: Int) = when (tag) {
        PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_ENCRYPT, PublicKeyAlgorithmTags.RSA_SIGN -> "RSA"
        PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT, PublicKeyAlgorithmTags.ELGAMAL_GENERAL -> "ElGamal"
        PublicKeyAlgorithmTags.DSA -> "DSA"
        PublicKeyAlgorithmTags.ECDH -> "ECDH"
        PublicKeyAlgorithmTags.ECDSA -> "ECDSA"
        PublicKeyAlgorithmTags.EDDSA_LEGACY -> "EdDSA"
        PublicKeyAlgorithmTags.X25519 -> "X25519 (RFC 9580)"
        PublicKeyAlgorithmTags.X448 -> "X448 (RFC 9580)"
        PublicKeyAlgorithmTags.Ed25519 -> "Ed25519 (RFC 9580)"
        PublicKeyAlgorithmTags.Ed448 -> "Ed448 (RFC 9580)"
        else -> "unknown algorithm (tag $tag)"
    }

    fun scan(data: ByteArray): Scan {
        val packets = ArrayList<RawPacket>()
        var damaged = false
        var i = 0
        walk@ while (i < data.size) {
            val start = i
            val b0 = data[i++].toInt() and 0xFF
            if (b0 and 0x80 == 0) { damaged = true; break }
            var partial = false
            val tag: Int
            var length: Long
            if (b0 and 0x40 != 0) {
                tag = b0 and 0x3F
                if (i >= data.size) { damaged = true; break }
                val o1 = data[i++].toInt() and 0xFF
                when {
                    o1 < 192 -> length = o1.toLong()
                    o1 < 224 -> {
                        if (i >= data.size) { damaged = true; break@walk }
                        length = (((o1 - 192) shl 8) + (data[i++].toInt() and 0xFF) + 192).toLong()
                    }
                    o1 < 255 -> { partial = true; length = 1L shl (o1 and 0x1F) }
                    else -> {
                        if (i + 4 > data.size) { damaged = true; break@walk }
                        length = readUInt(data, i); i += 4
                    }
                }
            } else {
                tag = (b0 shr 2) and 0x0F
                val octets = when (b0 and 3) { 0 -> 1; 1 -> 2; 2 -> 4; else -> 0 }
                if (i + octets > data.size) { damaged = true; break }
                length = if (octets == 0) (data.size - i).toLong() else readUInt(data, i, octets)
                i += octets
            }
            val bodyStart = i
            if (length > data.size - bodyStart) { damaged = true; break }
            var end = bodyStart + length.toInt()
            if (partial) {
                var pos = end
                while (true) {
                    if (pos >= data.size) { damaged = true; break@walk }
                    val o = data[pos++].toInt() and 0xFF
                    when {
                        o < 192 -> { end = pos + o; break }
                        o < 224 -> {
                            if (pos >= data.size) { damaged = true; break@walk }
                            end = pos + 1 + ((o - 192) shl 8) + (data[pos].toInt() and 0xFF) + 192
                            break
                        }
                        o < 255 -> pos += 1 shl (o and 0x1F)
                        else -> {
                            if (pos + 4 > data.size) { damaged = true; break@walk }
                            val final = readUInt(data, pos)
                            if (final > data.size - (pos + 4)) { damaged = true; break@walk }
                            end = pos + 4 + final.toInt()
                            break
                        }
                    }
                }
            }
            if (end > data.size) { damaged = true; break }
            packets += RawPacket(tag, start, bodyStart, end, partial)
            i = end
        }

        val keys = ArrayList<RawKey>()
        var inRing = false
        var ringEnded = false
        for (packet in packets) {
            when (packet.tag) {
                PacketTags.SECRET_KEY -> { if (inRing) { ringEnded = true } else inRing = true }
                PacketTags.PUBLIC_KEY -> if (inRing) ringEnded = true
            }
            if (ringEnded) break
            if (!inRing) continue
            if (packet.tag != PacketTags.SECRET_KEY && packet.tag != PacketTags.SECRET_SUBKEY && packet.tag != PacketTags.PUBLIC_SUBKEY) continue
            keys += rawKey(data, packet, keys.size)
        }
        return Scan(keys, damaged && !ringEnded)
    }

    private fun rawKey(data: ByteArray, packet: RawPacket, ordinal: Int): RawKey {
        fun body(at: Int): Int = if (packet.bodyStart + at < packet.end) data[packet.bodyStart + at].toInt() and 0xFF else -1
        val version = body(0)
        val algorithm = when {
            version == 3 -> body(7)
            version >= 4 -> body(5)
            else -> -1
        }
        val curve = if (version == SUPPORTED_VERSION && algorithm in EC_ALGORITHMS) curveOf(data, packet) else null
        val decodeError = if (packet.partial) "partial-length key packet" else {
            val whole = data.copyOfRange(packet.start, packet.end)
            try { decodeError(whole) } finally { whole.fill(0) }
        }
        return RawKey(ordinal, packet.tag, version, algorithm, curve, decodeError)
    }

    private fun curveOf(data: ByteArray, packet: RawPacket): String? {
        val oidAt = packet.bodyStart + 7
        val oidLength = if (packet.bodyStart + 6 < packet.end) data[packet.bodyStart + 6].toInt() and 0xFF else return null
        if (oidLength == 0 || oidLength == 0xFF || oidAt + oidLength > packet.end) return null
        return oidToDotted(data.copyOfRange(oidAt, oidAt + oidLength))
    }

    private fun decodeError(packet: ByteArray): String? = try {
        BCPGInputStream(ByteArrayInputStream(packet)).readPacket()
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun readUInt(data: ByteArray, at: Int, octets: Int = 4): Long {
        var value = 0L
        for (k in 0 until octets) value = (value shl 8) or (data[at + k].toLong() and 0xFF)
        return value
    }
}
