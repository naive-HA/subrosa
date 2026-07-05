package acab.naiveha.subrosa.ui.openpgp

enum class PgpDeviceType {
    YUBIKEY,
    NITROKEY,
    UNKNOWN;

    companion object {
        private const val MANUFACTURER_YUBICO: Short = 0x0006
        private const val MANUFACTURER_NITROKEY: Short = 0x000F

        fun fromManufacturerId(id: Short): PgpDeviceType = when (id) {
            MANUFACTURER_YUBICO   -> YUBIKEY
            MANUFACTURER_NITROKEY -> NITROKEY
            else                  -> UNKNOWN
        }
    }
}
