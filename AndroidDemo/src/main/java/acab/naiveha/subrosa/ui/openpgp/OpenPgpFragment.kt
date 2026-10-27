package acab.naiveha.subrosa.ui.openpgp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import kotlin.math.roundToInt
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentOpenpgpBinding
import acab.naiveha.subrosa.databinding.ItemOpenpgpSubkeyBinding
import acab.naiveha.subrosa.ui.YubiKeyFragment
import acab.naiveha.subrosa.ui.bindAutoClearStatus
import acab.naiveha.subrosa.ui.collectAdminPin
import acab.naiveha.subrosa.ui.collectNewAdminPin
import acab.naiveha.subrosa.ui.collectNewUserPin
import acab.naiveha.subrosa.ui.collectUserPin
import acab.naiveha.subrosa.ui.getSecret
import acab.naiveha.subrosa.ui.showConfirmationDialog
import acab.naiveha.subrosa.ui.showOpenPgpAppletResetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yubico.yubikit.core.keys.PrivateKeyValues
import com.yubico.yubikit.openpgp.OpenPgpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.openpgp.PGPSecretKeyRing

class OpenPgpFragment : YubiKeyFragment<OpenPgpSession, OpenPgpViewModel>() {
    private companion object {
        private const val TAG = "OpenPgpFragment"
    }
    override val viewModel: OpenPgpViewModel by activityViewModels()
    private lateinit var binding: FragmentOpenpgpBinding
    private var validatedBundle: ImportBundle? = null

