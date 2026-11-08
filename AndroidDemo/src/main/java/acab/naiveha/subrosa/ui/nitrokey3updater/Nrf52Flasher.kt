package acab.naiveha.subrosa.ui.nitrokey3updater

internal class Nrf52Flasher : FirmwareFlasher {

    override val variant: Nk3Variant get() = Nk3Variant.NRF52

    override fun flash(image: FirmwareImage, onProgress: (FlashProgress) -> Unit) {
        throw FlashNotSupportedException(NOT_SUPPORTED_MESSAGE)
    }

    companion object {
        const val IS_SUPPORTED = false

        const val NOT_SUPPORTED_MESSAGE = "Updating the nRF52 (Nordic) chip is not supported yet."
    }
}
