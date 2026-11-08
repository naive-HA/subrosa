package acab.naiveha.subrosa.ui.nitrokey3updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import acab.naiveha.subrosa.MainViewModel
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentNitrokey3updaterBinding
import acab.naiveha.subrosa.ui.showConfirmationDialog
import acab.naiveha.subrosa.ui.showErrorDialog
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.fido.FidoProtocol
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.support.DeviceUtil
import acab.naiveha.subrosa.ui.management.DeviceNameResolver
import acab.naiveha.subrosa.ui.openpgp.Nitrokey3AdminVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds

class Nitrokey3UpdaterFragment : Fragment() {
    companion object {
        private const val TAG = "Nitrokey3UpdaterFragment"

        // Nitrokey 3 Admin Applet AID and INS
        private val ADMIN_AID = byteArrayOf(
            0xa0.toByte(), 0x00, 0x00, 0x08, 0x47, 0x00, 0x00, 0x00, 0x01
        )
        private const val INS_ADMIN_REBOOT_TO_BOOTLOADER: Byte = 0x51.toByte()

        // Known VIDs
        private const val VID_NITROKEY = Nk3UsbIds.VID_NITROKEY
        private const val VID_NXP = 0x1FC9
        private const val VID_NORDIC = 0x1915

        // Known PIDs
        private const val PID_NK3_APP = Nk3UsbIds.PID_APP
        private const val PID_NK3_BOOTLOADER_LPC55 = Nk3UsbIds.PID_BOOTLOADER_LPC55 // 17117: NXP LPC55 bootloader
        private const val PID_NK3_BOOTLOADER_NRF52 = Nk3UsbIds.PID_BOOTLOADER_NRF52 // 17128: Nordic nRF52 bootloader
        private const val PID_NXP_DFU = 0x0021
    }

    private var _binding: FragmentNitrokey3updaterBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()

    private val updaterViewModel: Nitrokey3UpdaterViewModel by activityViewModels()

