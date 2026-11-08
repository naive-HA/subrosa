package acab.naiveha.subrosa.ui.nitrokey3updater

internal class Sb2Header private constructor(
    val versionMajor: Int,
    val versionMinor: Int,
    val imageBlocks: Long,
    val firstBootTagBlock: Long,
    val productVersion: String?,
    val componentVersion: String?,
    val buildNumber: Long,
) {
    val expectedLength: Long get() = imageBlocks * BLOCK_SIZE

    val pausePoint: Int get() = (firstBootTagBlock * BLOCK_SIZE).toInt()

    companion object {
        const val HEADER_SIZE = 96
        const val BLOCK_SIZE = 16

        private const val OFFSET_SIGNATURE_1 = 0x14
        private const val OFFSET_VERSION_MAJOR = 0x18
        private const val OFFSET_VERSION_MINOR = 0x19
        private const val OFFSET_IMAGE_BLOCKS = 0x1C
        private const val OFFSET_FIRST_BOOT_TAG_BLOCK = 0x20
        private const val OFFSET_SIGNATURE_2 = 0x34
        private const val OFFSET_PRODUCT_VERSION = 0x40
        private const val OFFSET_COMPONENT_VERSION = 0x4C
        private const val OFFSET_BUILD_NUMBER = 0x58

        private const val VERSION_COMPONENTS = 3
        private const val VERSION_COMPONENT_STRIDE = 4

        private const val SIGNATURE_1 = "STMP"
        private const val SIGNATURE_2 = "sgtl"

        fun parse(data: ByteArray): Sb2Header {
            if (data.size < HEADER_SIZE) {
                throw FirmwareException("The firmware file is incomplete or too small (${data.size} bytes).")
            }
            if (!data.hasAscii(OFFSET_SIGNATURE_1, SIGNATURE_1) || !data.hasAscii(OFFSET_SIGNATURE_2, SIGNATURE_2)) {
                throw FirmwareException("The firmware file format is invalid or corrupted.")
            }
            val major = data[OFFSET_VERSION_MAJOR].toInt() and 0xFF
            val minor = data[OFFSET_VERSION_MINOR].toInt() and 0xFF
            if (major != 2) {
                throw FirmwareException("Unsupported firmware binary version $major.$minor.")
            }

            val imageBlocks = data.u32(OFFSET_IMAGE_BLOCKS)
            if (imageBlocks * BLOCK_SIZE != data.size.toLong()) {
                throw FirmwareException(
                    "The firmware file is ${data.size} bytes, but its header announces ${imageBlocks * BLOCK_SIZE} bytes. " +
                        "The file is incomplete or damaged."
                )
            }
            val firstBootTagBlock = data.u32(OFFSET_FIRST_BOOT_TAG_BLOCK)
            if (firstBootTagBlock > imageBlocks) {
                throw FirmwareException("The firmware file header is damaged (boot tag outside of the file).")
            }

            return Sb2Header(
                versionMajor = major,
                versionMinor = minor,
                imageBlocks = imageBlocks,
                firstBootTagBlock = firstBootTagBlock,
                productVersion = data.versionAt(OFFSET_PRODUCT_VERSION),
                componentVersion = data.versionAt(OFFSET_COMPONENT_VERSION),
                buildNumber = data.u32(OFFSET_BUILD_NUMBER),
            )
        }

        private fun ByteArray.hasAscii(offset: Int, text: String): Boolean =
            text.indices.all { this[offset + it].toInt() == text[it].code }

        private fun ByteArray.u16(offset: Int): Int =
            (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

        private fun ByteArray.u32(offset: Int): Long =
            (u16(offset).toLong()) or (u16(offset + 2).toLong() shl 16)

        private fun ByteArray.u16be(offset: Int): Int =
            ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

        private fun ByteArray.versionAt(offset: Int): String? {
            val components = ArrayList<Int>(VERSION_COMPONENTS)
            for (index in 0 until VERSION_COMPONENTS) {
                val component = bcdToInt(u16be(offset + VERSION_COMPONENT_STRIDE * index)) ?: return null
                components.add(component)
            }
            return components.joinToString(".")
        }

        private fun bcdToInt(bcd: Int): Int? {
            var value = 0
            for (shift in 12 downTo 0 step 4) {
                val digit = (bcd shr shift) and 0xF
                if (digit > 9) return null
                value = value * 10 + digit
            }
            return value
        }
    }
}
