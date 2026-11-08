package acab.naiveha.subrosa.ui.nitrokey3updater

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal data class ImportedArchive(val bytes: ByteArray, val name: String, val sha256: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImportedArchive) return false
        return sha256 == other.sha256 && name == other.name
    }

    override fun hashCode(): Int = sha256.hashCode() * 31 + name.hashCode()
}

internal data class BootloaderDevice(
    val usbName: String,
    val vendorId: Int,
    val productId: Int,
    val variant: Nk3Variant?,
)

internal sealed interface ImageState {
    object None : ImageState

    object Preparing : ImageState

    class Ready(val image: FirmwareImage) : ImageState

    class Invalid(val message: String) : ImageState
}

internal data class UpdaterInput(
    val archive: ImportedArchive? = null,
    val bootloader: BootloaderDevice? = null,
    val image: ImageState = ImageState.None,
    val replugRequired: Boolean = false,
) {
    val flashableImage: FirmwareImage?
        get() {
            if (replugRequired) return null
            val variant = bootloader?.variant ?: return null
            val image = (image as? ImageState.Ready)?.image ?: return null
            return image.takeIf { it.variant == variant && isFlashSupported(variant) }
        }

    private fun isFlashSupported(variant: Nk3Variant): Boolean = when (variant) {
        Nk3Variant.LPC55 -> true
        Nk3Variant.NRF52 -> Nrf52Flasher.IS_SUPPORTED
    }
}

internal sealed interface FlashState {
    object Idle : FlashState

    class Running(val progress: FlashProgress) : FlashState

    class Succeeded(val label: String) : FlashState

    class Failed(val message: String) : FlashState {
        var announced: Boolean = false
    }
}

internal sealed interface CheckState {
    object Idle : CheckState

    class Running(val longTransfer: Boolean, val previous: BootloaderReport?) : CheckState

    class Finished(val report: BootloaderReport) : CheckState
}

internal class Nitrokey3UpdaterViewModel : ViewModel() {

    private val _input = MutableLiveData(UpdaterInput())
    val input: LiveData<UpdaterInput> = _input

    private val _flash = MutableLiveData<FlashState>(FlashState.Idle)
    val flash: LiveData<FlashState> = _flash

    private val _check = MutableLiveData<CheckState>(CheckState.Idle)
    val check: LiveData<CheckState> = _check

    override fun onCleared() {
        prepareJob?.cancel()
        readyKey = null
        _input.value = UpdaterInput()
        _flash.value = FlashState.Idle
        _check.value = CheckState.Idle
        Log.d(TAG, "Nitrokey3UpdaterViewModel onCleared() — firmware archive flushed from memory")
    }

    val isFlashing: Boolean get() = _flash.value is FlashState.Running

    val isChecking: Boolean get() = _check.value is CheckState.Running

    val isBusy: Boolean get() = isFlashing || isChecking

    private var prepareJob: Job? = null

    private var readyKey: String? = null

    @Volatile
    private var lastPublished: FlashProgress? = null

    private fun currentInput(): UpdaterInput = _input.value ?: UpdaterInput()

    fun reset() {
        if (isBusy) return
        prepareJob?.cancel()
        readyKey = null
        val current = currentInput()
        _input.value = current.copy(image = ImageState.None)
        _flash.value = FlashState.Idle
        _check.value = CheckState.Idle
        prepareImage()
    }

    fun clearResult() {
        if (!isFlashing && _flash.value !is FlashState.Idle) _flash.value = FlashState.Idle
    }

    fun onArchiveImported(archive: ImportedArchive) {
        if (isBusy) return
        readyKey = null
        _input.value = currentInput().copy(archive = archive, image = ImageState.None)
        _flash.value = FlashState.Idle
        prepareImage()
    }

    fun onArchiveCleared() {
        if (isBusy) return
        prepareJob?.cancel()
        readyKey = null
        _input.value = currentInput().copy(archive = null, image = ImageState.None)
        _flash.value = FlashState.Idle
    }

    fun onBootloaderChanged(device: BootloaderDevice?) {
        val current = currentInput()
        if (current.bootloader == device) return
        _input.value = current.copy(bootloader = device, replugRequired = false)
        if (device != null && !isChecking) _check.value = CheckState.Idle
        if (!isFlashing) prepareImage()
    }

