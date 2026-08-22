package acab.naiveha.subrosa.by.naiveha.ui.yubiotp

data class YubiOtpUiState(
    val slotOneProgrammed: Boolean,
    val slotTwoProgrammed: Boolean,
    val currentOperation: OtpOperation = OtpOperation.NONE,
)