    private var importInProgress = false

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Log.d(TAG, "File picker returned URI: $uri")
            handleImportedFileUri(uri)
        } else {
            Log.d(TAG, "File picker cancelled")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentOpenpgpBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSave.isEnabled = false

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state.pendingImportUri != null) {
                handleImportedFileUri(state.pendingImportUri)
            }

            if (state.importedKeyInfo != null) {
                showKeyInfo(state.importedKeyInfo)
            } else {
                hideKeyInfo()
            }

            Log.i(TAG, "Connected device: type=${state.connectedDevice.type} firmware=${state.connectedDevice.firmwareVersion}")

            if (state.cardInfo != null) {
                showCardInfo(state.cardInfo)
            } else {
                clearCardInfoDisplay()
            }

            updateButtonStates(state)
            updateProgressVisibility(state)
            renderPinRetries(state.pinRetries)
        }

        val onFilePickerClick = View.OnClickListener {
            Log.d(TAG, "Triggering file picker from text view")
            importFileLauncher.launch(arrayOf(
                "application/pgp-keys",
                "application/pgp-encrypted",
                "application/octet-stream",
                "*/*"
            ))
        }

        val instructionsText = getString(R.string.openpgp_tap_to_paste)
        val targetSubstr = "tap to import from file"
        val startIndex = instructionsText.indexOf(targetSubstr, ignoreCase = true)
        if (startIndex != -1) {
            val spannable = SpannableString(instructionsText)
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        onFilePickerClick.onClick(widget)
                    }
                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = true
                    }
                },
                startIndex,
                startIndex + targetSubstr.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.importKeyInstructions.text = spannable
            binding.importKeyInstructions.movementMethod = LinkMovementMethod.getInstance()
            binding.importKeyInstructions.highlightColor = android.graphics.Color.TRANSPARENT
        } else {
            binding.importKeyInstructions.text = instructionsText
        }

        binding.keyDetailsContainer.setOnClickListener {
            val info = viewModel.uiState.value?.importedKeyInfo
            if (info != null) {
                showSubkeyDetailsDialog(info)
            }
        }
        binding.keyDetailsContainer.setOnLongClickListener {
            val info = viewModel.uiState.value?.importedKeyInfo
            if (info != null) {
                showSubkeyDetailsDialog(info)
                true
            } else {
                false
            }
        }

        var currentTabIndex = 1
        var currentAnimator: ValueAnimator? = null

        fun updateDots(selectedIndex: Int) {
            binding.tabEraseIcon.setImageResource(if (selectedIndex == 0) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
            binding.tabWriteIcon.setImageResource(if (selectedIndex == 1) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
            binding.tabReadIcon.setImageResource(if (selectedIndex == 2) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
            binding.tabMgmtIcon.setImageResource(if (selectedIndex == 3) R.drawable.ic_tab_dot_filled else R.drawable.ic_tab_dot_outline)
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

            val activeDot = effectiveIndex.roundToInt().coerceIn(0, 3)
            updateDots(activeDot)

            val activeSection = sections[activeDot]
            activeSection.measure(
                View.MeasureSpec.makeMeasureSpec(binding.tabContentContainer.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            binding.openpgpScrollView.activeTabHeight = activeSection.measuredHeight
            activeSection.requestLayout()

            sections.forEachIndexed { i, view ->
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
                    if (binding.openpgpScrollView.scrollY > 0) {
                        val scrollAnim = ObjectAnimator.ofInt(binding.openpgpScrollView, "scrollY", binding.openpgpScrollView.scrollY, 0)
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
        binding.tabMgmt.setOnClickListener { navigateToTab(3) }

        binding.root.post {
            if (isAdded) {
                applySectionLayout(currentTabIndex.toFloat(), isAnimating = false)
            }
        }

        val dash = getString(R.string.dash)
        binding.pinRetriesUser.text = dash
        binding.pinRetriesAdmin.text = dash

        binding.openpgpScrollView.onSwipeLeft = {
            if (currentTabIndex < sections.size - 1) {
                navigateToTab(currentTabIndex + 1)
            }
        }
        binding.openpgpScrollView.onSwipeRight = {
            if (currentTabIndex > 0) {
                navigateToTab(currentTabIndex - 1)
            }
        }
        binding.openpgpScrollView.onPullDown = {
            if (currentTabIndex == 3) {
                Log.d(TAG, "Pull-down on Management tab — refreshing PIN counters")
                viewModel.refreshPinCounters()
            } else if (currentTabIndex == 2 && binding.btnTestAction.isEnabled) {
                Log.d(TAG, "Pull-down on Read tab — reading card")
                binding.btnTestAction.performClick()
            }
        }

        binding.pinRetriesContainer.setOnClickListener {
            Toast.makeText(requireContext(), "Pull down to refresh the PIN counters", Toast.LENGTH_SHORT).show()
        }

        binding.cardInfoContainer.setOnClickListener {
            val cardDash = getString(R.string.dash)
            val isCardInfoEmpty = binding.cardInfoName.text.toString() == cardDash || binding.cardInfoSigFingerprint.text.toString().trim() == cardDash.trim()
            if (isCardInfoEmpty) {
                Toast.makeText(requireContext(), "Pull down to refresh OpenPGP card details", Toast.LENGTH_SHORT).show()
            }
        }



        viewModel.pendingAction.observe(viewLifecycleOwner) { action ->
            val state = viewModel.uiState.value ?: OpenPgpUiState()
            updateProgressVisibility(state, action != null)
            updateButtonStates(state, action != null)

            if ((action == null) && (state.currentOperation != OpenPgpOperation.NONE)) {
                if (state.currentOperation == OpenPgpOperation.SAVE) {
                    if (viewModel.lastQueuedActionSucceeded == true) {
                        Log.i(TAG, "Save succeeded — resetting UI")
                        resetKeyState()
                        clearCardInfoDisplay()
                        viewModel.clearCardInfo()
                        clearPinRetriesDisplay()
                    } else {
                        Log.i(TAG, "Save did not complete successfully — keeping the loaded key so the user can retry")
                    }
                }
                viewModel.setCurrentOperation(OpenPgpOperation.NONE)
            }
        }

        bindAutoClearStatus(viewModel.writeStatus, binding.writeStatus, OpenPgpWriter.WRITE_COMPLETE_STATUS, "Ready to write") {
            viewModel.postWriteStatus(it)
            if (it == OpenPgpWriter.WRITE_COMPLETE_STATUS) {
                clearCardInfoDisplay()
                viewModel.clearCardInfo()
                clearPinRetriesDisplay()
            }
        }
        bindAutoClearStatus(viewModel.readStatus, binding.readStatus, OpenPgpReader.READ_COMPLETE_STATUS) {
            viewModel.postReadStatus(it)
        }
        bindAutoClearStatus(viewModel.wipeStatus, binding.wipeStatus, OpenPgpWriter.WIPE_COMPLETE_STATUS) {
            viewModel.postWipeStatus(it)
            clearCardInfoDisplay()
            viewModel.clearCardInfo()
            clearPinRetriesDisplay()
        }
        bindAutoClearStatus(
            viewModel.pinChangeStatus,
            binding.pinChangeStatus,
            OpenPgpViewModel.PIN_CHANGE_COMPLETE_STATUS,
            OpenPgpViewModel.PIN_RESET_COMPLETE_STATUS,
            OpenPgpWriter.WIPE_COMPLETE_STATUS,
            "PIN retries refreshed",
            hideWhenBlank = true,
            shouldHide = { it.contains("Remaining attempts", ignoreCase = true) },
        ) {
            viewModel.postPinChangeStatus(it)
            if (it == OpenPgpWriter.WIPE_COMPLETE_STATUS || it == OpenPgpViewModel.PIN_RESET_COMPLETE_STATUS) {
                clearCardInfoDisplay()
                viewModel.clearCardInfo()
                clearPinRetriesDisplay()
            }
        }

        binding.btnSave.setOnClickListener {
            val bundle = validatedBundle ?: run {
                Log.w(TAG, "Save tapped but validatedBundle is null")
                viewModel.postResult(Result.failure(IllegalStateException(getString(R.string.openpgp_no_key_loaded))))
                return@setOnClickListener
            }
            Log.d(TAG, "Save tapped — device=${viewModel.uiState.value?.connectedDevice?.type} " +
                "(writer decided once a device is connected)")

            showConfirmationDialog(
                tag = TAG,
                logLabel = "OpenPGP write",
                title = R.string.openpgp_write_confirm_title,
                message = getString(R.string.openpgp_write_confirm_message),
                confirmText = R.string.openpgp_write_confirm_action,
                onConfirmed = {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val adminPin = collectAdminPin(
                            "Enter Device Admin PIN",
                            tag = TAG,
                            defaultValue = OpenPgpWriterUtils.DEFAULT_ADMIN_PIN,
                            clearTextByDefault = true,
                        ) ?: return@launch
                        val userPin = collectUserPin(
                            "Enter Device User PIN",
                            tag = TAG,
                            defaultValue = OpenPgpWriterUtils.DEFAULT_USER_PIN,
                            clearTextByDefault = true,
                        ) ?: run { adminPin.fill('\u0000'); return@launch }

                        viewModel.queueWithPins(OpenPgpOperation.SAVE, listOf(adminPin, userPin)) {
                            val deviceType = viewModel.currentState.connectedDevice.type
                            val writer = deviceType.writer()
                            Log.i(TAG, "pendingAction — device=$deviceType " +
                                "writer=${writer::class.simpleName}")
                            writer.program(this, bundle, adminPin, userPin, status = viewModel::postWriteStatus).also {
                                viewModel.clearCardInfo()
                            }
                        }
                    }
                },
            )
        }

        binding.btnTestAction.setOnClickListener {
            Log.d(TAG, "Read tapped — device=${viewModel.uiState.value?.connectedDevice?.type}")
            viewModel.postReadStatus("Reading card status…")
            viewModel.setCurrentOperation(OpenPgpOperation.READ)
            viewModel.pendingAction.value = {
                val info = OpenPgpReader.read(
                    this,
                    knownFirmwareVersion = viewModel.uiState.value?.connectedDevice?.firmwareVersion,
                    status = viewModel::postReadStatus,
                )
                viewModel.onCardRead(info)
                null
            }
        }

        binding.btnDeleteOpenpgp.setOnClickListener {
            showResetConfirmationDialog()
        }

        binding.btnChangeAdminPin.setOnClickListener {
            val retries = viewModel.uiState.value?.pinRetries
            if (retries?.admin == 0) {
                onResetOpenPgpClicked()
            } else {
                onChangePinClicked(isAdmin = true)
            }
        }
        binding.btnChangeUserPin.setOnClickListener {
            val retries = viewModel.uiState.value?.pinRetries
            if (retries?.user == 0) {
                onResetUserPinClicked()
            } else {
                onChangePinClicked(isAdmin = false)
            }
        }
    }

    private fun updateProgressVisibility(state: OpenPgpUiState, isBusy: Boolean? = null) {
        val busy = isBusy ?: (viewModel.pendingAction.value != null)
        val op = state.currentOperation
        binding.progressSave.visibility = if (busy && (op == OpenPgpOperation.SAVE)) View.VISIBLE else View.GONE
        binding.progressRead.visibility = if (busy && (op == OpenPgpOperation.READ)) View.VISIBLE else View.GONE
        binding.progressWipe.visibility = if (busy && (op == OpenPgpOperation.WIPE)) View.VISIBLE else View.GONE

        val adminBusy = busy && (op == OpenPgpOperation.CHANGE_ADMIN_PIN || op == OpenPgpOperation.RESET_ADMIN_PIN)
        val userBusy = busy && (op == OpenPgpOperation.CHANGE_USER_PIN || op == OpenPgpOperation.RESET_USER_PIN)
        binding.progressChangeAdminPin.visibility = if (adminBusy) View.VISIBLE else View.GONE
        binding.progressChangeUserPin.visibility = if (userBusy) View.VISIBLE else View.GONE
    }

    private fun updateButtonStates(state: OpenPgpUiState, isBusy: Boolean? = null) {
        val busy = isBusy ?: (viewModel.pendingAction.value != null)
        binding.btnSave.isEnabled = (!busy && (validatedBundle != null) && (state.currentOperation != OpenPgpOperation.SAVE))
        binding.btnTestAction.isEnabled = !busy
        binding.btnDeleteOpenpgp.isEnabled = !busy
        binding.btnChangeAdminPin.isEnabled = !busy
        binding.btnChangeUserPin.isEnabled = !busy
    }

    override fun onDestroyView() {
        super.onDestroyView()
        validatedBundle?.destroy()
        validatedBundle = null
    }

    private fun showResetConfirmationDialog() {
        showOpenPgpAppletResetDialog(TAG, onConfirmed = {
            Log.i(TAG, "Reset confirmed — device=${viewModel.uiState.value?.connectedDevice?.type}")
            viewModel.setCurrentOperation(OpenPgpOperation.WIPE)
            viewModel.pendingAction.value = {
                val deviceType = viewModel.currentState.connectedDevice.type
                val writer = deviceType.writer()
                Log.i(TAG, "wipe — device=$deviceType " +
                    "writer=${writer::class.simpleName}")
                writer.wipe(this, status = viewModel::postWipeStatus).also {
                    viewModel.clearCardInfo()
                }
            }
        })
    }

    private fun renderPinRetries(retries: PinRetries?) {
        if (retries == null) {
            val dash = getString(R.string.dash)
            binding.pinRetriesUser.text = dash
            binding.pinRetriesAdmin.text = dash
            binding.btnChangeUserPin.visibility = View.VISIBLE
            binding.btnChangeUserPin.text = getString(R.string.openpgp_btn_change_user_pin)
            binding.btnChangeAdminPin.text = getString(R.string.openpgp_btn_change_admin_pin)
            return
        }

        val userRetries = if (retries.admin == 0) 0 else retries.user
        binding.pinRetriesUser.text = userRetries.toString()
        binding.pinRetriesAdmin.text = retries.admin.toString()

        if (retries.admin == 0) {
            binding.btnChangeUserPin.visibility = View.GONE
            binding.btnChangeAdminPin.text = getString(R.string.openpgp_btn_reset)
        } else {
            binding.btnChangeUserPin.visibility = View.VISIBLE
            binding.btnChangeAdminPin.text = getString(R.string.openpgp_btn_change_admin_pin)
            binding.btnChangeUserPin.text = if (retries.user == 0) {
                getString(R.string.openpgp_btn_reset_user_pin)
            } else {
                getString(R.string.openpgp_btn_change_user_pin)
            }
        }
    }

    private fun onChangePinClicked(isAdmin: Boolean) {
        val label = if (isAdmin) "Admin" else "User"
        Log.d(TAG, "Change $label PIN tapped")

        lifecycleScope.launch(Dispatchers.Main) {
            val currentPin = (
                if (isAdmin) {
                    collectAdminPin("Enter current Device Admin PIN", tag = TAG, logLabel = "Current Admin PIN")
                } else {
                    collectUserPin("Enter current Device User PIN", tag = TAG, logLabel = "Current User PIN")
                }
            ) ?: return@launch

            val newPin = (if (isAdmin) collectNewAdminPin(TAG) else collectNewUserPin(TAG))
                ?: run {
                    Log.d(TAG, "Change $label PIN cancelled (new PIN)")
                    currentPin.fill('\u0000')
                    return@launch
                }

            viewModel.changePin(isAdmin, currentPin, newPin)
        }
    }

    private fun onResetUserPinClicked() {
        Log.d(TAG, "Reset blocked User PIN tapped")

        lifecycleScope.launch(Dispatchers.Main) {
            val adminPin = collectAdminPin(
                "Enter Device Admin PIN",
                tag = TAG,
                logLabel = "Admin PIN (for User PIN reset)",
            ) ?: return@launch

            val newUserPin = collectNewUserPin(TAG)
                ?: run {
                    Log.d(TAG, "Reset User PIN cancelled (new PIN)")
                    adminPin.fill('\u0000')
                    return@launch
                }

            viewModel.resetBlockedUserPin(adminPin, newUserPin)
        }
    }

    private fun onResetOpenPgpClicked() {
        Log.d(TAG, "Reset OpenPGP applet tapped (Admin PIN retries exhausted)")
        showOpenPgpAppletResetDialog(TAG, onConfirmed = { viewModel.resetOpenPgpApplet() })
    }

    private fun showMonospaceInfoDialog(
        title: String,
        text: String,
        textSize: Float = 11.5f,
        onRemove: (() -> Unit)? = null,
    ) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val tv = TextView(requireContext()).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            this.textSize = textSize
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val sv = ScrollView(requireContext()).also { it.addView(tv) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(sv)
            .setPositiveButton(android.R.string.ok, null)
            .apply { onRemove?.let { remove -> setNeutralButton("Remove Key") { _, _ -> remove() } } }
            .show()
    }

    private fun showSubkeyDetailsDialog(info: OpenPgpKeyInfo) {
        showMonospaceInfoDialog(
            title = "OpenPGP key details",
            text = info.toDisplayText(),
            textSize = 11f,
            onRemove = { resetKeyState() },
        )
    }

    private fun handleImportedFileUri(uri: Uri) {
        if (importInProgress) return
        importInProgress = true
        Toast.makeText(requireContext(), "Importing PGP key...", Toast.LENGTH_SHORT).show()
        viewModel.postWriteStatus("Decoding armored input...")

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                viewModel.consumeImportUri()
                val fileBytes = withContext(Dispatchers.IO) {
                    runCatching {
                        requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                binding.progressSave.visibility = View.GONE

                if (fileBytes == null) {
                    Log.e(TAG, "Could not read file: $uri")
                    viewModel.postResult(Result.failure(Exception(getString(R.string.openpgp_file_read_error))))
                    return@launch
                }
                Log.d(TAG, "File read: ${fileBytes.size} bytes")

                val ring = decryptFileWithPassphraseRetry(fileBytes) ?: return@launch

                Log.d(TAG, "Re-arming PGPSecretKeyRing…")
                val armor = withContext(Dispatchers.IO) { OpenPgpFileImporter.toArmoredString(ring) }
                Log.d(TAG, "Armor generated (${armor.length} chars)")

                OpenPgpKeyParser.parse(armor)
                    .onSuccess { info ->
                        Log.i(TAG, "File import parsed: hasUserId=${info.userId.isNotBlank()}")
                        viewModel.onImportedKey(armor, info)
                        validateAndStoreBundle(armor, info)
                    }
                    .onFailure { e ->
                        Log.e(TAG, "File import parse failed: ${e.message}", e)
                        resetKeyState()
                        viewModel.postResult(Result.failure(e))
                    }
            } finally {
                importInProgress = false
            }
        }
    }

    private suspend fun decryptFileWithPassphraseRetry(fileBytes: ByteArray): PGPSecretKeyRing? {
        val firstAttempt = withContext(Dispatchers.IO) {
            runCatching { OpenPgpFileImporter.decrypt(fileBytes, CharArray(0)) }
        }
        firstAttempt.exceptionOrNull()?.let { e ->
            if (e is PgpImportLimitExceededException) {
                Log.w(TAG, "File import rejected: ${e.message}")
                viewModel.postResult(Result.failure(e))
                return null
            }
        }
        var ring = firstAttempt.getOrNull()
        while (ring == null) {
            val passphrase = getSecret(requireActivity(), R.string.enter_file_passphrase, showPaste = true)
                ?: run { Log.d(TAG, "File passphrase cancelled"); return null }
            Log.d(TAG, "File passphrase length=${passphrase.size} — decrypting…")
            viewModel.postWriteStatus("Extracting private key...")
            binding.progressSave.visibility = View.VISIBLE
            binding.btnSave.isEnabled = false
            val attempt = withContext(Dispatchers.IO) {
                runCatching { OpenPgpFileImporter.decrypt(fileBytes, passphrase) }
            }
            passphrase.fill('\u0000')
            binding.progressSave.visibility = View.GONE

            val error = attempt.exceptionOrNull()
            if (error is PgpImportLimitExceededException) {
                Log.w(TAG, "File import rejected: ${error.message}")
                viewModel.postResult(Result.failure(error))
                return null
            }
            if (error != null) {
                Log.e(TAG, "File decryption failed: ${error::class.simpleName}: ${error.message}", error)
            }
            ring = attempt.getOrNull()
            if (ring == null) {
                Log.w(TAG, "Wrong file passphrase")
                viewModel.postResult(Result.failure(Exception(getString(R.string.openpgp_file_wrong_passphrase))))
            }
        }
        return ring
    }

    private fun onPasteClicked() {
        Log.d(TAG, "onPasteClicked()")
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val rawText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (rawText.isNullOrBlank()) {
            Log.d(TAG, "Clipboard is empty")
            resetKeyState()
            return
        }
        Log.d(TAG, "Clipboard length=${rawText.length}")

        if (!OpenPgpKeyParser.isOpenPgpPrivateKey(rawText)) {
            Log.d(TAG, "isOpenPgpPrivateKey=false")
            resetKeyState()
            Toast.makeText(requireContext(), R.string.openpgp_key_invalid, Toast.LENGTH_LONG).show()
            return
        }
        Log.d(TAG, "Parsing key metadata…")
        viewModel.postWriteStatus("Decoding armored input...")
        OpenPgpKeyParser.parse(rawText)
            .onSuccess { info ->
                Log.i(TAG, "Parsed — hasUserId=${info.userId.isNotBlank()} protected=${info.isPassphraseProtected}")
                viewModel.onImportedKey(rawText, info)
                validateAndStoreBundle(rawText, info)
            }
            .onFailure { e ->
                Log.e(TAG, "Parse failed: ${e.message}", e)
                resetKeyState()
                viewModel.postResult(Result.failure(e))
            }
    }

    private suspend fun prepareBundleWithPassphraseRetry(armor: String): ImportBundle? {
        Log.d(TAG, "Key is passphrase-protected — collecting passphrase…")
        while (true) {
            val passphrase = getSecret(requireActivity(), R.string.enter_key_passphrase, showPaste = true)
                ?: run { Log.d(TAG, "Passphrase cancelled"); return null }
            viewModel.postWriteStatus("Extracting private key...")
            binding.progressSave.visibility = View.VISIBLE
            binding.btnSave.isEnabled = false
            val bundle = withContext(Dispatchers.IO) {
                try {
                    OpenPgpKeyImporter.prepare(armor, passphrase)
                } catch (e: Exception) {
                    Log.e(TAG, "prepare() failed: ${e.message}", e)
                    null
                } finally {
                    passphrase.fill('\u0000')
                }
            }
            binding.progressSave.visibility = View.GONE
            if (bundle != null) return bundle
            viewModel.clearImportedKey()
            viewModel.postResult(Result.failure(Exception(getString(R.string.openpgp_wrong_passphrase))))
        }
    }

    private fun validateAndStoreBundle(armor: String, info: OpenPgpKeyInfo) {
        lifecycleScope.launch(Dispatchers.Main) {
            validatedBundle?.destroy()
            validatedBundle = null

            viewModel.postWriteStatus("Extracting private key...")
            val bundle = if (info.isPassphraseProtected) {
                prepareBundleWithPassphraseRetry(armor) ?: run { resetKeyState(); return@launch }
            } else {
                binding.progressSave.visibility = View.VISIBLE
                binding.btnSave.isEnabled = false
                val prepared = withContext(Dispatchers.IO) {
                    runCatching { OpenPgpKeyImporter.prepare(armor) }
                        .onFailure { Log.e(TAG, "prepare() failed: ${it.message}", it) }
                        .getOrNull()
                }
                binding.progressSave.visibility = View.GONE
                prepared ?: run {
                    viewModel.clearImportedKey()
                    viewModel.postResult(Result.failure(Exception(getString(R.string.openpgp_key_prepare_failed))))
                    return@launch
                }
            }

            validatedBundle = bundle
            viewModel.postWriteStatus("Ready to write")
            updateButtonStates(viewModel.uiState.value ?: OpenPgpUiState())
        }
    }

    private fun resetKeyState() {
        viewModel.clearImportedKey()
        validatedBundle?.destroy()
        validatedBundle = null
    }

    private fun buildWriteConfirmMessage(info: OpenPgpKeyInfo?, bundle: ImportBundle): String = buildString {
        append(getString(R.string.openpgp_write_confirm_message))
        if (info != null && info.userId.isNotBlank()) {
            append("\n\n")
            append(info.userId)
        }
        if (bundle.slots.isNotEmpty()) {
            append("\n\n")
            append(bundle.slots.joinToString("\n") { slot ->
                val algorithm = when (val values = slot.privateKeyValues) {
                    is PrivateKeyValues.Rsa -> "RSA-${values.bitLength}"
                    is PrivateKeyValues.Ec -> values.curveParams.name
                    else -> values::class.simpleName.toString()
                }
                val shortFingerprint = slot.fingerprint.joinToString("") { "%02X".format(it) }.takeLast(16)
                "${slot.ref.name}   $algorithm   …$shortFingerprint"
            })
        }
    }

    private fun showKeyInfo(info: OpenPgpKeyInfo) {
        with(binding) {
            keyInfoUserId.text      = info.userId
            keyInfoAlgorithm.text   = info.algorithm
            keyInfoCreated.text     = info.creationDate.toDisplayDate()
            keyInfoExpires.text     = info.expirationDate?.toDisplayDate()
                ?: getString(R.string.openpgp_key_no_expiry)
            keyInfoFingerprint.text = info.fingerprint
            keyInfoFingerprint.setTextIsSelectable(true)

            keyInfoSubkeysContainer.removeAllViews()

            val subkeys = info.keys.filter { !it.usage.contains("Primary") }.ifEmpty {
                if (info.keys.size > 1) info.keys.drop(1) else emptyList()
            }

            for (subkey in subkeys) {
                val subkeyBinding = ItemOpenpgpSubkeyBinding.inflate(
                    layoutInflater,
                    keyInfoSubkeysContainer,
                    false
                )
                subkeyBinding.subkeyHeader.text = "── ${subkey.usage}"
                subkeyBinding.subkeyAlgorithm.text = subkey.algorithm
                subkeyBinding.subkeyCreated.text = subkey.creationDate.toDisplayDate()
                subkeyBinding.subkeyExpires.text = subkey.expirationDate?.toDisplayDate()
                    ?: getString(R.string.openpgp_key_no_expiry)
                subkeyBinding.subkeyFingerprint.text = formatFingerprintDisplay(subkey.fingerprint)
                subkeyBinding.subkeyFingerprint.setTextIsSelectable(true)

                keyInfoSubkeysContainer.addView(subkeyBinding.root)
            }

            root.post { root.requestLayout() }
        }
    }

    private fun hideKeyInfo() {
        with(binding) {
            val dash = getString(R.string.dash)
            keyInfoUserId.text      = dash
            keyInfoAlgorithm.text   = dash
            keyInfoCreated.text     = dash
            keyInfoExpires.text     = dash
            keyInfoFingerprint.text = getString(R.string.dash_newline)
            keyInfoFingerprint.setTextIsSelectable(false)
            keyInfoSubkeysContainer.removeAllViews()
        }
    }

    private fun formatFingerprintDisplay(fp: String): String {
        val clean = fp.filter { it.isLetterOrDigit() }
        val chunks = clean.chunked(4)
        return if (chunks.size >= 10) {
            val l1 = chunks.subList(0, 5).joinToString(" ")
            val l2 = chunks.subList(5, 10).joinToString(" ")
            "$l1\n$l2"
        } else {
            chunks.joinToString(" ")
        }
    }

    private fun showCardInfo(info: OpenPgpCardInfo) {
        with(binding) {
            cardInfoName.text = info.cardholderName.fromNameDo()
            cardInfoLogin.text = if (info.login.isBlank() || (info.login == "—")) getString(R.string.dash) else info.login

            val sigSlot = info.slots.find { it.ref.name == "SIG" }
            val decSlot = info.slots.find { it.ref.name == "DEC" }
            val autSlot = info.slots.find { it.ref.name == "AUT" }

            val hasSig = sigSlot != null && sigSlot.hasKey
            if (hasSig) {
                cardInfoSigAlgorithm.text = sigSlot.algorithm
                cardInfoSigExpires.text = sigSlot.created
                cardInfoSigFingerprint.text = formatFingerprintDisplay(sigSlot.fingerprint)
            } else {
                cardInfoSigAlgorithm.text = getString(R.string.dash)
                cardInfoSigExpires.text = getString(R.string.dash)
                cardInfoSigFingerprint.text = getString(R.string.dash_newline)
            }
            cardInfoSigFingerprint.setTextIsSelectable(hasSig)

            val hasDec = decSlot != null && decSlot.hasKey
            if (hasDec) {
                cardInfoDecAlgorithm.text = decSlot.algorithm
                cardInfoDecExpires.text = decSlot.created
                cardInfoDecFingerprint.text = formatFingerprintDisplay(decSlot.fingerprint)
            } else {
                cardInfoDecAlgorithm.text = getString(R.string.dash)
                cardInfoDecExpires.text = getString(R.string.dash)
                cardInfoDecFingerprint.text = getString(R.string.dash_newline)
            }
            cardInfoDecFingerprint.setTextIsSelectable(hasDec)

            val hasAut = autSlot != null && autSlot.hasKey
            if (hasAut) {
                cardInfoAutAlgorithm.text = autSlot.algorithm
                cardInfoAutExpires.text = autSlot.created
                cardInfoAutFingerprint.text = formatFingerprintDisplay(autSlot.fingerprint)
            } else {
                cardInfoAutAlgorithm.text = getString(R.string.dash)
                cardInfoAutExpires.text = getString(R.string.dash)
                cardInfoAutFingerprint.text = getString(R.string.dash_newline)
            }
            cardInfoAutFingerprint.setTextIsSelectable(hasAut)
        }
    }

    private fun clearCardInfoDisplay() {
        with(binding) {
            val dash = getString(R.string.dash)
            val dashNewline = getString(R.string.dash_newline)

            cardInfoName.text = dash
            cardInfoLogin.text = dash

            cardInfoSigAlgorithm.text = dash
            cardInfoSigExpires.text = dash
            cardInfoSigFingerprint.text = dashNewline
            cardInfoSigFingerprint.setTextIsSelectable(false)

            cardInfoDecAlgorithm.text = dash
            cardInfoDecExpires.text = dash
            cardInfoDecFingerprint.text = dashNewline
            cardInfoDecFingerprint.setTextIsSelectable(false)

            cardInfoAutAlgorithm.text = dash
            cardInfoAutExpires.text = dash
            cardInfoAutFingerprint.text = dashNewline
            cardInfoAutFingerprint.setTextIsSelectable(false)
        }
    }

    private fun clearPinRetriesDisplay() {
        val dash = getString(R.string.dash)
        binding.pinRetriesUser.text = dash
        binding.pinRetriesAdmin.text = dash
        viewModel.clearPinRetries()
    }
}