    private fun prepareImage() {
        val current = currentInput()
        val archive = current.archive
        val variant = current.bootloader?.variant
        prepareJob?.cancel()
        if (archive == null || variant == null) {
            readyKey = null
            if (current.image !is ImageState.None) _input.value = current.copy(image = ImageState.None)
            return
        }
        val key = "${archive.sha256}:${variant.name}"
        if (current.image is ImageState.Ready && readyKey == key) return

        readyKey = null
        _input.value = current.copy(image = ImageState.Preparing)
        prepareJob = viewModelScope.launch {
            val result: ImageState = withContext(Dispatchers.IO) {
                try {
                    ImageState.Ready(FirmwareArchive.read(archive.bytes, variant))
                } catch (e: FirmwareException) {
                    Log.e(TAG, "FirmwareArchive validation failed for ${variant.displayName}: ${e.message}", e)
                    ImageState.Invalid(e.message ?: "The firmware archive is not usable.")
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error while reading the firmware archive for ${variant.displayName}", e)
                    ImageState.Invalid("The firmware archive could not be read.")
                }
            }
            if (result is ImageState.Ready) {
                readyKey = key
                Log.d(TAG, "Prepared ${result.image.fileName} for ${variant.displayName}: " +
                    "version=${result.image.displayVersion}, sha256=${result.image.sha256}, " +
                    "checksumVerified=${result.image.checksumVerified}")
            } else if (result is ImageState.Invalid) {
                Log.e(TAG, "Firmware archive preparation resulted in INVALID state for ${variant.displayName}: ${result.message}")
            }
            _input.value = currentInput().copy(image = result)
        }
    }