    private var isInBootloaderMode = false
    private var isToggling = false
    private var lastAnnouncedInvalidImage: String? = null
    private var lastAnnouncedFailedCheckReport: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    mainHandler.postDelayed({ checkConnectedUsbDevice() }, 500)
                }
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            if (updaterViewModel.isBusy) {
                Log.d(TAG, "Ignoring imported file: an update or a check is running")
                return@registerForActivityResult
            }
            Log.d(TAG, "Selected firmware zip URI: $uri")
            try {
                val fileName = getFileName(uri)
                val bytes = requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.readBytes()
                } ?: throw java.io.IOException("Could not read file from $uri")

                FirmwareArchive.validate(bytes)

                val sha256 = computeSha256(bytes)
                updaterViewModel.onArchiveImported(ImportedArchive(bytes, fileName, sha256))
                Log.d(TAG, "Imported firmware $fileName into memory, sha256: $sha256")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read or validate imported file into memory: ${e.message}", e)
                errorVibrate()
                val errorMsg = e.message ?: "Failed to import firmware archive"
                showErrorDialog(errorMsg, title = getString(R.string.error_dialog_title))
            }
        } else {
            Log.d(TAG, "File picker cancelled")
        }
    }

    private fun launchFilePicker() {
        val input = updaterViewModel.input.value ?: UpdaterInput()
        val hasValidArchive = input.archive != null && input.image is ImageState.Ready
        val integrityChecked = binding.integrityCheckbox.isChecked
        val inBootloader = isInBootloaderMode && hasValidArchive && integrityChecked
        if (updaterViewModel.isBusy || inBootloader || !binding.selectedFileText.isEnabled) return
        updaterViewModel.reset()
        binding.integrityCheckbox.isChecked = false
        updateWriteStatus("")
        binding.btnBootloaderCancel.visibility = View.GONE
        binding.btnWrite.text = getString(
            if (isInBootloaderMode) R.string.nitrokey3updater_reboot_app else R.string.nitrokey3updater_reboot_bootloader
        )
        val frameParams = binding.btnWriteFrame.layoutParams as LinearLayout.LayoutParams
        frameParams.width = LinearLayout.LayoutParams.MATCH_PARENT
        frameParams.weight = 0f
        frameParams.marginStart = 0
        binding.btnWriteFrame.layoutParams = frameParams

        filePickerLauncher.launch(arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
            "*/*"
        ))
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get file name", e)
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                result = result.substring(cut + 1)
            }
        }
        return result ?: "Unknown file"
    }

    private fun computeSha256(bytes: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute SHA-256", e)
            "Error computing hash"
        }
    }

    private fun formatSha256(sha256: String): String {
        return if (sha256.length == 64) {
            "${sha256.substring(0, 32)}\n${sha256.substring(32)}"
        } else {
            sha256
        }
    }

    private fun updateSelectedFileDisplay(fileName: String?) {
        if (fileName == null) {
            val text = getString(R.string.tap_to_import)
            val spannable = SpannableString(text)
            spannable.setSpan(UnderlineSpan(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            binding.selectedFileText.text = spannable
            binding.sha256sumText.text = getString(R.string.dash)
        } else {
            binding.selectedFileText.text = fileName
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (savedInstanceState == null && !updaterViewModel.isBusy) {
            updaterViewModel.reset()
        }

        _binding = FragmentNitrokey3updaterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        if (!updaterViewModel.isBusy && isRemoving) {
            updaterViewModel.onArchiveCleared()
            Log.d(TAG, "Nitrokey3UpdaterFragment popped — flushing firmware archive from memory")
        }
        mainHandler.removeCallbacksAndMessages(null)
        try {
            requireContext().unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "Receiver not registered or already unregistered", e)
        }
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (updaterViewModel.isBusy) {
                    val message = if (updaterViewModel.isChecking) {
                        R.string.nitrokey3updater_check_in_progress
                    } else {
                        R.string.nitrokey3updater_update_in_progress
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    return
                }
                findNavController().popBackStack(R.id.nav_management, false)
            }
        })

        updateSelectedFileDisplay(null)
        binding.selectedFileText.setTextColor(ColorStateList.valueOf(binding.selectedFileText.currentTextColor))
        binding.deviceModel.text = getString(R.string.dash)
        binding.deviceFirmware.text = getString(R.string.dash)
        binding.btnWrite.text = getString(R.string.nitrokey3updater_reboot_bootloader)
        binding.btnWrite.isEnabled = false

        binding.integrityText.setOnClickListener {
            if (binding.integrityCheckbox.isEnabled) {
                binding.integrityCheckbox.isChecked = !binding.integrityCheckbox.isChecked
            }
        }
        binding.integrityCheckbox.setOnCheckedChangeListener { _, _ ->
            render()
        }

        binding.selectedFileText.setOnClickListener {
            launchFilePicker()
        }

        binding.selectedFileText.setOnLongClickListener {
            if (!updaterViewModel.isBusy) {
                updaterViewModel.onArchiveCleared()
                Toast.makeText(requireContext(), "Cleared imported file", Toast.LENGTH_SHORT).show()
            }
            true
        }

        val instructionsText = getString(R.string.nitrokey3updater_instructions)
        val urlString = "https://github.com/Nitrokey/nitrokey-3-firmware/releases"
        val importWord = "import"

        val urlIndex = instructionsText.indexOf(urlString)
        val importIndex = instructionsText.indexOf(importWord, ignoreCase = true)

        if (urlIndex != -1 && importIndex != -1) {
            val spannable = SpannableString(instructionsText)

            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val intent = Intent(Intent.ACTION_VIEW, urlString.toUri())
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open URL", e)
                        }
                    }
                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = true
                        ds.color = binding.updaterInstructions.currentTextColor
                    }
                },
                urlIndex,
                urlIndex + urlString.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        if (!binding.selectedFileText.isEnabled) return
                        Log.d(TAG, "Triggering file picker for firmware zip")
                        launchFilePicker()
                    }
                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = binding.selectedFileText.isEnabled
                        ds.color = binding.updaterInstructions.currentTextColor
                    }
                },
                importIndex,
                importIndex + importWord.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            binding.updaterInstructions.text = spannable
            binding.updaterInstructions.movementMethod = LinkMovementMethod.getInstance()
            binding.updaterInstructions.highlightColor = Color.TRANSPARENT
        } else {
            binding.updaterInstructions.text = instructionsText
        }

        binding.btnWrite.setOnClickListener {
            val input = updaterViewModel.input.value ?: UpdaterInput()
            val hasValidArchive = input.archive != null && input.image is ImageState.Ready
            val integrityChecked = binding.integrityCheckbox.isChecked
            val inBootloader = isInBootloaderMode && hasValidArchive && integrityChecked
            val checkState = updaterViewModel.check.value
            val testSucceeded = inBootloader && checkState is CheckState.Finished && checkState.report.worst != CheckLevel.FAIL

            if (inBootloader) {
                if (testSucceeded) {
                    confirmFlash()
                } else {
                    updaterViewModel.startCheck(requireContext())
                }
            } else {
                handleToggleClick()
            }
        }

        binding.btnBootloaderCancel.setOnClickListener {
            isInBootloaderMode = false
            binding.btnBootloaderCancel.visibility = View.GONE
            binding.btnWrite.text = getString(R.string.nitrokey3updater_reboot_bootloader)
            val frameParams = binding.btnWriteFrame.layoutParams as LinearLayout.LayoutParams
            frameParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            frameParams.weight = 0f
            frameParams.marginStart = 0
            binding.btnWriteFrame.layoutParams = frameParams
            updaterViewModel.reset()
            updateWriteStatus("")
            rebootToApplication()
        }

        updaterViewModel.input.observe(viewLifecycleOwner) { input ->
            if (input.image is ImageState.Invalid && lastAnnouncedInvalidImage != (input.image).message) {
                val invalidMsg = (input.image).message
                lastAnnouncedInvalidImage = invalidMsg
                Log.e(TAG, "Firmware archive error announced: $invalidMsg")
                errorVibrate()
                showErrorDialog(invalidMsg, title = getString(R.string.error_dialog_title))
            } else if (input.image !is ImageState.Invalid) {
                lastAnnouncedInvalidImage = null
            }
            render()
        }
        updaterViewModel.check.observe(viewLifecycleOwner) { checkState ->
            if (checkState is CheckState.Finished && checkState.report.worst == CheckLevel.FAIL) {
                val failMsg = checkState.report.sections
                    .flatMap { it.lines }
                    .filter { it.level == CheckLevel.FAIL }
                    .joinToString("\n") { it.text }
                    .ifBlank { "Firmware archive validation failed." }
                if (lastAnnouncedFailedCheckReport != failMsg) {
                    lastAnnouncedFailedCheckReport = failMsg
                    Log.e(TAG, "Bootloader check failed: $failMsg")
                    errorVibrate()
                    showErrorDialog(failMsg, title = getString(R.string.error_dialog_title))
                }
            } else if (checkState !is CheckState.Finished) {
                lastAnnouncedFailedCheckReport = null
            }
            render()
        }
        updaterViewModel.flash.observe(viewLifecycleOwner) { flash ->
            if (flash is FlashState.Failed && !flash.announced) {
                flash.announced = true
                Log.e(TAG, "Flash operation failed: ${flash.message}")
                errorVibrate()
                showErrorDialog(flash.message, title = getString(R.string.error_dialog_title))
            }
            render()
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            requireContext().registerReceiver(usbReceiver, filter)
        }

        checkConnectedUsbDevice()
    }

    private fun handleToggleClick() {
        if (isToggling || updaterViewModel.isBusy) return

        if (!isInBootloaderMode) {
            rebootToBootloader()
        } else {
            rebootToApplication()
        }
    }

    private fun confirmFlash() {
        if (updaterViewModel.isBusy) return
        val image = updaterViewModel.input.value?.flashableImage ?: return
        showConfirmationDialog(
            tag = TAG,
            logLabel = "Firmware update",
            title = R.string.nitrokey3updater_flash_title,
            message = getString(R.string.nitrokey3updater_flash_message, image.displayVersion ?: image.fileName),
            confirmText = R.string.nitrokey3updater_flash_confirm,
            onConfirmed = { updaterViewModel.startFlash(requireContext()) },
        )
    }

    private fun render() {
        val b = _binding ?: return
        val input = updaterViewModel.input.value ?: UpdaterInput()
        val flash = updaterViewModel.flash.value ?: FlashState.Idle
        val checkState = updaterViewModel.check.value ?: CheckState.Idle
        val running = flash as? FlashState.Running
        val checking = checkState as? CheckState.Running
        val busy = running != null || checking != null

        val archive = input.archive
        if (archive != null) {
            b.selectedFileText.text = archive.name
            b.sha256sumText.text = formatSha256(archive.sha256)
        } else {
            updateSelectedFileDisplay(null)
        }

        val hasArchive = archive != null
        val hasValidArchive = archive != null && input.image is ImageState.Ready
        val integrityChecked = b.integrityCheckbox.isChecked
        val inBootloader = isInBootloaderMode && hasValidArchive && integrityChecked
        b.integrityCheckbox.isEnabled = !busy && hasArchive && !inBootloader
        b.integrityText.isEnabled = b.integrityCheckbox.isEnabled
        if (!hasArchive) {
            b.integrityCheckbox.isChecked = false
        }
        val testSucceeded = inBootloader && checkState is CheckState.Finished && checkState.report.worst != CheckLevel.FAIL

        b.btnBootloaderCancel.visibility = if (inBootloader) View.VISIBLE else View.GONE

        val frameParams = b.btnWriteFrame.layoutParams as LinearLayout.LayoutParams
        if (inBootloader) {
            frameParams.width = 0
            frameParams.weight = 1f
            frameParams.marginStart = (4 * resources.displayMetrics.density).toInt()
            b.btnWrite.text = getString(
                if (testSucceeded) R.string.nitrokey3updater_write else R.string.nitrokey3updater_test
            )
        } else {
            frameParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            frameParams.weight = 0f
            frameParams.marginStart = 0
            b.btnWrite.text = getString(
                if (isInBootloaderMode) R.string.nitrokey3updater_reboot_app else R.string.nitrokey3updater_reboot_bootloader
            )
        }
        b.btnWriteFrame.layoutParams = frameParams

        if (!inBootloader) {
            if (isInBootloaderMode) {
                b.btnWrite.isEnabled = !busy && !isToggling
            } else {
                val hasSupportedDevice = input.bootloader != null || isNitrokeyAppConnected()
                val integrityChecked = b.integrityCheckbox.isChecked
                b.btnWrite.isEnabled = !busy && !isToggling && hasSupportedDevice && integrityChecked
            }
        } else {
            b.btnBootloaderCancel.isEnabled = !busy && !isToggling
            if (testSucceeded) {
                b.btnWrite.isEnabled = !busy && input.flashableImage != null
            } else {
                b.btnWrite.isEnabled = !busy && !input.replugRequired
            }
        }
        b.selectedFileText.isEnabled = !busy && !inBootloader
        b.root.keepScreenOn = busy

        b.progressWrite.visibility = if (busy || isToggling) View.VISIBLE else View.GONE

        statusText(input, flash, checkState)?.let { msg ->
            val testSucceeded = isInBootloaderMode && checkState is CheckState.Finished && checkState.report.worst != CheckLevel.FAIL
            val isSuccess = flash is FlashState.Succeeded || testSucceeded || isInBootloaderMode
            updateWriteStatus(msg, autoClear = isSuccess)
        }
    }

    private var writeStatusClearJob: Job? = null

    private fun updateWriteStatus(message: String, autoClear: Boolean = false) {
        writeStatusClearJob?.cancel()
        binding.writeStatus.text = message
        Log.d(TAG, "Updating write_status: $message")
        if (autoClear && message.isNotBlank()) {
            writeStatusClearJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(2000.milliseconds)
                if (_binding != null && binding.writeStatus.text == message) {
                    binding.writeStatus.text = ""
                }
            }
        }
    }

    private fun statusText(input: UpdaterInput, flash: FlashState, checkState: CheckState): String? {
        if (isToggling) return null
        if (input.bootloader == null && flash is FlashState.Idle && checkState is CheckState.Idle) return null
        if (isInBootloaderMode && checkState is CheckState.Idle && flash is FlashState.Idle) {
            return "Device ready for testing"
        }
        when (flash) {
            is FlashState.Running -> return when (flash.progress.phase) {
                FlashPhase.CONNECTING -> getString(R.string.nitrokey3updater_status_connecting)
                FlashPhase.SENDING -> getString(
                    R.string.nitrokey3updater_status_installing,
                    flash.progress.percent.coerceAtLeast(0)
                )
                FlashPhase.RESTARTING -> getString(R.string.nitrokey3updater_status_restarting)
            }
            is FlashState.Succeeded -> return getString(
                if (isInBootloaderMode) {
                    R.string.nitrokey3updater_status_done
                } else {
                    R.string.nitrokey3updater_status_done_app_mode
                },
                flash.label
            )
            is FlashState.Failed -> return getString(R.string.nitrokey3updater_status_failed, flash.message)
            is FlashState.Idle -> Unit
        }

        if (checkState is CheckState.Running) {
            return getString(
                if (checkState.longTransfer) {
                    R.string.nitrokey3updater_status_checking_transfer
                } else {
                    R.string.nitrokey3updater_status_checking
                }
            )
        }
        if (input.replugRequired) return getString(R.string.nitrokey3updater_status_replug)

        val bootloader = input.bootloader
        val state = input.image
        val flashable = input.flashableImage

        if (checkState is CheckState.Finished) {
            if (checkState.report.worst != CheckLevel.FAIL && flashable != null) {
                return getString(R.string.nitrokey3updater_tests_completed)
            }
            val summary = getString(
                if (checkState.report.hasProblems) {
                    R.string.nitrokey3updater_status_check_problems
                } else {
                    R.string.nitrokey3updater_status_check_ok
                }
            )
            return if (checkState.report.canTestLongTransfers && input.archive == null) {
                summary + "\n" + getString(R.string.nitrokey3updater_status_check_import_hint)
            } else {
                summary
            }
        }
        return when {
            state is ImageState.Preparing -> getString(R.string.nitrokey3updater_status_preparing)
            state is ImageState.Invalid -> state.message
            bootloader == null -> null
            bootloader.variant == null -> getString(R.string.nitrokey3updater_status_not_nitrokey_bootloader)
            input.archive == null -> getString(R.string.nitrokey3updater_status_import_first)
            flashable != null -> getString(
                R.string.nitrokey3updater_status_ready,
                flashable.displayVersion ?: flashable.fileName
            )
            state is ImageState.Ready && bootloader.variant == Nk3Variant.NRF52 ->
                getString(R.string.nitrokey3updater_status_nrf52_unsupported)
            else -> null
        }
    }

    private fun rebootToBootloader() {
        val yubiKeyDevice = mainViewModel.yubiKey.value
        if (yubiKeyDevice !is UsbYubiKeyDevice) {
            updateWriteStatus("Please connect a Nitrokey 3 via USB first")
            return
        }

        updaterViewModel.clearResult()
        setLoading(true)
        updateWriteStatus("Please touch your Nitrokey 3 to confirm reboot…")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            var errorMsg: String? = null

            try {
                if (yubiKeyDevice.supportsConnection(SmartCardConnection::class.java)) {
                    yubiKeyDevice.openConnection(SmartCardConnection::class.java).use { sc ->
                        val selectApdu = byteArrayOf(0x00, 0xa4.toByte(), 0x04, 0x00, ADMIN_AID.size.toByte()) + ADMIN_AID
                        val selectResp = sc.sendAndReceive(selectApdu)
                        val selectSw = getSw(selectResp)
                        if (selectSw != 0x9000) {
                            throw Exception("Select admin applet failed (SW: %04X)".format(selectSw))
                        }

                        val rebootApdu = byteArrayOf(0x00, INS_ADMIN_REBOOT_TO_BOOTLOADER, 0x00, 0x00)
                        try {
                            val resp = sc.sendAndReceive(rebootApdu)
                            val sw = getSw(resp)
                            if (sw == 0x9000) {
                                success = true
                            } else if (sw == 0x6985) {
                                errorMsg = "Touch confirmation timed out or was rejected"
                            } else {
                                errorMsg = "Reboot rejected by device (SW: %04X)".format(sw)
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "SmartCard reboot exception (expected on reset): ${e.message}")
                            success = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SmartCard reboot failed, attempting CTAP...", e)
                if (errorMsg == null) {
                    errorMsg = e.message
                }
            }

            if (!success && errorMsg != "Touch confirmation timed out or was rejected") {
                try {
                    if (yubiKeyDevice.supportsConnection(FidoConnection::class.java)) {
                        yubiKeyDevice.openConnection(FidoConnection::class.java).use { fc ->
                            val fido = FidoProtocol(fc)
                            val cmdVendor = (0x80 or 0x51).toByte()
                            try {
                                fido.sendAndReceive(cmdVendor, byteArrayOf(0x01), null)
                                success = true
                            } catch (e: Exception) {
                                Log.d(TAG, "CTAP reboot exception (expected on reset): ${e.message}")
                                success = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "CTAP reboot failed", e)
                    if (errorMsg == null) {
                        errorMsg = e.message
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    updateWriteStatus("Device rebooting… Waiting for bootloader mode")
                    mainHandler.postDelayed({ checkConnectedUsbDevice() }, 1500)
                } else {
                    setLoading(false)
                    val err = errorMsg ?: "Failed to reboot device"
                    Log.e(TAG, "rebootToBootloader failed: $err")
                    errorVibrate()
                    showErrorDialog(err, title = getString(R.string.error_dialog_title))
                }
            }
        }
    }

    private fun getSw(resp: ByteArray): Int {
        if (resp.size < 2) return 0
        return ((resp[resp.size - 2].toInt() and 0xFF) shl 8) or
                (resp[resp.size - 1].toInt() and 0xFF)
    }

    private fun rebootToApplication() {
        Log.d(TAG, "rebootToApplication() triggered from UI")
        setLoading(true)
        updateWriteStatus("Sending reset command to reboot into application mode…")
        updaterViewModel.rebootToApplication(requireContext()) { success, errorMsg ->
            Log.d(TAG, "rebootToApplication() result: success=$success, errorMsg=$errorMsg")
            if (success) {
                updateWriteStatus("Reset sent. Waiting for application mode…")
                mainHandler.postDelayed({ checkConnectedUsbDevice() }, 1500)
            } else {
                setLoading(false)
                val err = "Could not reset device: ${errorMsg ?: "Unknown error"}. Please unplug and re-plug your Nitrokey 3."
                Log.e(TAG, "rebootToApplication failed: $err")
                errorVibrate()
                showErrorDialog(err, title = getString(R.string.error_dialog_title))
            }
        }
    }

    private fun checkConnectedUsbDevice() {
        if (!isAdded || _binding == null) return

        val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val devices = usbManager.deviceList.values
        val hadToReplug = updaterViewModel.input.value?.replugRequired == true

        var foundBootloader: UsbDevice? = null
        var foundAppDevice: UsbDevice? = null

        for (device in devices) {
            val vid = device.vendorId
            val pid = device.productId

            if (isBootloaderDevice(vid, pid)) {
                foundBootloader = device
                break
            } else if (vid == VID_NITROKEY && pid == PID_NK3_APP) {
                foundAppDevice = device
            }
        }

        if (foundBootloader != null) {
            isInBootloaderMode = true
            setLoading(false)

            val vid = foundBootloader.vendorId
            val pid = foundBootloader.productId
            val hwType = determineHardwareType(foundBootloader)

            val vidHex = "0x%04X".format(vid)
            val pidHex = "0x%04X".format(pid)

            Log.d(TAG, "checkConnectedUsbDevice(): Bootloader active — $hwType (VID: $vidHex, PID: $pidHex, dev: ${foundBootloader.deviceName})")
            updateDeviceInfo(foundBootloader, isBootloader = true, hwType = hwType)
            updateWriteStatus("Device ready for testing")
            binding.btnWrite.text = getString(R.string.nitrokey3updater_reboot_app)
            binding.btnWrite.isEnabled = !isToggling && !updaterViewModel.isBusy && binding.integrityCheckbox.isChecked

            updaterViewModel.onBootloaderChanged(
                BootloaderDevice(foundBootloader.deviceName, vid, pid, Nk3UsbIds.bootloaderVariant(vid, pid))
            )
        } else if (foundAppDevice != null) {
            val wasInBootloader = isInBootloaderMode
            isInBootloaderMode = false
            setLoading(false)
            updaterViewModel.reset()

            val vid = foundAppDevice.vendorId
            val pid = foundAppDevice.productId
            val vidHex = "0x%04X".format(vid)
            val pidHex = "0x%04X".format(pid)

            Log.d(TAG, "checkConnectedUsbDevice(): Application mode active (VID: $vidHex, PID: $pidHex, wasInBootloader=$wasInBootloader)")
            updateDeviceInfo(foundAppDevice, isBootloader = false, hwType = null)
            binding.btnWrite.text = getString(R.string.nitrokey3updater_reboot_bootloader)
            binding.btnWrite.isEnabled = !isToggling && !updaterViewModel.isBusy && binding.integrityCheckbox.isChecked
            if (wasInBootloader) {
                updateWriteStatus("Application mode confirmed: VID $vidHex, PID $pidHex", autoClear = true)
            } else {
                updateWriteStatus("")
            }
            updaterViewModel.onBootloaderChanged(null)
        } else if (devices.isNotEmpty()) {
            setLoading(false)
            isInBootloaderMode = false
            updaterViewModel.reset()
            val connectedDevice = devices.first()
            updateDeviceInfo(connectedDevice, isBootloader = false, hwType = null)
            updateWriteStatus("Connected device is not a Nitrokey 3")
            binding.btnWrite.isEnabled = false
            binding.btnWrite.text = getString(R.string.nitrokey3updater_reboot_bootloader)
            binding.btnBootloaderCancel.visibility = View.GONE
            val frameParams = binding.btnWriteFrame.layoutParams as LinearLayout.LayoutParams
            frameParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            frameParams.weight = 0f
            frameParams.marginStart = 0
            binding.btnWriteFrame.layoutParams = frameParams
            updaterViewModel.onBootloaderChanged(null)
        } else {
            setLoading(false)
            isInBootloaderMode = false
            updaterViewModel.reset()
            updateDeviceInfo(null, isBootloader = false, hwType = null)
            binding.btnWrite.isEnabled = false
            binding.btnWrite.text = getString(R.string.nitrokey3updater_reboot_bootloader)
            binding.btnBootloaderCancel.visibility = View.GONE
            val frameParams = binding.btnWriteFrame.layoutParams as LinearLayout.LayoutParams
            frameParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            frameParams.weight = 0f
            frameParams.marginStart = 0
            binding.btnWriteFrame.layoutParams = frameParams

            val currentCheck = updaterViewModel.check.value
            if (hadToReplug) {
                updateWriteStatus(getString(R.string.nitrokey3updater_status_replugged))
            } else if (currentCheck !is CheckState.Finished) {
                updateWriteStatus("Please connect a Nitrokey 3 via USB")
            }
            updaterViewModel.onBootloaderChanged(null)
        }

        render()
    }

    private fun isBootloaderDevice(vid: Int, pid: Int): Boolean {
        return (vid == VID_NITROKEY && (pid == PID_NK3_BOOTLOADER_LPC55 || pid == PID_NK3_BOOTLOADER_NRF52)) ||
                (vid == VID_NXP && pid == PID_NXP_DFU) ||
                (vid == VID_NORDIC)
    }

    private fun determineHardwareType(device: UsbDevice): String {
        val vid = device.vendorId
        val pid = device.productId
        val mfg = try { device.manufacturerName ?: "" } catch (_: Exception) { "" }
        val prodName = try { device.productName ?: "" } catch (_: Exception) { "" }

        return when {
            pid == PID_NK3_BOOTLOADER_LPC55 || vid == VID_NXP || pid == PID_NXP_DFU ||
                mfg.contains("NXP", ignoreCase = true) || prodName.contains("LPC55", ignoreCase = true) -> "LPC55 (NXP)"
            pid == PID_NK3_BOOTLOADER_NRF52 || vid == VID_NORDIC ||
                mfg.contains("Nordic", ignoreCase = true) || prodName.contains("nRF52", ignoreCase = true) -> "nRF52 (Nordic)"
            else -> "unknown"
        }
    }

    private fun isNitrokeyAppConnected(): Boolean {
        val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbManager.deviceList.values.any { it.vendorId == VID_NITROKEY && it.productId == PID_NK3_APP }
    }

    private fun setLoading(loading: Boolean) {
        isToggling = loading
        binding.progressWrite.visibility = if (loading || updaterViewModel.isBusy) View.VISIBLE else View.GONE
        val hasSupportedDevice = updaterViewModel.input.value?.bootloader != null || isNitrokeyAppConnected()
        val integrityChecked = binding.integrityCheckbox.isChecked
        binding.btnWrite.isEnabled = !loading && !updaterViewModel.isBusy && hasSupportedDevice && integrityChecked
        binding.btnBootloaderCancel.isEnabled = !loading && !updaterViewModel.isBusy
    }

    private fun errorVibrate() {
        try {
            val vibratorManager = requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.d(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun updateDeviceInfo(device: UsbDevice?, isBootloader: Boolean, hwType: String?) {
        if (device == null) {
            val dash = getString(R.string.dash)
            binding.deviceModel.text = dash
            binding.deviceFirmware.text = dash
            return
        }

        if (isBootloader) {
            binding.deviceModel.text = hwType ?: device.productName ?: "Nitrokey 3"
            binding.deviceFirmware.text = "Bootloader Mode"
            return
        }

        val productName = device.productName?.takeIf { it.isNotBlank() } ?: "USB Device"

        val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as? UsbManager
        val yubiKeyDevice = mainViewModel.yubiKey.value as? UsbYubiKeyDevice 
            ?: (usbManager?.let { runCatching { UsbYubiKeyDevice(it, device) }.getOrNull() })

        if (yubiKeyDevice != null) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                var resolvedName = productName
                var resolvedFirmware: String? = null

                try {
                    if (yubiKeyDevice.supportsConnection(SmartCardConnection::class.java)) {
                        yubiKeyDevice.openConnection(SmartCardConnection::class.java).use { sc ->
                            val nkVersion = Nitrokey3AdminVersion.query(sc)
                            if (nkVersion != null) {
                                resolvedFirmware = nkVersion
                                resolvedName = "Nitrokey 3"
                            } else {
                                val devInfo = DeviceUtil.readInfo(sc, yubiKeyDevice.pid)
                                resolvedFirmware = devInfo.version.toString()
                                resolvedName = DeviceUtil.getName(devInfo, null) ?: productName
                            }
                        }
                    } else if (yubiKeyDevice.supportsConnection(FidoConnection::class.java)) {
                        yubiKeyDevice.openConnection(FidoConnection::class.java).use { fc ->
                            val devInfo = DeviceUtil.readInfo(fc, yubiKeyDevice.pid)
                            resolvedFirmware = devInfo.version.toString()
                            resolvedName = DeviceUtil.getName(devInfo, null) ?: productName
                        }
                    } else if (yubiKeyDevice.supportsConnection(OtpConnection::class.java)) {
                        yubiKeyDevice.openConnection(OtpConnection::class.java).use { oc ->
                            val devInfo = DeviceUtil.readInfo(oc, yubiKeyDevice.pid)
                            resolvedFirmware = devInfo.version.toString()
                            resolvedName = DeviceUtil.getName(devInfo, null) ?: productName
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to read detailed device info: ${e.message}")
                }

                val formattedFirmware = DeviceNameResolver.formatFirmware(resolvedFirmware, null)
                val finalFirmware = if (formattedFirmware.isBlank() || formattedFirmware.equals("unknown", ignoreCase = true)) {
                    "unknown"
                } else {
                    formattedFirmware
                }

                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        binding.deviceModel.text = resolvedName
                        binding.deviceFirmware.text = finalFirmware
                    }
                }
            }
        } else {
            binding.deviceModel.text = productName
            binding.deviceFirmware.text = "unknown"
        }
    }
}
