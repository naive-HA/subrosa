package acab.naiveha.subrosa.ui.yubiotp

enum class SlotState { UNKNOWN, EMPTY, PROGRAMMED }

data class YubiOtpUiState(
    val slotOne: SlotState = SlotState.UNKNOWN,
    val slotTwo: SlotState = SlotState.UNKNOWN,
    val currentOperation: OtpOperation = OtpOperation.NONE,
)