    fun rebootToApplication(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val current = currentInput()
        Log.d(TAG, "rebootToApplication() called — device=${current.bootloader?.usbName}, isBusy=$isBusy, replugRequired=${current.replugRequired}")
        if (isBusy) {
            Log.w(TAG, "rebootToApplication() rejected: updater is busy")
            onComplete(false, "Updater is busy")
            return
        }
        val device = current.bootloader
        if (device == null) {
            Log.w(TAG, "rebootToApplication() rejected: no bootloader connected")
            onComplete(false, "No bootloader connected")
            return
        }
        viewModelScope.launch {
            try {
                Log.d(TAG, "rebootToApplication() attempting mboot reset for device ${device.usbName}...")
                var success = false
                withBootloader(context.applicationContext, device) { transport ->
                    val client = acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootClient(
                        transport,
                        log = { Log.d(TAG, "mboot reset: $it") }
                    )
                    success = client.reset()
                    Log.d(TAG, "rebootToApplication() client.reset() returned: $success")
                }
                if (success) {
                    Log.i(TAG, "rebootToApplication() reset succeeded")
                    onComplete(true, null)
                } else {
                    Log.w(TAG, "rebootToApplication() reset command returned false (bootloader rejected reset, likely aborted active data phase)")
                    onComplete(false, "Reset command rejected by bootloader (device was in data phase)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "rebootToApplication() reset failed with exception", e)
                onComplete(false, e.message)
            }
        }
    }

    fun startCheck(context: Context) {
        if (isBusy) return
        val current = currentInput()
        val device = current.bootloader ?: return
        if (device.variant != Nk3Variant.LPC55 || current.replugRequired) return

        clearResult()
        _check.value = CheckState.Running(longTransfer = false, previous = null)
        viewModelScope.launch {
            val report: BootloaderReport = try {
                withBootloader(context.applicationContext, device) { transport ->
                    BootloaderCheck(transport, transport.linkInfo(), log = { line -> Log.d(TAG, "check: $line") }).runReadOnly()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlashException) {
                BootloaderReport.failure(e.message ?: "The bootloader could not be opened.")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during the bootloader check", e)
                BootloaderReport.failure("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
            Log.i(TAG, "Bootloader check report:\n${report.toText()}")

            val image = currentInput().flashableImage
            if (report.canTestLongTransfers && image != null) {
                _check.value = CheckState.Running(longTransfer = true, previous = report)
                val result: LongTransferResult = try {
                    withBootloader(context.applicationContext, device) { transport ->
                        BootloaderCheck(transport, transport.linkInfo(), log = { line -> Log.d(TAG, "check: $line") }).testLongTransfers(image)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FlashException) {
                    Log.w(TAG, "Long transfer test FlashException", e)
                    BootloaderCheck.longTransferFailure(e.message ?: "The bootloader could not be opened.", needsReplug = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error during the long-transfer test", e)
                    BootloaderCheck.longTransferFailure("Unexpected error: ${e.message ?: e.javaClass.simpleName}", needsReplug = true)
                }
                val updated = report.withLongTransfer(result)
                Log.i(TAG, "Bootloader long transfer report:\n${updated.toText()}")
                publishCheck(device, updated)
            } else {
                val currentImageState = currentInput().image
                if (currentImageState is ImageState.Invalid) {
                    val invalidMsg = currentImageState.message
                    Log.e(TAG, "Bootloader check cannot test firmware material — archive is invalid: $invalidMsg")
                    val failSection = CheckSection(
                        "Firmware Validation",
                        listOf(CheckLine(CheckLevel.FAIL, "Firmware archive validation failed: $invalidMsg"))
                    )
                    val failedReport = report.withLongTransfer(LongTransferResult(failSection, needsReplug = false))
                    publishCheck(device, failedReport)
                } else {
                    publishCheck(device, report)
                }
            }
        }
    }

    fun longTransferPlan(): LongTransferPlan? {
        val image = currentInput().flashableImage ?: return null
        val report = when (val state = _check.value) {
            is CheckState.Finished -> state.report
            is CheckState.Running -> state.previous
            else -> null
        } ?: return null
        if (!report.canTestLongTransfers) return null
        return BootloaderCheck.plan(image, report.packetSize ?: return null)
    }

    fun startLongTransferTest(context: Context) {
        if (isBusy) return
        val current = currentInput()
        val device = current.bootloader ?: return
        val image = current.flashableImage ?: return
        val previous = (_check.value as? CheckState.Finished)?.report ?: return
        if (longTransferPlan() == null) return

        Log.d(TAG, "startLongTransferTest() starting for device ${device.usbName}")
        clearResult()
        _check.value = CheckState.Running(longTransfer = true, previous = previous)
        viewModelScope.launch {
            val result: LongTransferResult = try {
                withBootloader(context.applicationContext, device) { transport ->
                    BootloaderCheck(transport, transport.linkInfo(), log = { line -> Log.d(TAG, "check: $line") }).testLongTransfers(image)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlashException) {
                Log.w(TAG, "Long transfer test FlashException", e)
                BootloaderCheck.longTransferFailure(e.message ?: "The bootloader could not be opened.", needsReplug = false)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during the long-transfer test", e)
                BootloaderCheck.longTransferFailure("Unexpected error: ${e.message ?: e.javaClass.simpleName}", needsReplug = true)
            }
            val updated = previous.withLongTransfer(result)
            Log.i(TAG, "Bootloader long transfer report:\n${updated.toText()}")
            Log.d(TAG, "startLongTransferTest() finished — needsReplug=${updated.needsReplug}")
            publishCheck(device, updated)
        }
    }

    private fun publishCheck(device: BootloaderDevice, report: BootloaderReport) {
        val current = currentInput()
        if (current.bootloader != null && current.bootloader.usbName != device.usbName) {
            _check.value = CheckState.Idle
            return
        }
        if (report.needsReplug && !current.replugRequired) _input.value = current.copy(replugRequired = true)
        _check.value = CheckState.Finished(report)
    }

    fun startFlash(context: Context) {
        if (isBusy) return
        val current = currentInput()
        val device = current.bootloader ?: return
        val image = current.flashableImage ?: return

        lastPublished = null
        _flash.value = FlashState.Running(FlashProgress(FlashPhase.CONNECTING))
        viewModelScope.launch {
            val outcome: FlashState = try {
                install(context.applicationContext, device, image)
                FlashState.Succeeded(image.displayVersion ?: image.fileName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlashException) {
                Log.w(TAG, "Firmware update failed: ${e.message}", e)
                FlashState.Failed(e.message ?: "The firmware update failed.")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during the firmware update", e)
                FlashState.Failed("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
            _flash.value = outcome
        }
    }

    private suspend fun install(context: Context, device: BootloaderDevice, image: FirmwareImage) {
        when (image.variant) {
            Nk3Variant.LPC55 -> installLpc55(context, device, image)
            Nk3Variant.NRF52 -> Nrf52Flasher().flash(image, ::publishProgress)
        }
    }

    private suspend fun installLpc55(context: Context, device: BootloaderDevice, image: FirmwareImage) {
        withBootloader(context, device) { transport ->
            Lpc55Flasher(transport, log = { line -> Log.d(TAG, "mboot: $line") }).flash(image, ::publishProgress)
        }
    }

    private suspend fun <T> withBootloader(context: Context, device: BootloaderDevice, block: (UsbHidMbootTransport) -> T): T {
        val connector = UsbBootloaderConnector(context)
        val usbDevice = connector.findDevice(device.usbName)
            ?: throw FlashException("The Nitrokey 3 bootloader is no longer connected.")
        if (!connector.ensurePermission(usbDevice)) {
            throw FlashException("USB access was not granted. Allow sub rosa to use the Nitrokey 3 and try again.")
        }
        return withContext(Dispatchers.IO) {
            val transport = try {
                connector.open(usbDevice)
            } catch (e: IOException) {
                throw FlashException(e.message ?: "The bootloader could not be opened.", e)
            }
            transport.use(block)
        }
    }

    private fun publishProgress(progress: FlashProgress) {
        val last = lastPublished
        if (last != null && last.phase == progress.phase && last.percent == progress.percent) return
        lastPublished = progress
        viewModelScope.launch {
            if (_flash.value is FlashState.Running) _flash.value = FlashState.Running(progress)
        }
    }

    private companion object {
        const val TAG = "Nitrokey3UpdaterVM"
    }
}
