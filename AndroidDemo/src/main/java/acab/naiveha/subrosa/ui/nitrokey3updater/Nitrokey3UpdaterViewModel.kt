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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

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
    val bootloaderConflict: List<UsbDeviceInfo> = emptyList(),
    val preflight: DevicePreflight? = null,
    val rebootPending: Boolean = false,
) {
    val flashableImage: FirmwareImage?
        get() {
            if (replugRequired) return null
            val variant = bootloader?.variant ?: return null
            val image = (image as? ImageState.Ready)?.image ?: return null
            return image.takeIf { it.variant == variant && isFlashSupported(variant) }
        }

    val hasBootloaderConflict: Boolean get() = bootloaderConflict.isNotEmpty()

    val flashBlock: FlashBlock? get() = FirmwarePolicy.flashBlock(flashableImage, preflight)

    val canFlash: Boolean get() = flashableImage != null && flashBlock == null

    val needsIdentityConfirmation: Boolean
        get() = bootloader?.let { Nk3UsbIds.isGenericLpc55(it.vendorId, it.productId) } == true

    fun withBootloader(device: BootloaderDevice?): UpdaterInput {
        if (bootloader == device && bootloaderConflict.isEmpty()) return this
        val keptPreflight = when {
            device == null -> if (bootloader == null) preflight else null
            bootloader == null -> preflight.takeIf { rebootPending }
            else -> null
        }
        return copy(
            bootloader = device,
            replugRequired = false,
            bootloaderConflict = emptyList(),
            preflight = keptPreflight,
            rebootPending = if (device != null) false else rebootPending,
        )
    }

    fun withBootloaderConflict(candidates: List<UsbDeviceInfo>): UpdaterInput =
        if (bootloader == null && bootloaderConflict == candidates) {
            this
        } else {
            copy(
                bootloader = null,
                replugRequired = false,
                bootloaderConflict = candidates,
                preflight = null,
                rebootPending = false,
            )
        }

    fun withPreflight(read: DevicePreflight?): UpdaterInput = when {
        bootloader != null || bootloaderConflict.isNotEmpty() -> this
        read == null && rebootPending -> this
        else -> copy(preflight = read)
    }

    fun withRebootRequested(): UpdaterInput = if (rebootPending) this else copy(rebootPending = true)

    fun withRebootFailed(): UpdaterInput = if (rebootPending) copy(rebootPending = false) else this

    fun withoutPreflight(): UpdaterInput =
        if (preflight == null && !rebootPending) this else copy(preflight = null, rebootPending = false)

    fun afterFlashFailure(failed: FlashState.Failed): UpdaterInput =
        if (failed.replugRequired) copy(replugRequired = true) else this

    private fun isFlashSupported(variant: Nk3Variant): Boolean = when (variant) {
        Nk3Variant.LPC55 -> true
        Nk3Variant.NRF52 -> Nrf52Flasher.IS_SUPPORTED
    }
}

internal sealed interface FlashState {
    object Idle : FlashState

    class Running(val progress: FlashProgress) : FlashState

    class Succeeded(val label: String, val verification: FirmwareVerification) : FlashState

