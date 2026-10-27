package acab.naiveha.subrosa.ui.yubiotp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import kotlin.math.abs
import kotlin.math.roundToInt
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.activityViewModels
import acab.naiveha.subrosa.MainViewModel
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentYubiotpBinding
import acab.naiveha.subrosa.ui.PgpDeviceType
import acab.naiveha.subrosa.ui.YubiKeyFragment
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

class YubiOtpFragment : YubiKeyFragment<YubiOtpSession, YubiOtpViewModel>() {
    companion object {
        private const val TAG = "YubiOtpFragment"

        private const val MAX_STATIC_PASSWORD_LENGTH = 38
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
        val slotTwo = lastUsbReadSlotTwo
        lastUsbReadSlotTwo = null
        if (result == null) {
            Log.d(TAG, "requestOtp — OtpActivity returned no result (cancelled)")
            return@registerForActivityResult
        }
        result.fold(
            onSuccess = { scancodes ->
                Log.d(TAG, "requestOtp — OtpActivity succeeded (${scancodes.size} scancode bytes)")
                decodeAndShow(scancodes)
            },
            onFailure = { e ->
                Log.w(TAG, "requestOtp — OtpActivity failed (read-slot radio showed " +
                    "${if (slotTwo == true) "TWO" else if (slotTwo == false) "ONE" else "?"}): ${e.message}", e)
                if (!isAnySlotProgrammed()) {
                    Log.d(TAG, "requestOtp — neither slot programmed, reporting as such instead of raw error")
                    viewModel.postResult(Result.failure(Exception(OtpNfcConfig.noSlotConfigured(requireContext()))))
                } else {
                    viewModel.postResult(Result.failure(e))
                }
            },
        )
    }

    private var pendingReadSlotTwo: Boolean? = null
    private var lastUsbReadSlotTwo: Boolean? = null
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

    private fun isAnySlotProgrammed(): Boolean {
        val state = viewModel.uiState.value
        if (state == null) {
            Log.d(TAG, "isAnySlotProgrammed — no cached uiState yet, assuming programmed")
            return true
        }
        Log.d(TAG, "isAnySlotProgrammed — slotOne=${state.slotOne} slotTwo=${state.slotTwo}")
        return !(state.slotOne == SlotState.EMPTY && state.slotTwo == SlotState.EMPTY)
    }

    private fun SlotState.toDisplayLabel(): String = when (this) {
        SlotState.PROGRAMMED -> getString(R.string.otp_slot_state_programmed)
        SlotState.EMPTY -> getString(R.string.otp_slot_state_empty)
        SlotState.UNKNOWN -> getString(R.string.otp_slot_state_unknown)
    }

    private fun dispatchRead(device: YubiKeyDevice) {
        val slotTwo = pendingReadSlotTwo ?: return
        if (readPrompt.isShowing) readPrompt.dismiss()
        if (device is NfcYubiKeyDevice) {
            onNfcDeviceForRead(device)
        } else {
            Log.d(TAG, "dispatchRead — USB device available, launching OtpActivity for slot ${if (slotTwo) "TWO" else "ONE"}")
            lastUsbReadSlotTwo = slotTwo
            pendingReadSlotTwo = null
            activityViewModel.setYubiKeyListenerEnabled(false)
            requestOtp.launch(Unit)
        }
    }

    private fun onNfcDeviceForRead(device: NfcYubiKeyDevice) {
        val slotTwo = pendingReadSlotTwo ?: return
        pendingReadSlotTwo = null
        val slot = if (slotTwo) Slot.TWO else Slot.ONE
        Log.d(TAG, "onNfcDeviceForRead — reading slot ${if (slotTwo) "TWO" else "ONE"} over NFC")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(activityViewModel.singleDispatcher) {
                runCatching {
                    device.openConnection(SmartCardConnection::class.java).use { connection ->
                        val session = YubiOtpSession(connection)
                        val configured = session.configurationState.isConfigured(slot)
                        Log.d(TAG, "onNfcDeviceForRead — freshly-tapped device reports slot $slot configured=$configured")
                        if (!configured) {
                            throw Exception(OtpNfcConfig.slotNotProgrammed(requireContext()))
                        }
                        session.setNdefConfiguration(slot, null, null)
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
                    Log.w(TAG, "onNfcDeviceForRead — failed: ${e.message}", e)
                    viewModel.postResult(Result.failure(e))
                },
            )
            device.remove {}
        }
    }

