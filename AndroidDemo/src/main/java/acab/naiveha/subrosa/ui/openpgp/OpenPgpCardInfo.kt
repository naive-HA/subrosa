package acab.naiveha.subrosa.ui.openpgp

import com.yubico.yubikit.openpgp.KeyRef

data class OpenPgpCardInfo(
    val version: String,
    val cardholderName: String,
    val login: String,
    val slots: List<SlotInfo>,
    val userPinRetries: Int,
    val resetRetries: Int,
    val adminPinRetries: Int,
    val supportedAlgorithms: SupportedAlgorithms? = null,
    val aidManufacturerId: Short? = null,
    val cardProtocolVersion: String? = null,
) {
    data class SlotInfo(
        val ref: KeyRef,
        val algorithm: String,
        val fingerprint: String,
        val created: String,
        val hasKey: Boolean,
    )

    data class SupportedAlgorithms(val bySlot: Map<KeyRef, List<String>>) {
        fun linesFor(slot: KeyRef): List<String> = bySlot[slot].orEmpty()
    }
}
