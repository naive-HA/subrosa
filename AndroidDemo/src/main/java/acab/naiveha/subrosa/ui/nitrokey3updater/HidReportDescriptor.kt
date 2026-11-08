package acab.naiveha.subrosa.ui.nitrokey3updater

internal enum class HidReportType(val label: String) {
    INPUT("IN"),
    OUTPUT("OUT"),
    FEATURE("FEATURE"),
}

internal class HidDescriptorException(message: String) : Exception(message)

internal class DeclaredReport(val type: HidReportType, val id: Int, val sizeInBits: Long) {
    val dataBytes: Int get() = ((sizeInBits + 7) / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal class HidReportDescriptor private constructor(
    val reports: List<DeclaredReport>,
    val usesReportIds: Boolean,
) {

    fun find(type: HidReportType, id: Int): DeclaredReport? = reports.firstOrNull { it.type == type && it.id == id }

    fun wireBytes(type: HidReportType, id: Int): Int? =
        find(type, id)?.let { it.dataBytes + if (usesReportIds) 1 else 0 }

    private class GlobalState(val reportSize: Long, val reportCount: Long, val reportId: Int)

    companion object {
        private const val LONG_ITEM_PREFIX = 0xFE

        private const val TYPE_MAIN = 0
        private const val TYPE_GLOBAL = 1

        private const val MAIN_INPUT = 0x8
        private const val MAIN_OUTPUT = 0x9
        private const val MAIN_FEATURE = 0xB

        private const val GLOBAL_REPORT_SIZE = 0x7
        private const val GLOBAL_REPORT_ID = 0x8
        private const val GLOBAL_REPORT_COUNT = 0x9
        private const val GLOBAL_PUSH = 0xA
        private const val GLOBAL_POP = 0xB

        private const val MAX_PLAUSIBLE = 1L shl 24

        fun parse(data: ByteArray): HidReportDescriptor {
            val bits = LinkedHashMap<Pair<HidReportType, Int>, Long>()
            val stack = ArrayDeque<GlobalState>()
            var reportSize = 0L
            var reportCount = 0L
            var reportId = 0
            var usesReportIds = false

            var index = 0
            while (index < data.size) {
                val prefix = data[index].toInt() and 0xFF

                if (prefix == LONG_ITEM_PREFIX) {
                    if (index + 2 >= data.size) throw HidDescriptorException("truncated long item at byte $index")
                    val dataSize = data[index + 1].toInt() and 0xFF
                    index += 3 + dataSize
                    if (index > data.size) throw HidDescriptorException("truncated long item")
                    continue
                }

                val sizeCode = prefix and 0x03
                val size = if (sizeCode == 3) 4 else sizeCode
                val type = (prefix shr 2) and 0x03
                val tag = prefix shr 4
                if (index + 1 + size > data.size) throw HidDescriptorException("truncated item at byte $index")
                var value = 0L
                for (k in 0 until size) value = value or ((data[index + 1 + k].toLong() and 0xFF) shl (8 * k))
                index += 1 + size

                when (type) {
                    TYPE_GLOBAL -> when (tag) {
                        GLOBAL_REPORT_SIZE -> reportSize = plausible(value, "report size")
                        GLOBAL_REPORT_COUNT -> reportCount = plausible(value, "report count")
                        GLOBAL_REPORT_ID -> {
                            if (value !in 1..255) throw HidDescriptorException("invalid report id $value")
                            reportId = value.toInt()
                            usesReportIds = true
                        }
                        GLOBAL_PUSH -> stack.addLast(GlobalState(reportSize, reportCount, reportId))
                        GLOBAL_POP -> {
                            val saved = stack.removeLastOrNull() ?: throw HidDescriptorException("Pop without Push")
                            reportSize = saved.reportSize
                            reportCount = saved.reportCount
                            reportId = saved.reportId
                        }
                    }
                    TYPE_MAIN -> {
                        val kind = when (tag) {
                            MAIN_INPUT -> HidReportType.INPUT
                            MAIN_OUTPUT -> HidReportType.OUTPUT
                            MAIN_FEATURE -> HidReportType.FEATURE
                            else -> null
                        }
                        if (kind != null) {
                            val key = kind to reportId
                            bits[key] = (bits[key] ?: 0L) + reportSize * reportCount
                        }
                    }
                }
            }

            val reports = bits.entries
                .map { (key, sizeInBits) -> DeclaredReport(key.first, key.second, sizeInBits) }
                .sortedWith(compareBy({ it.type.ordinal }, { it.id }))
            return HidReportDescriptor(reports, usesReportIds)
        }

        private fun plausible(value: Long, what: String): Long {
            if (value > MAX_PLAUSIBLE) throw HidDescriptorException("implausible $what: $value")
            return value
        }
    }
}