    private fun decodeAndShow(scancodes: ByteArray) {
        val password = try {
            Keyboard.decode(scancodes, selectedKeyboard(binding.readKeyboardRadio.checkedChipId))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "decodeAndShow — Keyboard.decode failed: ${e.message}")
            viewModel.postResult(Result.failure(Exception(describeError(e), e)))
            return
        }
        Log.d(TAG, "decodeAndShow — decoded ${password.size} character password")
        showStaticPasswordDialog(password)
        viewModel.postReadStatus(OtpNfcConfig.readCompleteStatus(requireContext()))
    }

    private fun requireWithinMaxLength(length: Int) {
        if (length > MAX_STATIC_PASSWORD_LENGTH) {
            throw IllegalStateException(getString(R.string.otp_static_password_too_long, MAX_STATIC_PASSWORD_LENGTH))
        }
    }

    private fun requireWithinMaxLength(text: String) = requireWithinMaxLength(text.length)

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
        override val viewModel: YubiOtpViewModel by activityViewModels()
    private lateinit var binding: FragmentYubiotpBinding

    override fun isYubiKeyTapSuspended(): Boolean = pendingReadSlotTwo != null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentYubiotpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onPause() {
        if (::readPrompt.isInitialized && readPrompt.isShowing) {
            readPrompt.dismiss()
        }
        super.onPause()
    }

    private var nfcDotAnimationJob: Job? = null

    private fun startNfcDotAnimation(baseText: String) {
        if (nfcDotAnimationJob?.isActive == true) return
        nfcDotAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            val steps = arrayOf("", ".", "..", "...")
            var step = 0
            while (isActive) {
                binding.mgmtDesc.text = StringBuilder(baseText).append(steps[step]).toString()
                delay(500.milliseconds)
                step = (step + 1) % steps.size
            }
        }
    }

    private fun stopNfcDotAnimation() {
        nfcDotAnimationJob?.cancel()
        nfcDotAnimationJob = null
    }

    override fun onDestroyView() {
        stopNfcDotAnimation()
        viewModel.setMgmtTabActive(false)
        if (!requireActivity().isChangingConfigurations) {
            viewModel.requestClearUi()
        }
        super.onDestroyView()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSaveStaticpwd.isEnabled = false

        if (savedInstanceState == null) {
            binding.editTextStaticpwdId.requestFocus()
        }

        var currentTabIndex = 1
        var currentAnimator: ValueAnimator? = null
        viewModel.setMgmtTabActive(false)

        fun updateDots(selectedIndex: Int) {
            binding.tabEraseIcon.setImageResource(if (selectedIndex == 0) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
            binding.tabWriteIcon.setImageResource(if (selectedIndex == 1) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
            binding.tabReadIcon.setImageResource(if (selectedIndex == 2) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
//            binding.tabMgmtIcon.setImageResource(if (selectedIndex == 3) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
            binding.tabMgmt.visibility = View.GONE
        }

        val sections = listOf(binding.sectionErase, binding.sectionWrite, binding.sectionRead, binding.sectionMgmt)

        fun applySectionLayout(effectiveIndex: Float, isAnimating: Boolean = false) {
            val isWideTablet = resources.configuration.smallestScreenWidthDp >= 600
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val isCarouselMode = isWideTablet || isLandscape

            val screenWidth = resources.displayMetrics.widthPixels.toFloat()
            val density = resources.displayMetrics.density
            val cardSpacingPx = density * 460f
            val sectionWidthPx = if (isCarouselMode) (density * 448f).roundToInt() else ViewGroup.LayoutParams.MATCH_PARENT

//            val activeDot = effectiveIndex.roundToInt().coerceIn(0, 3)
            val activeDot = effectiveIndex.roundToInt().coerceIn(0, 2)
            updateDots(activeDot)

            val activeSection = sections[activeDot]
            activeSection.measure(
                View.MeasureSpec.makeMeasureSpec(binding.tabContentContainer.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            binding.yubiotpScrollView.activeTabHeight = activeSection.measuredHeight
            activeSection.requestLayout()

            sections.forEachIndexed { i, view ->
                //TODO remove this
                if (i == 3) {
                    view.visibility = View.GONE
                    return@forEachIndexed
                }
                val lp = view.layoutParams
                if (lp != null && lp.width != sectionWidthPx) {
                    lp.width = sectionWidthPx
                    view.layoutParams = lp
                    view.requestLayout()
                }

                val dist = i - effectiveIndex
                val absDist = abs(dist)

                if (isCarouselMode) {
                    val maxVisibleDist = 1.8f
                    if (absDist < maxVisibleDist) {
                        view.visibility = View.VISIBLE
                        view.translationX = dist * cardSpacingPx
                        val scale = (0.85f + (1.0f - absDist.toDouble().coerceIn(0.0, 1.0)) * 0.15f).toFloat()
                        view.scaleX = scale
                        view.scaleY = scale
                        view.alpha = (1.0f - (absDist * 0.5f)).coerceIn(0f, 1f)
                    } else {
                        view.visibility = View.GONE
                        view.translationX = 0f
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.alpha = 1f
                    }
                } else {
                    if (isAnimating) {
                        if (absDist < 1.0f) {
                            view.visibility = View.VISIBLE
                            view.translationX = dist * screenWidth
                            view.scaleX = 1f
                            view.scaleY = 1f
                            view.alpha = (1.0f - absDist).coerceIn(0f, 1f)
                        } else {
                            view.visibility = View.GONE
                            view.translationX = 0f
                            view.scaleX = 1f
                            view.scaleY = 1f
                            view.alpha = 1f
                        }
                    } else {
                        val targetIndex = effectiveIndex.roundToInt().coerceIn(0, 3)
                        if (i == targetIndex) {
                            view.visibility = View.VISIBLE
                            view.translationX = 0f
                            view.scaleX = 1f
                            view.scaleY = 1f
                            view.alpha = 1f
                        } else {
                            view.visibility = View.GONE
                            view.translationX = 0f
                            view.scaleX = 1f
                            view.scaleY = 1f
                            view.alpha = 1f
                        }
                    }
                }
            }
        }

//        fun navigateToTab(targetIndex: Int) {
        fun navigateToTab(targetIndexRaw: Int) {
            val targetIndex = targetIndexRaw.coerceIn(0, 2)
            if (targetIndex == currentTabIndex) return

            currentAnimator?.cancel()
//            viewModel.setMgmtTabActive(targetIndex == 3)
            viewModel.setMgmtTabActive(false)

            val fromIndex = currentTabIndex
            val steps = targetIndex - fromIndex

            val totalDuration = if (abs(steps) > 1) 360L else 220L

            val animator = ValueAnimator.ofFloat(0f, steps.toFloat())
            animator.duration = totalDuration
            animator.interpolator = AccelerateDecelerateInterpolator()

            animator.addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                val effectiveIndex = fromIndex + progress
                applySectionLayout(effectiveIndex, isAnimating = true)
            }

            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentTabIndex = targetIndex
                    applySectionLayout(targetIndex.toFloat(), isAnimating = false)
                    if (binding.yubiotpScrollView.scrollY > 0) {
                        val scrollAnim = ObjectAnimator.ofInt(binding.yubiotpScrollView, "scrollY", binding.yubiotpScrollView.scrollY, 0)
                        scrollAnim.duration = totalDuration
                        scrollAnim.interpolator = AccelerateDecelerateInterpolator()
                        scrollAnim.start()
                    }
                    currentAnimator = null
                }
            })

            currentAnimator = animator
            animator.start()
        }

        binding.tabErase.setOnClickListener { navigateToTab(0) }
        binding.tabWrite.setOnClickListener { navigateToTab(1) }
        binding.tabRead.setOnClickListener { navigateToTab(2) }
//        binding.tabMgmt.setOnClickListener { navigateToTab(3) }
        binding.tabMgmt.setOnClickListener { }
        var renderingMgmt = false

        fun renderMgmt(state: YubiOtpUiState, isBusy: Boolean? = null) {
            val busy = isBusy ?: (viewModel.pendingAction.value != null)
            val nfc = state.nfc
            val rowVisible = nfc == null || (nfc.applicable && nfc.hasNfc && nfc.otpSupported)
            val note: Int? = when {
                nfc == null -> R.string.nfc_static_password_desc
                !nfc.applicable -> R.string.otp_nfc_reason_unsupported
                !nfc.hasNfc -> R.string.otp_nfc_reason_no_nfc
                !nfc.otpSupported -> R.string.otp_nfc_reason_no_otp
                !nfc.canConfigure -> R.string.otp_nfc_reason_old_firmware
                nfc.locked -> R.string.otp_nfc_reason_locked
                nfc.otpOverNfc == OtpNfcState.UNKNOWN -> R.string.otp_nfc_reason_unreadable
                nfc.otpOverNfc == OtpNfcState.ENABLED -> R.string.otp_nfc_on_note
                nfc.otpOverNfc == OtpNfcState.DISABLED -> R.string.otp_nfc_off_note
                else -> R.string.nfc_static_password_desc
            }
            val known = if (nfc != null && rowVisible) nfc.otpOverNfc else OtpNfcState.UNKNOWN

            val switchLabel = getString(R.string.otp_nfc_switch_label)
            val hintConnect = getString(R.string.otp_nfc_hint_connect)
            if (known == OtpNfcState.UNKNOWN) {
                startNfcDotAnimation("$switchLabel\n$hintConnect")
            } else {
                stopNfcDotAnimation()
                binding.mgmtDesc.text = switchLabel
            }

            val switchOn = state.nfcRequested ?: (known == OtpNfcState.ENABLED)

            val elementsEnabled = (known != OtpNfcState.UNKNOWN) && !busy

            renderingMgmt = true
            binding.mgmtNfcSwitch.isChecked = switchOn
            renderingMgmt = false

            binding.mgmtNfcSwitch.isEnabled = elementsEnabled
            binding.mgmtNfcOffLabel.isEnabled = elementsEnabled
            binding.mgmtNfcOnLabel.isEnabled = elementsEnabled

            val status = when (known) {
                OtpNfcState.ENABLED -> getString(R.string.otp_nfc_on)
                OtpNfcState.DISABLED -> getString(R.string.otp_nfc_off)
                OtpNfcState.UNKNOWN -> getString(R.string.dash)
            }
            binding.mgmtStatus.text =
                if (nfc != null && nfc.viaNfc && known != OtpNfcState.UNKNOWN) {
                    getString(R.string.otp_nfc_state_last_tap, status)
                } else {
                    status
                }

            if (note != null) {
                binding.mgmtReason.setText(note)
            }

            if (currentTabIndex == 3 && currentAnimator?.isRunning != true) {
                binding.root.post {
                    if (isAdded) {
                        applySectionLayout(3f, isAnimating = false)
                    }
                }
            }
        }

        binding.mgmtNfcSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (renderingMgmt) return@setOnCheckedChangeListener
            Log.d(TAG, "mgmtNfcSwitch — user set static password over NFC to ${if (isChecked) "ON" else "OFF"}")
            viewModel.queueSetOtpOverNfc(isChecked)
        }

        binding.mgmtNfcOffLabel.setOnClickListener {
            if (renderingMgmt || !binding.mgmtNfcSwitch.isEnabled) return@setOnClickListener
            Log.d(TAG, "mgmtNfcOffLabel clicked — setting static password over NFC to OFF")
            viewModel.queueSetOtpOverNfc(false)
        }

        binding.mgmtNfcOnLabel.setOnClickListener {
            if (renderingMgmt || !binding.mgmtNfcSwitch.isEnabled) return@setOnClickListener
            Log.d(TAG, "mgmtNfcOnLabel clicked — setting static password over NFC to ON")
            viewModel.queueSetOtpOverNfc(true)
        }

        binding.root.post {
            if (isAdded) {
                applySectionLayout(currentTabIndex.toFloat(), isAnimating = false)
            }
        }

        binding.yubiotpScrollView.onSwipeLeft = {
//            if (currentTabIndex < sections.size - 1) {
        if (currentTabIndex < 2) {
                navigateToTab(currentTabIndex + 1)
            }
        }
        binding.yubiotpScrollView.onSwipeRight = {
            if (currentTabIndex > 0) {
                navigateToTab(currentTabIndex - 1)
            }
        }
        binding.yubiotpScrollView.onPullDown = {
            if (currentTabIndex == 2 && binding.btnRequestStaticpwd.isEnabled) {
                binding.btnRequestStaticpwd.performClick()
            }
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state == null) return@observe
            updateButtonStates()
            updateProgressVisibility(state)
            renderMgmt(state)
        }

        viewModel.pendingAction.observe(viewLifecycleOwner) { action ->
            val state = viewModel.uiState.value ?: return@observe
            updateProgressVisibility(state, action != null)
            updateButtonStates(action != null)
            renderMgmt(state, action != null)

            if ((action == null) && (state.currentOperation != OtpOperation.NONE)) {
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

        viewModel.clearUiTrigger.observe(viewLifecycleOwner) {
            binding.editTextStaticpwdId.setText("")
            binding.btnSaveStaticpwd.isEnabled = false
            binding.extrasTabFront.isChecked = false
            binding.extrasTabEnd.isChecked = false
            binding.extrasCr.isChecked = false
            binding.keyboardRadio.check(R.id.keyoard_us)
            binding.slotRadio.check(R.id.radio_slot_1)
            binding.slotRadioReset.check(R.id.reset_slot_1)
            binding.readKeyboardRadio.check(R.id.read_keyoard_us)
            binding.readSlotRadio.check(R.id.read_radio_slot_1)
        }

        var isPasswordVisible = false
        val toggleVisibility: (Boolean) -> Unit = { visible ->
            isPasswordVisible = visible
            if (visible) {
                binding.editTextStaticpwdId.transformationMethod = HideReturnsTransformationMethod.getInstance()
                binding.btnVisibilityToggle.setImageResource(R.drawable.visibility)
            } else {
                binding.editTextStaticpwdId.transformationMethod = PasswordTransformationMethod.getInstance()
                binding.btnVisibilityToggle.setImageResource(R.drawable.visibility_off)
            }
            binding.editTextStaticpwdId.setSelection(binding.editTextStaticpwdId.text?.length ?: 0)
        }

        binding.btnVisibilityToggle.setOnClickListener {
            toggleVisibility(!isPasswordVisible)
        }

        binding.btnPasteAction.setOnClickListener {
            if (binding.editTextStaticpwdId.text.toString().isNotEmpty()){
                binding.btnPasteAction.setImageResource(R.drawable.ic_content_paste_24dp)
                binding.editTextStaticpwdId.setText("")
                binding.editTextStaticpwdId.requestFocus()
                WindowCompat.getInsetsController(requireActivity().window, binding.editTextStaticpwdId).show(WindowInsetsCompat.Type.ime())
            } else {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.text?.let { pasted ->
                    runValidated(viewModel) {
                        requireWithinMaxLength(pasted.toString())
                        binding.btnPasteAction.setImageResource(R.drawable.ic_cancel_24dp)
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
                    binding.btnPasteAction.setImageResource(R.drawable.ic_content_paste_24dp)
                    binding.textLayoutStaticpwdId.hint = getString(R.string.otp_yubistatic_id)
                } else {
                    binding.btnPasteAction.setImageResource(R.drawable.ic_cancel_24dp)
                    binding.textLayoutStaticpwdId.hint = getString(R.string.otp_yubistatic_hint)
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }
        })
        binding.keyboardRadio.setOnCheckedStateChangeListener { _, checkedIds ->
            hideIme()
            val checkedId = checkedIds.firstOrNull() ?: View.NO_ID
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
        binding.slotRadio.setOnCheckedStateChangeListener { _, _ -> hideIme() }

        bindAutoClearStatus(
            viewModel.writeStatus, binding.saveStatus,
            OtpNfcConfig.slotProgrammedStatus(Slot.ONE), OtpNfcConfig.slotProgrammedStatus(Slot.TWO),
        ) { viewModel.postWriteStatus(it) }
        viewModel.writeStatus.observe(viewLifecycleOwner) { message ->
            if (message.isNotEmpty()) binding.editTextStaticpwdId.setText("")
        }

        bindAutoClearStatus(
            viewModel.readStatus, binding.readStatus, OtpNfcConfig.readCompleteStatus(requireContext()),
        ) { viewModel.postReadStatus(it) }

        bindAutoClearStatus(
            viewModel.resetStatus, binding.deleteStatus,
            OtpNfcConfig.slotResetStatus(Slot.ONE), OtpNfcConfig.slotResetStatus(Slot.TWO),
        ) { viewModel.postResetStatus(it) }

        binding.btnSaveStaticpwd.setOnClickListener {
            hideIme()
            if (rejectIfUnsupportedDeviceConnected()) return@setOnClickListener
            runValidated(viewModel) {
                val keyboard = selectedKeyboard(binding.keyboardRadio.checkedChipId)
                val text = binding.editTextStaticpwdId.text
                val rawLength = text?.length ?: 0
                requireWithinMaxLength(rawLength)

                val tabFront = binding.extrasTabFront.isChecked
                val tabEnd = binding.extrasTabEnd.isChecked
                val staticpwd = CharArray(rawLength + (if (tabFront) 1 else 0) + (if (tabEnd) 1 else 0))
                var offset = 0
                if (tabFront) staticpwd[offset++] = '\t'
                if (text != null) {
                    TextUtils.getChars(text, 0, rawLength, staticpwd, offset)
                    offset += rawLength
                }
                if (tabEnd) staticpwd[offset] = '\t'

                try {
                    val scancodes = Keyboard.encode(staticpwd, keyboard)
                    val configuration = StaticPasswordSlotConfiguration(scancodes)
                    configuration.appendCr(binding.extrasCr.isChecked)
                    val slot = resolveSlot(binding.slotRadio.checkedChipId, R.id.radio_slot_1, R.id.radio_slot_2)

                    fun queueSave(overwriteConfirmed: Boolean) {
                        Log.d(TAG, "btnSaveStaticpwd — queuing program of slot $slot (${staticpwd.size} chars, " +
                            "keyboard=$keyboard, overwriteConfirmed=$overwriteConfirmed)")
                        viewModel.setCurrentOperation(OtpOperation.SAVE)
                        viewModel.pendingAction.value = {
                            if (!overwriteConfirmed && configurationState.isConfigured(slot)) {
                                throw IllegalStateException(
                                    "Slot ${if (slot == Slot.ONE) 1 else 2} is not empty. Confirm the overwrite and tap again."
                                )
                            }
                            Log.i(TAG, "pendingAction — programming slot $slot")
                            putConfiguration(slot, configuration, null, null)
                            Log.i(TAG, "pendingAction — slot $slot programmed")
                            viewModel.postWriteStatus(OtpNfcConfig.slotProgrammedStatus(slot))
                            null
                        }
                    }

                    val cachedState = if (slot == Slot.ONE) viewModel.uiState.value?.slotOne else viewModel.uiState.value?.slotTwo
                    if (cachedState != SlotState.EMPTY) {
                        val slotLabel = getString(if (slot == Slot.ONE) R.string.staticpwd_slot_1 else R.string.staticpwd_slot_2)
                        showConfirmationDialog(
                            tag = TAG,
                            logLabel = "Static password slot overwrite",
                            title = R.string.staticpwd_overwrite_title,
                            message = getString(R.string.staticpwd_overwrite_message, slotLabel),
                            confirmText = R.string.staticpwd_overwrite_confirm,
                            onConfirmed = { queueSave(overwriteConfirmed = true) },
                        )
                    } else {
                        queueSave(overwriteConfirmed = false)
                    }
                } finally {
                    staticpwd.fill('\u0000')
                }
            }
        }
        binding.btnRequestStaticpwd.setOnClickListener {
            if (rejectIfUnsupportedDeviceConnected()) return@setOnClickListener
            val slotTwo = binding.readSlotRadio.checkedChipId == R.id.read_radio_slot_2
            val deviceConnected = activityViewModel.yubiKey.value != null
            Log.d(TAG, "btnRequestStaticpwd — clicked (read-slot radio shows ${if (slotTwo) "TWO" else "ONE"}, " +
                "irrelevant for USB), deviceConnected=$deviceConnected")
            if (deviceConnected && !isAnySlotProgrammed()) {
                Log.d(TAG, "btnRequestStaticpwd — neither slot programmed, skipping read")
                viewModel.postResult(Result.failure(Exception(OtpNfcConfig.noSlotConfigured(requireContext()))))
                return@setOnClickListener
            }
            hideIme()
            binding.editTextStaticpwdId.clearFocus()
            Log.d(TAG, "btnRequestStaticpwd — starting read of slot ${if (slotTwo) "TWO" else "ONE"}")
            viewModel.setCurrentOperation(OtpOperation.READ)
            startRead(slotTwo)
        }
        binding.btnDeleteStaticpwd.setOnClickListener {
            if (rejectIfUnsupportedDeviceConnected()) return@setOnClickListener
            runValidated(viewModel) {
                val slot = resolveSlot(binding.slotRadioReset.checkedChipId, R.id.reset_slot_1, R.id.reset_slot_2)
                showStaticPasswordResetConfirmationDialog(slot)
            }
        }
    }

    private fun updateProgressVisibility(state: YubiOtpUiState, isBusy: Boolean? = null) {
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

    private fun showStaticPasswordDialog(password: CharArray) {
        val context = context ?: return

        val text = buildString {
            for (c in password) if (c != '\u0000') append(c)
            appendLine()
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
            .setOnDismissListener { password.fill('\u0000') }
            .show()
            .apply {
                window?.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
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
                    Log.d(TAG, "onConfirmed — queuing reset of slot $slot")
                    viewModel.setCurrentOperation(OtpOperation.RESET)
                    viewModel.pendingAction.value = {
                        if (configurationState.isConfigured(slot)) {
                            Log.i(TAG, "pendingAction — deleting slot $slot")
                            deleteConfiguration(slot, null)
                            Log.i(TAG, "pendingAction — slot $slot deleted, device now reports " +
                                "isConfigured=${configurationState.isConfigured(slot)}")
                        } else {
                            Log.i(TAG, "pendingAction — slot $slot already not programmed, nothing to delete")
                        }
                        viewModel.postResetStatus(OtpNfcConfig.slotResetStatus(slot))
                        null
                    }
                }
            },
        )
    }

    private fun rejectIfUnsupportedDeviceConnected(): Boolean {
        val device = activityViewModel.yubiKey.value
        if (PgpDeviceType.isUsbNitrokey3(device) || PgpDeviceType.isUsbNitrokey2(device)) {
            viewModel.postResult(Result.failure(Exception(OtpNfcConfig.staticPasswordsNotSupported(requireContext()))))
            return true
        }
        return false
    }

    private fun resolveSlot(checkedId: Int, oneId: Int, twoId: Int): Slot =
        when (checkedId) {
            oneId -> Slot.ONE
            twoId -> Slot.TWO
            else -> throw IllegalStateException(getString(R.string.otp_no_slot_selected))
        }

    private fun describeError(e: Exception): String = when (e) {
        is Keyboard.UnknownKeyboardException -> getString(R.string.otp_unknown_keyboard_desc, e.keyboard)
        is Keyboard.IllegalCharacterException -> getString(R.string.otp_illegal_char_desc, e.char.toString())
        is Keyboard.UnknownScanCodeException -> getString(R.string.otp_unknown_scan_code_desc)
        else -> e.message ?: e.toString()
    }

    private inline fun runValidated(viewModel: YubiOtpViewModel, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            viewModel.postResult(Result.failure(Exception(describeError(e), e)))
        }
    }
}