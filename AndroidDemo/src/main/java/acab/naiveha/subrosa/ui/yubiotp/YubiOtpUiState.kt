package acab.naiveha.subrosa.ui.yubiotp

enum class SlotState { UNKNOWN, EMPTY, PROGRAMMED }

enum class OtpNfcState { UNKNOWN, ENABLED, DISABLED }

data class OtpNfcInfo(
    val applicable: Boolean,
    val hasNfc: Boolean = false,
    val otpSupported: Boolean = false,
    val locked: Boolean = false,
    val canConfigure: Boolean = false,
    val otpOverNfc: OtpNfcState = OtpNfcState.UNKNOWN,
    val viaNfc: Boolean = false,
) {
    companion object {
        val NOT_APPLICABLE = OtpNfcInfo(applicable = false)
    }
}

data class YubiOtpUiState(
    val slotOne: SlotState = SlotState.UNKNOWN,
    val slotTwo: SlotState = SlotState.UNKNOWN,
    val currentOperation: OtpOperation = OtpOperation.NONE,
    val nfc: OtpNfcInfo? = null,
    val nfcRequested: Boolean? = null,
)