    class Failed(
        val message: String,
        val stage: FlashStage = FlashStage.CONNECT,
        val bytesSent: Long = 0,
        val total: Long = 0,
        val status: Int? = null,
        val replugRequired: Boolean = false,
    ) : FlashState {
        var announced: Boolean = false

        val advice: FailureAdvice get() = FailureAdvice.of(stage, bytesSent, replugRequired)

        companion object {
            fun from(e: FlashException): Failed {
                val message = e.message ?: "The firmware update failed."
                val failure = e.failure ?: return Failed(message)
                return Failed(
                    message,
                    failure.stage,
                    failure.bytesSent,
                    failure.total,
                    failure.status,
                    failure.replugRequired,
                )
            }
        }
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

    private val _flash = MutableLiveData<FlashState>(FlashSession.state.value)
    val flash: LiveData<FlashState> = _flash

    private val _check = MutableLiveData<CheckState>(CheckState.Idle)
    val check: LiveData<CheckState> = _check

    private val _busy = MutableLiveData(FlashSession.isRunning)

    val busy: LiveData<Boolean> = _busy

    private var prepareJob: Job? = null

    private var readyKey: String? = null

    init {
        viewModelScope.launch(Dispatchers.Main) {
            FlashSession.state.collect { onFlashSessionState(it) }
        }
    }

    override fun onCleared() {
        prepareJob?.cancel()
        readyKey = null
        _input.value = UpdaterInput()
        _check.value = CheckState.Idle
        Log.d(TAG, "Nitrokey3UpdaterViewModel onCleared() — firmware archive flushed from memory")
    }

    val isFlashing: Boolean get() = FlashSession.isRunning

    val isChecking: Boolean get() = _check.value is CheckState.Running

    val isBusy: Boolean get() = isFlashing || isChecking

    private fun currentInput(): UpdaterInput = _input.value ?: UpdaterInput()

    private fun onFlashSessionState(state: FlashState) {
        if (state is FlashState.Failed && state.replugRequired) {
            _input.value = currentInput().afterFlashFailure(state)
        }
        _flash.value = state
        refreshBusy()
    }

    private fun setCheck(state: CheckState) {
        _check.value = state
        refreshBusy()
    }

    private fun refreshBusy() {
        val busy = isBusy
        if (_busy.value != busy) _busy.value = busy
    }

    fun reset() {
        if (isBusy) return
        discardFlashResult()
        resetCheckAndImage()
    }

    fun onDeviceChanged() {
        if (isBusy) return
        resetCheckAndImage()
    }

    private fun resetCheckAndImage() {
        prepareJob?.cancel()
        readyKey = null
        _input.value = currentInput().copy(image = ImageState.None)
        setCheck(CheckState.Idle)
        prepareImage()
    }

    private fun discardFlashResult() {
        val result = FlashSession.state.value
        if (result is FlashState.Failed && !result.announced) return
        FlashSession.clearResult()
    }

    fun clearResult() {
        FlashSession.clearResult()
    }

    fun onArchiveImported(archive: ImportedArchive) {
        if (isBusy) return
        readyKey = null
        _input.value = currentInput().copy(archive = archive, image = ImageState.None)
        FlashSession.clearResult()
        prepareImage()
    }

    fun onArchiveCleared() {
        if (isBusy) return
        prepareJob?.cancel()
        readyKey = null
        _input.value = currentInput().copy(archive = null, image = ImageState.None)
        FlashSession.clearResult()
    }

    fun onBootloaderChanged(device: BootloaderDevice?) {
        val current = currentInput()
        if (current.bootloader == device && !current.hasBootloaderConflict) return
        _input.value = current.withBootloader(device)
        if (device != null && !isChecking) setCheck(CheckState.Idle)
        if (!isFlashing) prepareImage()
    }

    fun onBootloaderConflict(candidates: List<UsbDeviceInfo>) {
        val current = currentInput()
        if (current.bootloader == null && current.bootloaderConflict == candidates) return
        Log.w(TAG, "More than one bootloader device is attached: ${candidates.joinToString { it.ids }}")
        _input.value = current.withBootloaderConflict(candidates)
        if (!isChecking) setCheck(CheckState.Idle)
        if (!isFlashing) prepareImage()
    }

    fun onPreflightRead(read: DevicePreflight?) {
        val current = currentInput()
        val keptEarlier = read == null && current.rebootPending
        Log.i(TAG, "Pre-flight capture in application mode: ${read?.summary() ?: "nothing readable"}" +
            (if (keptEarlier) " (the key is rebooting, keeping the earlier capture)" else ""))
        val updated = current.withPreflight(read)
        if (updated != current) _input.value = updated
    }

    fun onRebootRequested() {
        val current = currentInput()
        if (!current.rebootPending) _input.value = current.withRebootRequested()
    }

    fun onRebootFailed() {
        val current = currentInput()
        if (current.rebootPending) _input.value = current.withRebootFailed()
    }

    fun onScreenLeft() {
        val current = currentInput()
        val updated = current.withoutPreflight()
        if (updated != current) _input.value = updated
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
        setCheck(CheckState.Running(longTransfer = false, previous = null))
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
                setCheck(CheckState.Running(longTransfer = true, previous = report))
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
        setCheck(CheckState.Running(longTransfer = true, previous = previous))
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
            setCheck(CheckState.Idle)
            return
        }
        if (report.needsReplug && !current.replugRequired) _input.value = current.copy(replugRequired = true)
        setCheck(CheckState.Finished(report))
    }

    fun startFlash(context: Context, identityConfirmed: Boolean = false) {
        if (isBusy) return
        val current = currentInput()
        val device = current.bootloader ?: return
        val image = current.flashableImage ?: return
        val block = current.flashBlock
        if (block != null) {
            Log.w(TAG, "startFlash() refused: ${block.javaClass.simpleName}")
            return
        }
        if (current.needsIdentityConfirmation && !identityConfirmed) {
            Log.w(TAG, "startFlash() refused: USB ${Nk3UsbIds.format(device.vendorId, device.productId)} " +
                "is not a Nitrokey 3 bootloader identity and the user did not confirm it")
            return
        }
        val request = FlashRequest(device, image)
        if (!FlashSession.begin(request)) return
        refreshBusy()

        val appContext = context.applicationContext
        viewModelScope.launch {
            val refusal: FlashState.Failed? = try {
                requireBootloaderAccess(UsbBootloaderConnector(appContext), device)
                FirmwareFlashService.start(appContext)
                null
            } catch (e: CancellationException) {
                FlashSession.abandon(request)
                throw e
            } catch (e: FlashException) {
                Log.w(TAG, "The firmware update could not be started: ${e.message}", e)
                FlashState.Failed.from(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error while starting the firmware update", e)
                FlashState.Failed("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
            if (refusal != null) {
                FlashSession.failIfUnclaimed(request, refusal)
                return@launch
            }

            delay(SERVICE_START_TIMEOUT_MS.milliseconds)
            val timedOut = FlashState.Failed(
                message = "The update service did not start.",
                total = image.data.size.toLong(),
            )
            if (FlashSession.failIfUnclaimed(request, timedOut)) {
                Log.e(TAG, "The update service did not take the request within $SERVICE_START_TIMEOUT_MS ms")
            }
        }
    }

    private suspend fun <T> withBootloader(context: Context, device: BootloaderDevice, block: (UsbHidMbootTransport) -> T): T =
        withBootloaderTransport(context, device, block)

    private companion object {
        const val TAG = "Nitrokey3UpdaterVM"
        const val SERVICE_START_TIMEOUT_MS = 10_000L
    }
}
