package acab.naiveha.subrosa.ui.yubiotp

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import acab.naiveha.subrosa.MainViewModel
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentStaticpwdBinding
import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.YubiKeyPromptDialog
import acab.naiveha.subrosa.ui.bindAutoClearStatus
import acab.naiveha.subrosa.ui.showConfirmationDialog
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.android.ui.OtpActivity
import com.yubico.yubikit.android.ui.YubiKeyPromptActivity
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.util.NdefUtils
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.StaticPasswordSlotConfiguration
import com.yubico.yubikit.yubiotp.YubiOtpSession
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class StaticPwdFragment : Fragment() {
    companion object {
        private const val TAG = "StaticPwdFragment"

        private const val MAX_STATIC_PASSWORD_LENGTH = 38

        private const val SLOT_RESET_FILLER_PASSWORD = "Hello kitty"
    }

    private class OtpContract : ActivityResultContract<Unit, Result<ByteArray>?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(context, OtpActivity::class.java)
                .putExtra(YubiKeyPromptActivity.ARG_ALLOW_NFC, false)
        override fun parseResult(resultCode: Int, intent: Intent?): Result<ByteArray>? = when (resultCode) {
            Activity.RESULT_OK -> {
                val scancodes = intent?.getByteArrayExtra(OtpActivity.EXTRA_SCANCODES)
                if (scancodes != null) Result.success(scancodes) else Result.failure(IOException(ERROR_NO_DATA))
            }
            OtpActivity.RESULT_ERROR -> {
                @Suppress("DEPRECATION")
                val error = intent?.getSerializableExtra(OtpActivity.EXTRA_ERROR) as? Throwable
                Result.failure(error ?: IOException(ERROR_READ_FAILED))
            }
            else -> null
        }

        companion object {
            private const val ERROR_NO_DATA = "OtpActivity returned no data"
            private const val ERROR_READ_FAILED = "Failed to read static password"
        }
    }

    private val requestOtp = registerForActivityResult(OtpContract()) { result ->
        if (!isAdded) return@registerForActivityResult
        activityViewModel.setYubiKeyListenerEnabled(true)
        if (result == null) return@registerForActivityResult
        result.fold(
            onSuccess = { scancodes -> decodeAndShow(scancodes) },
            onFailure = { e ->
                viewModel.postResult(Result.failure(e))
            },
        )
    }

    private var pendingReadSlotTwo: Boolean? = null
    private lateinit var readPrompt: YubiKeyPromptDialog

    private fun startRead(slotTwo: Boolean) {
        pendingReadSlotTwo = slotTwo
        val device = activityViewModel.yubiKey.value
        if (device == null) {
            Log.d(TAG, "startRead — no device connected yet, showing prompt")
            readPrompt.setHelpText(getString(R.string.yubikit_prompt_plug_in_or_tap))
            readPrompt.show()
        } else {
            dispatchRead(device)
        }
    }

    private fun dispatchRead(device: YubiKeyDevice) {
        if (pendingReadSlotTwo == null) return
        if (readPrompt.isShowing) readPrompt.dismiss()
        if (device is NfcYubiKeyDevice) {
            onNfcDeviceForRead(device)
        } else {
            Log.d(TAG, "dispatchRead — USB device available, launching OtpActivity")
            pendingReadSlotTwo = null
            activityViewModel.setYubiKeyListenerEnabled(false)
            requestOtp.launch(Unit)
        }
    }

    private fun onNfcDeviceForRead(device: NfcYubiKeyDevice) {
        val slotTwo = pendingReadSlotTwo ?: return
        pendingReadSlotTwo = null
        Log.d(TAG, "onNfcDeviceForRead — reading slot ${if (slotTwo) "TWO" else "ONE"} over NFC")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(activityViewModel.singleDispatcher) {
                runCatching {
                    device.openConnection(SmartCardConnection::class.java).use { connection ->
                        YubiOtpSession(connection)
                            .setNdefConfiguration(if (slotTwo) Slot.TWO else Slot.ONE, null, null)
                    }
                    NdefUtils.getNdefPayloadBytes(device.readNdef())
                }
            }
            result.fold(
                onSuccess = { scancodes ->
                    Log.d(TAG, "onNfcDeviceForRead — NDEF read succeeded (${scancodes.size} bytes)")
                    decodeAndShow(scancodes)
                },
                onFailure = { e ->
                    Log.w(TAG, "onNfcDeviceForRead — failed: ${e.message}")
                    viewModel.postResult(Result.failure(e))
                },
            )
            device.remove {}
        }
    }

    private fun decodeAndShow(scancodes: ByteArray) {
        val password = try {
            Keyboard.decode(scancodes, selectedKeyboard(binding.readKeyboardRadio.checkedRadioButtonId))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "decodeAndShow — Keyboard.decode failed: ${e.message}")
            viewModel.postResult(Result.failure(Exception(describeError(e), e)))
            return
        }
        Log.d(TAG, "decodeAndShow — decoded ${password.length} character password")
        showStaticPasswordDialog(password)
        viewModel.postReadStatus(OtpViewModel.READ_COMPLETE_STATUS)
    }

    private fun requireWithinMaxLength(text: String) {
        if (text.length > MAX_STATIC_PASSWORD_LENGTH) {
            throw IllegalStateException(getString(R.string.otp_static_password_too_long, MAX_STATIC_PASSWORD_LENGTH))
        }
    }

    private val keyboardByRadioId = mapOf(
        R.id.keyoard_us to "en_US", R.id.read_keyoard_us to "en_US",
        R.id.keyoard_uk to "en_UK", R.id.read_keyoard_uk to "en_UK",
        R.id.keyoard_de to "de_DE", R.id.read_keyoard_de to "de_DE",
        R.id.keyoard_fr to "fr_FR", R.id.read_keyoard_fr to "fr_FR",
        R.id.keyoard_it to "it_IT", R.id.read_keyoard_it to "it_IT",
        R.id.keyoard_modhex to "en_MODHEX", R.id.read_keyoard_modhex to "en_MODHEX",
    )
    private fun selectedKeyboard(checkedRadioButtonId: Int): String =
        keyboardByRadioId[checkedRadioButtonId] ?: "en_US"
    private val activityViewModel: MainViewModel by activityViewModels()
    private val viewModel: OtpViewModel by activityViewModels()
    private lateinit var binding: FragmentStaticpwdBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentStaticpwdBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onPause() {
        if (::readPrompt.isInitialized && readPrompt.isShowing) {
            readPrompt.dismiss()
        }
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSaveStaticpwd.isEnabled = false

        if (savedInstanceState == null) {
            binding.editTextStaticpwdId.requestFocus()
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state == null) return@observe
            updateButtonStates()
            updateProgressVisibility(state)
        }

        viewModel.pendingAction.observe(viewLifecycleOwner) { action ->
            val state = viewModel.uiState.value ?: return@observe
            updateProgressVisibility(state, action != null)
            updateButtonStates(action != null)

            if (action == null && state.currentOperation != OtpOperation.NONE) {
                viewModel.setCurrentOperation(OtpOperation.NONE)
            }
        }

        readPrompt = YubiKeyPromptDialog(requireContext()) { pendingReadSlotTwo = null }
        activityViewModel.yubiKey.observe(viewLifecycleOwner) { device ->
            if (device != null && pendingReadSlotTwo != null) {
                dispatchRead(device)
            }
            val usbConnected = device is UsbYubiKeyDevice
            binding.readRadioSlot1.isEnabled = !usbConnected
            binding.readRadioSlot2.isEnabled = !usbConnected
        }

        viewModel.clearUiTrigger.observe(viewLifecycleOwner) { shouldClear ->
            if (shouldClear) {
                binding.editTextStaticpwdId.setText("")
                binding.btnSaveStaticpwd.isEnabled = false
                binding.extrasTabFront.isChecked = false
                binding.extrasTabEnd.isChecked = false
                binding.extrasCr.isChecked = false
                binding.checkboxShowPwdId.isChecked = false
                binding.keyboardRadio.check(R.id.keyoard_us)
                binding.slotRadio.check(R.id.radio_slot_1)
                binding.slotRadioReset.check(R.id.reset_slot_1)
            }
        }

        binding.checkboxShowPwdId.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.editTextStaticpwdId.transformationMethod = HideReturnsTransformationMethod.getInstance()
                binding.editTextStaticpwdId.setSelection(binding.editTextStaticpwdId.text?.length ?: 0)
            } else {
                binding.editTextStaticpwdId.transformationMethod = PasswordTransformationMethod.getInstance()
                binding.editTextStaticpwdId.setSelection(binding.editTextStaticpwdId.text?.length ?: 0)
            }
        }
        binding.textLayoutStaticpwdId.setEndIconOnClickListener {
            if (binding.editTextStaticpwdId.text.toString().isNotEmpty()){
                binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_content_paste_24dp)
                binding.editTextStaticpwdId.setText("")
                binding.editTextStaticpwdId.requestFocus()
                WindowCompat.getInsetsController(requireActivity().window, binding.editTextStaticpwdId).show(WindowInsetsCompat.Type.ime())
            } else {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.text?.let { pasted ->
                    runValidated(viewModel) {
                        requireWithinMaxLength(pasted.toString())
                        binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_cancel_24dp)
                        binding.editTextStaticpwdId.append(pasted)
                        WindowCompat.getInsetsController(requireActivity().window, binding.editTextStaticpwdId).hide(WindowInsetsCompat.Type.ime())
                        binding.editTextStaticpwdId.setSelection(binding.editTextStaticpwdId.text?.length ?: 0)
                        clipboard.clearPrimaryClip()
                    }
                }
            }
        }
        binding.editTextStaticpwdId.addTextChangedListener(object: TextWatcher{
            override fun afterTextChanged(s: Editable?) {
                val staticpwd = s?.toString() ?: ""
                val busy = viewModel.pendingAction.value != null
                binding.btnSaveStaticpwd.isEnabled = !busy && staticpwd.isNotEmpty()
                if (staticpwd.isEmpty()) {
                    binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_content_paste_24dp)
                    binding.textLayoutStaticpwdId.hint = getString(R.string.otp_yubistatic_id)
                } else {
                    binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_cancel_24dp)
                    binding.textLayoutStaticpwdId.hint = getString(R.string.otp_yubistatic_hint)
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }
        })
        binding.keyboardRadio.setOnCheckedChangeListener { _, checkedId ->
            hideIme()
            val extrasEnabled = checkedId != R.id.keyoard_modhex
            binding.extrasTabFront.isChecked = false
            binding.extrasTabFront.isEnabled = extrasEnabled
            binding.extrasTabEnd.isChecked = false
            binding.extrasTabEnd.isEnabled = extrasEnabled
            binding.extrasCr.isChecked = false
            binding.extrasCr.isEnabled = extrasEnabled
        }
        binding.extrasTabFront.hideImeOnClick()
        binding.extrasTabEnd.hideImeOnClick()
        binding.extrasCr.hideImeOnClick()
        binding.slotRadio.setOnCheckedChangeListener { _, _ -> hideIme() }

        bindAutoClearStatus(
            viewModel.writeStatus, binding.saveStatus,
            OtpViewModel.slotProgrammedStatus(Slot.ONE), OtpViewModel.slotProgrammedStatus(Slot.TWO),
        ) { viewModel.postWriteStatus(it) }
        viewModel.writeStatus.observe(viewLifecycleOwner) { message ->
            if (message.isNotEmpty()) binding.editTextStaticpwdId.setText("")
        }

        bindAutoClearStatus(
            viewModel.readStatus, binding.readStatus, OtpViewModel.READ_COMPLETE_STATUS,
        ) { viewModel.postReadStatus(it) }

        bindAutoClearStatus(
            viewModel.resetStatus, binding.deleteStatus,
            OtpViewModel.slotResetStatus(Slot.ONE), OtpViewModel.slotResetStatus(Slot.TWO),
        ) { viewModel.postResetStatus(it) }

        binding.btnSaveStaticpwd.setOnClickListener {
            if (rejectIfUnsupportedDeviceConnected()) return@setOnClickListener
            runValidated(viewModel) {
                val keyboard = selectedKeyboard(binding.keyboardRadio.checkedRadioButtonId)
                var staticpwd = binding.editTextStaticpwdId.text.toString()
                requireWithinMaxLength(staticpwd)
                if (binding.extrasTabFront.isChecked){
                    staticpwd = '\t' + staticpwd
                }
                if (binding.extrasTabEnd.isChecked){
                    staticpwd += '\t'
                }
                val scancodes = Keyboard.encode(staticpwd, keyboard)
                val configuration = StaticPasswordSlotConfiguration(scancodes)
                configuration.appendCr(binding.extrasCr.isChecked)
                val slot = resolveSlot(binding.slotRadio.checkedRadioButtonId, R.id.radio_slot_1, R.id.radio_slot_2)
                Log.d(TAG, "btnSaveStaticpwd — queuing program of slot $slot (${staticpwd.length} chars, keyboard=$keyboard)")
                viewModel.setCurrentOperation(OtpOperation.SAVE)
                viewModel.pendingAction.value = {
                    Log.i(TAG, "pendingAction — programming slot $slot")
                    putConfiguration(slot, configuration, null, null)
                    Log.i(TAG, "pendingAction — slot $slot programmed")
                    viewModel.postWriteStatus(OtpViewModel.slotProgrammedStatus(slot))
                    null
                }
            }
        }
        binding.btnRequestStaticpwd.setOnClickListener {
            if (rejectIfUnsupportedDeviceConnected()) return@setOnClickListener
            hideIme()
            binding.editTextStaticpwdId.clearFocus()
            val slotTwo = binding.readSlotRadio.checkedRadioButtonId == R.id.read_radio_slot_2
            Log.d(TAG, "btnRequestStaticpwd — starting read of slot ${if (slotTwo) "TWO" else "ONE"}")
            viewModel.setCurrentOperation(OtpOperation.READ)
            startRead(slotTwo)
        }
        binding.btnDeleteStaticpwd.setOnClickListener {
            if (rejectIfUnsupportedDeviceConnected()) return@setOnClickListener
            runValidated(viewModel) {
                val slot = resolveSlot(binding.slotRadioReset.checkedRadioButtonId, R.id.reset_slot_1, R.id.reset_slot_2)
                showStaticPasswordResetConfirmationDialog(slot)
            }
        }
    }

    private fun updateProgressVisibility(state: OtpUiState, isBusy: Boolean? = null) {
        val busy = isBusy ?: (viewModel.pendingAction.value != null)
        val op = state.currentOperation
        binding.progressSave.visibility = if (busy && op == OtpOperation.SAVE) View.VISIBLE else View.GONE
        binding.progressRead.visibility = if (busy && op == OtpOperation.READ) View.VISIBLE else View.GONE
        binding.progressWipe.visibility = if (busy && op == OtpOperation.RESET) View.VISIBLE else View.GONE
    }

    private fun updateButtonStates(isBusy: Boolean? = null) {
        val busy = isBusy ?: (viewModel.pendingAction.value != null)
        binding.btnSaveStaticpwd.isEnabled = !busy && binding.editTextStaticpwdId.text.toString().isNotEmpty()
        binding.btnRequestStaticpwd.isEnabled = !busy
        binding.btnDeleteStaticpwd.isEnabled = !busy
    }

    private fun hideIme() {
        WindowCompat.getInsetsController(requireActivity().window, binding.editTextStaticpwdId).hide(WindowInsetsCompat.Type.ime())
    }

    private fun View.hideImeOnClick() = setOnClickListener { hideIme() }

    private fun showStaticPasswordDialog(password: String) {
        val context = context ?: return

        val text = buildString {
            appendLine(password.replace("\u0000", ""))
        }

        val builder = MaterialAlertDialogBuilder(context)
        val themedContext = builder.context
        val view = LayoutInflater.from(themedContext).inflate(R.layout.dialog_static_password, null)
        
        val tv = view.findViewById<TextView>(R.id.text_static_password)
        val focusCatcher = view.findViewById<View>(R.id.focus_catcher)
        
        tv.text = text

        builder.setTitle(R.string.otp_yubistatic)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .apply {
                focusCatcher.requestFocus()
            }
    }

    private fun showStaticPasswordResetConfirmationDialog(slot: Slot) {
        val slotLabel = getString(if (slot == Slot.ONE) R.string.staticpwd_slot_1 else R.string.staticpwd_slot_2)
        showConfirmationDialog(
            tag         = TAG,
            logLabel    = "Static password slot reset",
            title       = R.string.staticpwd_reset_title,
            message     = getString(R.string.staticpwd_reset_message, slotLabel),
            confirmText = R.string.staticpwd_reset_confirm,
            onConfirmed = {
                runValidated(viewModel) {
                    val staticpwd = SLOT_RESET_FILLER_PASSWORD
                    val keyboard = "en_US"
                    val scancodes = Keyboard.encode(staticpwd, keyboard)
                    val configuration = StaticPasswordSlotConfiguration(scancodes)
                    Log.d(TAG, "onConfirmed — queuing reset of slot $slot")
                    viewModel.setCurrentOperation(OtpOperation.RESET)
                    viewModel.pendingAction.value = {
                        Log.i(TAG, "pendingAction — resetting slot $slot")
                        putConfiguration(slot, configuration, null, null)
                        Log.i(TAG, "pendingAction — slot $slot reset")
                        viewModel.postResetStatus(OtpViewModel.slotResetStatus(slot))
                        null
                    }
                }
            },
        )
    }

    private fun rejectIfUnsupportedDeviceConnected(): Boolean {
        val device = activityViewModel.yubiKey.value
        if (PgpDeviceType.isUsbNitrokey(device) || PgpDeviceType.isUsbGnuk(device)) {
            viewModel.postResult(Result.failure(Exception(OtpViewModel.STATIC_PASSWORDS_NOT_SUPPORTED)))
            return true
        }
        return false
    }
}