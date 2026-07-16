package acab.naiveha.subrosa.ui.yubiotp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
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
import acab.naiveha.subrosa.ui.YubiKeyPromptDialog
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.ui.OtpActivity
import com.yubico.yubikit.android.ui.YubiKeyPromptActivity
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.util.NdefUtils
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.StaticPasswordSlotConfiguration
import com.yubico.yubikit.yubiotp.YubiOtpSession
import androidx.core.content.ContextCompat
import android.os.VibratorManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import java.io.IOException

class StaticPwdFragment : Fragment() {
    private class OtpContract : ActivityResultContract<Unit, Result<String>?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(context, OtpActivity::class.java)
                .putExtra(YubiKeyPromptActivity.ARG_ALLOW_NFC, false)
        override fun parseResult(resultCode: Int, intent: Intent?): Result<String>? = when (resultCode) {
            Activity.RESULT_OK -> {
                val otp = intent?.getStringExtra(OtpActivity.EXTRA_OTP)
                if (otp != null) Result.success(otp) else Result.failure(IOException("OtpActivity returned no data"))
            }
            OtpActivity.RESULT_ERROR -> {
                @Suppress("DEPRECATION")
                val error = intent?.getSerializableExtra(OtpActivity.EXTRA_ERROR) as? Throwable
                Result.failure(error ?: IOException("Failed to read static password"))
            }
            else -> null
        }
    }

    private val requestOtp = registerForActivityResult(OtpContract()) { result ->
        if (!isAdded) return@registerForActivityResult
        activityViewModel.setYubiKeyListenerEnabled(true)
        if (result == null) return@registerForActivityResult
        result.fold(
            onSuccess = { password ->
                showStaticPasswordDialog(password)
                viewModel.postReadStatus("Read complete")
            },
            onFailure = { e ->
                viewModel.postResult(Result.failure(e))
            },
        )
    }

    private var pendingReadSlotTwo: Boolean? = null
    private lateinit var readPrompt: YubiKeyPromptDialog

    private fun startRead(slotTwo: Boolean) {
        pendingReadSlotTwo = slotTwo
        when (val device = activityViewModel.yubiKey.value) {
            is NfcYubiKeyDevice -> onNfcDeviceForRead(device)
            null -> {
                readPrompt.setHelpText(getString(R.string.yubikit_prompt_plug_in_or_tap))
                readPrompt.show()
            }
            else -> {
                pendingReadSlotTwo = null
                activityViewModel.setYubiKeyListenerEnabled(false)
                requestOtp.launch(Unit)
            }
        }
    }

    private fun onNfcDeviceForRead(device: NfcYubiKeyDevice) {
        val slotTwo = pendingReadSlotTwo ?: return
        pendingReadSlotTwo = null
        if (readPrompt.isShowing) readPrompt.dismiss()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(activityViewModel.singleDispatcher) {
                runCatching {
                    device.openConnection(SmartCardConnection::class.java).use { connection ->
                        YubiOtpSession(connection)
                            .setNdefConfiguration(if (slotTwo) Slot.TWO else Slot.ONE, null, null)
                    }
                    val scancodes = NdefUtils.getNdefPayloadBytes(device.readNdef())
                    Keyboard().decode(scancodes, selectedKeyboard(binding.readKeyboardRadio.checkedRadioButtonId))
                }
            }
            result.fold(
                onSuccess = { password ->
                    showStaticPasswordDialog(password)
                    viewModel.postReadStatus("Read complete")
                },
                onFailure = { e ->
                    viewModel.postResult(Result.failure(e))
                },
            )
            device.remove {}
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

    private var saveStatusClearJob: Job? = null
    private var readStatusClearJob: Job? = null
    private var deleteStatusClearJob: Job? = null

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

        readPrompt = YubiKeyPromptDialog(requireContext()) { pendingReadSlotTwo = null }
        activityViewModel.yubiKey.observe(viewLifecycleOwner) { device ->
            if (device is NfcYubiKeyDevice && pendingReadSlotTwo != null) {
                onNfcDeviceForRead(device)
            }
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
                clipboard.primaryClip?.getItemAt(0)?.text?.let {
                    try{
                        if (it.length > 38){
                            throw IllegalStateException("Static password cannot exceed 38 characters")
                        } else {
                            binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_cancel_24dp)
                            binding.editTextStaticpwdId.append(it)
                            WindowCompat.getInsetsController(requireActivity().window, binding.editTextStaticpwdId).hide(WindowInsetsCompat.Type.ime())
                            binding.editTextStaticpwdId.setSelection(binding.editTextStaticpwdId.text?.length ?: 0)
                            clipboard.setPrimaryClip(ClipData.newPlainText("", "Hello kitty"))
                        }
                    } catch (e: Exception) {
                        viewModel.postResult(Result.failure(e))
                    }
                }
            }
        }
        binding.editTextStaticpwdId.addTextChangedListener(object: TextWatcher{
            override fun afterTextChanged(s: Editable?) {
                val staticpwd = s?.toString() ?: ""
                binding.btnSaveStaticpwd.isEnabled = staticpwd.isNotEmpty()
                if (staticpwd.isEmpty()) {
                    binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_content_paste_24dp)
                    binding.textLayoutStaticpwdId.hint = "Type or paste from clipboard"
                } else {
                    binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_cancel_24dp)
                    binding.textLayoutStaticpwdId.hint = "Static password"
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

        viewModel.saveStatus.observe(viewLifecycleOwner) { message ->
            saveStatusClearJob?.cancel()
            binding.saveStatus.text = message
            if (message.isNotEmpty()) {
                binding.editTextStaticpwdId.setText("")
                saveStatusClearJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(2000.milliseconds)
                    viewModel.postSaveStatus("")
                }
            }
        }

        viewModel.readStatus.observe(viewLifecycleOwner) { message ->
            readStatusClearJob?.cancel()
            binding.readStatus.text = message
            if (message.isNotEmpty()) {
                readStatusClearJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(2000.milliseconds)
                    viewModel.postReadStatus("")
                }
            }
        }

        viewModel.deleteStatus.observe(viewLifecycleOwner) { message ->
            deleteStatusClearJob?.cancel()
            binding.deleteStatus.text = message
            if (message.isNotEmpty()) {
                deleteStatusClearJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(2000.milliseconds)
                    viewModel.postDeleteStatus("")
                }
            }
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            if (result.isFailure) {
                getVibrator().vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        binding.btnSaveStaticpwd.setOnClickListener {
            if (rejectIfNitrokeyConnected()) return@setOnClickListener
            try {
                val keyboard = selectedKeyboard(binding.keyboardRadio.checkedRadioButtonId)
                var staticpwd = binding.editTextStaticpwdId.text.toString()
                if (staticpwd.length > 38){
                    throw IllegalStateException("Static password cannot exceed 38 characters")
                }
                if (binding.extrasTabFront.isChecked){
                    staticpwd = '\t' + staticpwd
                }
                if (binding.extrasTabEnd.isChecked){
                    staticpwd += '\t'
                }
                val scancodes = Keyboard().encode(staticpwd, keyboard)
                val configuration = StaticPasswordSlotConfiguration(scancodes)
                if (binding.extrasCr.isChecked){
                    configuration.appendCr(true)
                } else {
                    configuration.appendCr(false)
                }
                val slot = when (binding.slotRadio.checkedRadioButtonId) {
                    R.id.radio_slot_1 -> Slot.ONE
                    R.id.radio_slot_2 -> Slot.TWO
                    else -> Slot.ONE
                }
                viewModel.pendingAction.value = {
                    putConfiguration(slot, configuration, null, null)
                    viewModel.postSaveStatus("Slot $slot programmed")
                    null
                }
            } catch (e: Exception) {
                viewModel.postResult(Result.failure(e))
            }
        }
        binding.btnRequestStaticpwd.setOnClickListener {
            if (rejectIfNitrokeyConnected()) return@setOnClickListener
            val slotTwo = binding.readSlotRadio.checkedRadioButtonId == R.id.read_radio_slot_2
            startRead(slotTwo)
        }
        binding.btnDeleteStaticpwd.setOnClickListener {
            if (rejectIfNitrokeyConnected()) return@setOnClickListener
            val slot = when (binding.slotRadioReset.checkedRadioButtonId) {
                R.id.reset_slot_1 -> Slot.ONE
                R.id.reset_slot_2 -> Slot.TWO
                else -> Slot.ONE
            }
            showStaticPasswordResetConfirmationDialog(slot)
        }
    }

    private fun hideIme() {
        WindowCompat.getInsetsController(requireActivity().window, binding.editTextStaticpwdId).hide(WindowInsetsCompat.Type.ime())
    }

    private fun View.hideImeOnClick() = setOnClickListener { hideIme() }

    private fun getVibrator(): Vibrator {
        val vibratorManager = requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        return vibratorManager.defaultVibrator
    }

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
        val slotLabel = if (slot == Slot.ONE) "Slot 1" else "Slot 2"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.staticpwd_reset_title)
            .setMessage(getString(R.string.staticpwd_reset_message, slotLabel))
            .setPositiveButton(R.string.staticpwd_reset_confirm) { _, _ ->
                try {
                    val staticpwd = "Hello kitty"
                    val keyboard = "en_US"
                    val scancodes = Keyboard().encode(staticpwd, keyboard)
                    val configuration = StaticPasswordSlotConfiguration(scancodes)
                    viewModel.pendingAction.value = {
                        putConfiguration(slot, configuration, null, null)
                        viewModel.postDeleteStatus("Slot $slot reset")
                        null
                    }
                } catch (e: Exception) {
                    viewModel.postResult(Result.failure(e))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rejectIfNitrokeyConnected(): Boolean {
        if (OtpViewModel.isUsbNitrokey(activityViewModel.yubiKey.value)) {
            viewModel.postResult(Result.failure(Exception(OtpViewModel.NITROKEY_NOT_SUPPORTED_MESSAGE)))
            return true
        }
        return false
    }
}