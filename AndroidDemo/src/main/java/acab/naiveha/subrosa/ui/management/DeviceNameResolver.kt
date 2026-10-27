package acab.naiveha.subrosa.ui.management

data class DeviceNameResult(
    val name: String,
    val source: Source
) {
    enum class Source {
        USB_ID,
        AID_MANUFACTURER,
        ATR,
        UNKNOWN
    }
}

object DeviceNameResolver {
    fun resolveDeviceNameWithSource(
        usbName: String?,
        aidManufacturerId: Short?,
        atrHex: String?
    ): DeviceNameResult {
        val cleanedUsbName = cleanUsbName(usbName)
        if (!cleanedUsbName.isNullOrBlank()) {
            return DeviceNameResult(cleanedUsbName, DeviceNameResult.Source.USB_ID)
        }

        if (aidManufacturerId != null) {
            val mfgName = getManufacturerName(aidManufacturerId)
            if (!mfgName.isNullOrBlank()) {
                return DeviceNameResult(mfgName, DeviceNameResult.Source.AID_MANUFACTURER)
            }
        }

        if (!atrHex.isNullOrBlank()) {
            val atrName = parseNameFromAtr(atrHex)
            if (!atrName.isNullOrBlank()) {
                return DeviceNameResult(atrName, DeviceNameResult.Source.ATR)
            }
        }

        return DeviceNameResult("Unknown", DeviceNameResult.Source.UNKNOWN)
    }

    fun resolveDeviceName(
        usbName: String?,
        aidManufacturerId: Short?,
        atrHex: String?
    ): String = resolveDeviceNameWithSource(usbName, aidManufacturerId, atrHex).name

    private fun cleanUsbName(usbName: String?): String? {
        if (usbName.isNullOrBlank()) return null
        val trimmed = usbName.trim()
        val lower = trimmed.lowercase()
        val isGenericReader = lower == "unknown" ||
            lower.contains("smart card reader") ||
            lower.contains("smartcard reader") ||
            lower.contains("ccid reader") ||
            lower.contains("generic usb")
        if (isGenericReader) {
            return null
        }
        return trimmed
    }

    fun getManufacturerName(id: Short): String? {
        return when (id.toInt() and 0xFFFF) {
            0x0001 -> "Flugzeug"
            0x0002 -> "PPC Card Systems"
            0x0003 -> "FSC"
            0x0004 -> "Athena"
            0x0005 -> "Nitrokey 2"
            0x0006, 0x000A -> "YubiKey"
            0x0007 -> "OpenPGP.card"
            0x0008, 0x0042, 0x1337, 0x6378 -> "Nitrokey"
            0x0009 -> "CardContact"
            0x000B -> "Feitian"
            0x000C -> "Cosart"
            0x000D, 0x000E -> "ZeitControl"
            0x000F -> "Nitrokey 3"
            0x0010 -> "Convida"
            0x0011 -> "Moonbase"
            0x0012 -> "FSFE"
            0x002A -> "SoloKeys"
            0xF517 -> "Gnuk"
            else -> if ((id.toInt() and 0xFFFF) != 0) "Manufacturer 0x%04X".format(id.toInt() and 0xFFFF) else null
        }
    }

    fun parseNameFromAtr(atrHex: String): String? {
        val bytes = hexToBytes(atrHex)
        if (bytes.isEmpty()) return null

        val asciiChars = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v in 0x20..0x7E) {
                asciiChars.append(v.toChar())
            } else if (asciiChars.isNotEmpty() && asciiChars.last() != ' ') {
                asciiChars.append(' ')
            }
        }
        val asciiStr = asciiChars.toString().trim()

        return when {
            asciiStr.contains("YubiKey", ignoreCase = true) -> {
                asciiStr.split(" ").find { it.contains("YubiKey", ignoreCase = true) } ?: "YubiKey"
            }
            asciiStr.contains("Nitrokey", ignoreCase = true) -> "Nitrokey"
            asciiStr.contains("Gnuk", ignoreCase = true) -> "Gnuk"
            asciiStr.contains("OpenPGP", ignoreCase = true) -> "OpenPGP Card"
            asciiStr.length >= 4 -> asciiStr
            else -> null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace(":", "")
        if (cleanHex.length % 2 != 0) return byteArrayOf()
        return try {
            ByteArray(cleanHex.length / 2) { i ->
                val index = i * 2
                cleanHex.substring(index, index + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            byteArrayOf()
        }
    }

    fun formatFirmware(
        rawFirmware: String?,
        openPgpVersion: String?
    ): String {
        val fwStr = if (rawFirmware.isNullOrBlank() ||
            rawFirmware.equals("unknown", ignoreCase = true) ||
            rawFirmware.equals("unavailable", ignoreCase = true) ||
            rawFirmware == "0.0.0"
        ) {
            "unknown"
        } else {
            rawFirmware.trim()
        }

        val cleanPgpVer = openPgpVersion?.trim()
            ?.removePrefix("OpenPGP ")
            ?.removePrefix("v")
        return if (!cleanPgpVer.isNullOrBlank()) {
            "$fwStr (OpenPGP $cleanPgpVer)"
        } else {
            fwStr
        }
    }
}
