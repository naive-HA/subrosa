package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.core.keys.PrivateKeyValues
import com.yubico.yubikit.openpgp.KeyRef

internal class CardAlgorithmInfo private constructor(
    val entries: List<CardEntry>,
    val notes: List<String>,
    val isComplete: Boolean,
) {

    val isUsable: Boolean
        get() = isComplete && entries.any { it.slot != null && it.algorithm !is CardAlgorithm.Unrecognised }

    fun lists(slot: KeyRef, key: PrivateKeyValues): Boolean {
        val listed = entries.filter { it.slot == slot }.map { it.algorithm }
        return when (key) {
            is PrivateKeyValues.Rsa -> listed.any { it is CardAlgorithm.Rsa && it.modulusBits == key.bitLength }
            is PrivateKeyValues.Ec -> {
                val oid = CurveRegistry.forSdkCurve(key.curveParams)?.dottedOid
                oid != null && listed.any { it is CardAlgorithm.Ec && it.oid == oid }
            }
            else -> false
        }
    }

    fun toLines(): List<String> {
        val bySlot = entries.groupBy { it.slot?.name ?: "tag %02X".format(it.tag) }
        val lines = mutableListOf("DO 0xFA decoded: ${entries.size} entries in ${bySlot.size} slots")
        for ((label, group) in bySlot) {
            val algorithms = group.map { it.algorithm }
            algorithms.filterIsInstance<CardAlgorithm.Rsa>()
                .groupBy { it.exponentBits to it.importFormat }
                .forEach { (shape, rsa) ->
                    val (exponentBits, format) = shape
                    val details = listOfNotNull("e=$exponentBits bits", format?.let { "import ${rsaImportName(it)}" })
                    lines += "$label  RSA (${details.joinToString(", ")}): ${rsa.map { it.modulusBits }.sorted().joinToString(", ")}"
                }
            algorithms.filterIsInstance<CardAlgorithm.Ec>()
                .groupBy { it.algorithmId to it.importFormat }
                .forEach { (shape, ec) ->
                    val (algorithmId, format) = shape
                    val details = format?.let { " (import ${ecImportName(it)})" }.orEmpty()
                    lines += "$label  ${algorithmName(algorithmId)}$details: ${ec.joinToString(", ") { curveName(it.oid) }}"
                }
            algorithms.filterIsInstance<CardAlgorithm.Unrecognised>()
                .forEach { lines += "$label  UNRECOGNISED (${it.reason}): ${it.rawHex}" }
        }
        notes.forEach { lines += "note: $it" }
        return lines
    }

    fun toSupportedAlgorithms(): OpenPgpCardInfo.SupportedAlgorithms =
        OpenPgpCardInfo.SupportedAlgorithms(
            KeyRef.entries.associateWith { slot ->
                familyLines(this.entries.filter { it.slot == slot }.map { it.algorithm })
            },
        )

    companion object {
        private const val LIST_TAG = 0xFA
        private const val ALGORITHM_RSA = 0x01
        private const val ALGORITHM_ECDH = 0x12
        private const val ALGORITHM_ECDSA = 0x13
        private const val ALGORITHM_EDDSA = 0x16
        private const val IMPORT_EC_PRIVATE_ONLY = 0x00
        private const val IMPORT_EC_WITH_PUBLIC_KEY = 0xFF

        fun describe(key: PrivateKeyValues): String = when (key) {
            is PrivateKeyValues.Rsa -> "RSA ${key.bitLength}"
            is PrivateKeyValues.Ec -> CurveRegistry.forSdkCurve(key.curveParams)?.label ?: key.curveParams.name
            else -> key::class.simpleName ?: "unknown key type"
        }

        private fun familyLines(algorithms: List<CardAlgorithm>): List<String> {
            val lines = mutableListOf<String>()
            val rsa = algorithms.filterIsInstance<CardAlgorithm.Rsa>()
            if (rsa.isNotEmpty()) {
                lines += "RSA: ${rsa.map { it.modulusBits }.distinct().sorted().joinToString(", ")}"
            }
            algorithms.filterIsInstance<CardAlgorithm.Ec>()
                .groupBy { it.algorithmId }
                .entries.sortedBy { it.key }
                .forEach { (algorithmId, ec) ->
                    lines += "${algorithmName(algorithmId)}: ${ec.map { curveName(it.oid) }.distinct().joinToString(", ")}"
                }
            algorithms.filterIsInstance<CardAlgorithm.Unrecognised>().forEach {
                lines += "Unrecognised: ${it.reason}" + if (it.rawHex.isEmpty()) "" else " (${it.rawHex})"
            }
            return lines
        }

        fun parse(data: ByteArray): CardAlgorithmInfo {
            val notes = mutableListOf<String>()
            var complete = true
            var list = data
            if (data.isEmpty()) {
                notes += "empty response"
            } else if ((data[0].toInt() and 0xFF) == LIST_TAG) {
                val header = readHeader(data, 0)
                if (header == null) {
                    notes += "tag 0xFA header is incomplete: ${data.hex()}"
                    complete = false
                    list = ByteArray(0)
                } else {
                    val available = data.size - header.valueStart
                    list = data.copyOfRange(header.valueStart, header.valueStart + minOf(header.length, available))
                    if (header.length > available) {
                        notes += "tag 0xFA declares ${header.length} bytes but only $available arrived"
                    } else if (header.length < available) {
                        notes += "${available - header.length} bytes after the 0xFA element ignored"
                    }
                }
            } else {
                notes += "no 0xFA wrapper; decoded as a bare list of slot entries"
            }

            val entries = mutableListOf<CardEntry>()
            var offset = 0
            while (offset < list.size) {
                val header = readHeader(list, offset)
                if (header == null || header.valueStart + header.length > list.size) {
                    notes += "truncated entry at offset $offset (${list.size - offset} bytes left): ${list.copyOfRange(offset, list.size).hex()}"
                    complete = false
                    break
                }
                val slot = KeyRef.entries.firstOrNull { it.algorithmAttributes == header.tag }
                val value = list.copyOfRange(header.valueStart, header.valueStart + header.length)
                entries += CardEntry(slot, header.tag, decodeAttribute(value))
                offset = header.valueStart + header.length
            }
            return CardAlgorithmInfo(entries, notes, complete)
        }

        private fun decodeAttribute(value: ByteArray): CardAlgorithm {
            if (value.isEmpty()) return CardAlgorithm.Unrecognised("empty attribute", "")
            return when (val algorithmId = value[0].toInt() and 0xFF) {
                ALGORITHM_RSA ->
                    if (value.size >= 5) {
                        CardAlgorithm.Rsa(
                            modulusBits = u16(value, 1),
                            exponentBits = u16(value, 3),
                            importFormat = value.getOrNull(5)?.let { it.toInt() and 0xFF },
                        )
                    } else {
                        CardAlgorithm.Unrecognised("RSA attribute is ${value.size} bytes, expected at least 5", value.hex())
                    }
                ALGORITHM_ECDH, ALGORITHM_ECDSA, ALGORITHM_EDDSA ->
                    decodeEc(algorithmId, value.copyOfRange(1, value.size))
                else -> CardAlgorithm.Unrecognised("algorithm id 0x%02X".format(algorithmId), value.hex())
            }
        }

        private fun decodeEc(algorithmId: Int, params: ByteArray): CardAlgorithm {
            if (params.isEmpty()) return CardAlgorithm.Unrecognised("no curve OID", "%02X".format(algorithmId))
            val whole = oidToDotted(params)
            if (whole != null && CurveRegistry.forDottedOid(whole) != null) return CardAlgorithm.Ec(algorithmId, whole, null)
            val last = params.last().toInt() and 0xFF
            if (params.size > 1 && (last == IMPORT_EC_PRIVATE_ONLY || last == IMPORT_EC_WITH_PUBLIC_KEY)) {
                val stripped = oidToDotted(params.copyOfRange(0, params.size - 1))
                if (stripped != null) return CardAlgorithm.Ec(algorithmId, stripped, last)
            }
            return CardAlgorithm.Ec(algorithmId, whole ?: "malformed(${params.hex()})", null)
        }

        private class Header(val tag: Int, val length: Int, val valueStart: Int)

        private fun readHeader(buf: ByteArray, start: Int): Header? {
            var p = start
            if (p >= buf.size) return null
            var tag = buf[p++].toInt() and 0xFF
            if ((tag and 0x1F) == 0x1F) {
                var continuation = 0
                do {
                    if (p >= buf.size || ++continuation > 3) return null
                    val next = buf[p++].toInt() and 0xFF
                    tag = (tag shl 8) or next
                } while ((next and 0x80) != 0)
            }
            if (p >= buf.size) return null
            var length = buf[p++].toInt() and 0xFF
            if ((length and 0x80) != 0) {
                val count = length and 0x7F
                if (count == 0 || count > 3 || p + count > buf.size) return null
                length = 0
                repeat(count) { length = (length shl 8) or (buf[p++].toInt() and 0xFF) }
            }
            return Header(tag, length, p)
        }

        private fun u16(buf: ByteArray, at: Int) = ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

        private fun ByteArray.hex() = toHexString(HexFormat.UpperCase)

        private fun curveName(oid: String) = CurveRegistry.forDottedOid(oid)?.label ?: "curve OID $oid"

        internal fun algorithmName(id: Int) = when (id) {
            ALGORITHM_ECDH -> "ECDH"
            ALGORITHM_ECDSA -> "ECDSA"
            ALGORITHM_EDDSA -> "EdDSA"
            else -> "algorithm 0x%02X".format(id)
        }

        private fun rsaImportName(format: Int) = when (format) {
            0 -> "standard"
            1 -> "standard+modulus"
            2 -> "CRT"
            3 -> "CRT+modulus"
            else -> "0x%02X".format(format)
        }

        private fun ecImportName(format: Int) = when (format) {
            IMPORT_EC_PRIVATE_ONLY -> "private only"
            IMPORT_EC_WITH_PUBLIC_KEY -> "private+public"
            else -> "0x%02X".format(format)
        }
    }
}

internal class CardEntry(val slot: KeyRef?, val tag: Int, val algorithm: CardAlgorithm)

internal sealed interface CardAlgorithm {
    class Rsa(val modulusBits: Int, val exponentBits: Int, val importFormat: Int?) : CardAlgorithm

    class Ec(val algorithmId: Int, val oid: String, val importFormat: Int?) : CardAlgorithm

    class Unrecognised(val reason: String, val rawHex: String) : CardAlgorithm
}
