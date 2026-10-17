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
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

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
                    viewModel.postResult(Result.failure(Exception(YubiOtpViewModel.NO_SLOT_CONFIGURED)))
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
        Log.d(TAG, "isAnySlotProgrammed — slotOne=${state.slotOneProgrammed} slotTwo=${state.slotTwoProgrammed}")
        return state.slotOneProgrammed || state.slotTwoProgrammed
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
                            throw Exception(YubiOtpViewModel.SLOT_NOT_PROGRAMMED)
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
        viewModel.postReadStatus(YubiOtpViewModel.READ_COMPLETE_STATUS)
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSaveStaticpwd.isEnabled = false

        if (savedInstanceState == null) {
            binding.editTextStaticpwdId.requestFocus()
        }

        var currentTabIndex = 1
        var currentAnimator: ValueAnimator? = null

        fun updateDots(selectedIndex: Int) {
            binding.tabEraseIcon.setImageResource(if (selectedIndex == 0) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
            binding.tabWriteIcon.setImageResource(if (selectedIndex == 1) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
            binding.tabReadIcon.setImageResource(if (selectedIndex == 2) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
        }

        val sections = listOf(binding.sectionErase, binding.sectionWrite, binding.sectionRead)

        fun applySectionLayout(effectiveIndex: Float, isAnimating: Boolean = false) {
            val isWideTablet = resources.configuration.smallestScreenWidthDp >= 600
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val isCarouselMode = isWideTablet || isLandscape

            val screenWidth = resources.displayMetrics.widthPixels.toFloat()
            val density = resources.displayMetrics.density
            val cardSpacingPx = density * 460f
            val sectionWidthPx = if (isCarouselMode) (density * 448f).roundToInt() else ViewGroup.LayoutParams.MATCH_PARENT

            val activeDot = effectiveIndex.roundToInt().coerceIn(0, 2)
            updateDots(activeDot)

            sections.forEachIndexed { i, view ->
                val lp = view.layoutParams
                if (lp != null && lp.width != sectionWidthPx) {
                    lp.width = sectionWidthPx
                    view.layoutParams = lp
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
                        val targetIndex = effectiveIndex.roundToInt().coerceIn(0, 2)
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

        fun navigateToTab(targetIndex: Int) {
            if (targetIndex == currentTabIndex) return

            currentAnimator?.cancel()

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

        applySectionLayout(1.0f, isAnimating = false)

        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold = 80
            private val swipeVelocityThreshold = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY)) {
                    if ((abs(diffX) > swipeThreshold) && (abs(velocityX) > swipeVelocityThreshold)) {
                        if (diffX < 0) {
                            if (currentTabIndex < 2) {
                                navigateToTab(currentTabIndex + 1)
                                return true
                            }
                        } else {
                            if (currentTabIndex > 0) {
                                navigateToTab(currentTabIndex - 1)
                                return true
                            }
                        }
                    }
                } else {
                    val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    if (!isLandscape && (diffY > swipeThreshold) && (velocityY > swipeVelocityThreshold)) {
                        if (currentTabIndex == 2 && (binding.btnRequestStaticpwd.isEnabled)) {
                            binding.btnRequestStaticpwd.performClick()
                            return true
                        }
                    }
                }
                return false
            }
        })

        @SuppressLint("ClickableViewAccessibility")
        val attachSwipeListener = object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var currentX = 0f
            private var currentY = 0f
            private var isHorizontalSwipe = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        currentX = startX
                        currentY = startY
                        isHorizontalSwipe = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        currentX = event.rawX
                        currentY = event.rawY
                        val dx = currentX - startX
                        val dy = currentY - startY

                        if (!isHorizontalSwipe && abs(dx) > 30f && abs(dx) > abs(dy) * 0.7f) {
                            isHorizontalSwipe = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dx = currentX - startX
                        val dy = currentY - startY

                        if (isHorizontalSwipe && abs(dx) > 80f && abs(dx) > abs(dy) * 0.7f) {
                            if (dx < 0) {
                                if (currentTabIndex < sections.size - 1) {
                                    navigateToTab(currentTabIndex + 1)
                                }
                            } else {
                                if (currentTabIndex > 0) {
                                    navigateToTab(currentTabIndex - 1)
                                }
                            }
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            return true
                        }

                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return false
            }
        }

        val viewsToAttachSwipe = listOf(
            binding.root,
            binding.yubiotpScrollView,
            binding.tabContentContainer,
            binding.sectionErase,
            binding.sectionWrite,
            binding.sectionRead
        )
        viewsToAttachSwipe.forEach { v ->
            v.setOnTouchListener(attachSwipeListener)
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

        viewModel.clearUiTrigger.observe(viewLifecycleOwner) { shouldClear ->
            if (shouldClear) {
                binding.editTextStaticpwdId.setText("")
                binding.btnSaveStaticpwd.isEnabled = false
                binding.extrasTabFront.isChecked = false
                binding.extrasTabEnd.isChecked = false
                binding.extrasCr.isChecked = false
                binding.keyboardRadio.check(R.id.keyoard_us)
                binding.slotRadio.check(R.id.radio_slot_1)
                binding.slotRadioReset.check(R.id.reset_slot_1)
            }
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
            YubiOtpViewModel.slotProgrammedStatus(Slot.ONE), YubiOtpViewModel.slotProgrammedStatus(Slot.TWO),
        ) { viewModel.postWriteStatus(it) }
        viewModel.writeStatus.observe(viewLifecycleOwner) { message ->
            if (message.isNotEmpty()) binding.editTextStaticpwdId.setText("")
        }

        bindAutoClearStatus(
            viewModel.readStatus, binding.readStatus, YubiOtpViewModel.READ_COMPLETE_STATUS,
        ) { viewModel.postReadStatus(it) }

        bindAutoClearStatus(
            viewModel.resetStatus, binding.deleteStatus,
            YubiOtpViewModel.slotResetStatus(Slot.ONE), YubiOtpViewModel.slotResetStatus(Slot.TWO),
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
                    Log.d(TAG, "btnSaveStaticpwd — queuing program of slot $slot (${staticpwd.size} chars, keyboard=$keyboard)")
                    viewModel.setCurrentOperation(OtpOperation.SAVE)
                    viewModel.pendingAction.value = {
                        Log.i(TAG, "pendingAction — programming slot $slot")
                        putConfiguration(slot, configuration, null, null)
                        Log.i(TAG, "pendingAction — slot $slot programmed")
                        viewModel.postWriteStatus(YubiOtpViewModel.slotProgrammedStatus(slot))
                        null
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
                viewModel.postResult(Result.failure(Exception(YubiOtpViewModel.NO_SLOT_CONFIGURED)))
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
                        viewModel.postResetStatus(YubiOtpViewModel.slotResetStatus(slot))
                        null
                    }
                }
            },
        )
    }

    private fun rejectIfUnsupportedDeviceConnected(): Boolean {
        val device = activityViewModel.yubiKey.value
        if (PgpDeviceType.isUsbNitrokey(device) || PgpDeviceType.isUsbGnuk(device)) {
            viewModel.postResult(Result.failure(Exception(YubiOtpViewModel.STATIC_PASSWORDS_NOT_SUPPORTED)))
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