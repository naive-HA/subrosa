package acab.naiveha.subrosa.ui.openpgp

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import acab.naiveha.subrosa.MainViewModel
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentOpenpgpBinding
import acab.naiveha.subrosa.ui.bindAutoClearStatus
import acab.naiveha.subrosa.ui.collectPin
import acab.naiveha.subrosa.ui.getSecret
import acab.naiveha.subrosa.ui.showOpenPgpAppletResetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.openpgp.PGPSecretKeyRing

class OpenPgpFragment : Fragment() {
    private companion object {
        const val TAG = "OpenPgpFragment"
    }
    private val activityViewModel: MainViewModel by activityViewModels()
    private val viewModel: OpenPgpViewModel by activityViewModels()
    private lateinit var binding: FragmentOpenpgpBinding
    private var rawKeyArmor: String? = null
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSave.isEnabled = false

        viewModel.pendingImportUri.observe(viewLifecycleOwner) { uri ->
            if (uri != null) {
                handleImportedFileUri(uri)
            }
        }

        viewModel.importedKeyInfo.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                showKeyInfo(info)
                binding.btnSave.isEnabled = viewModel.currentOperation.value != OpenPgpOperation.SAVE
            } else {
                hideKeyInfo()
                binding.btnSave.isEnabled = false
            }
        }

        viewModel.importedKeyArmor.observe(viewLifecycleOwner) { armor ->
            rawKeyArmor = armor
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

        val onPasteLongClick = View.OnLongClickListener {
            onPasteClicked()
            true
        }

        binding.keyInfoSection.setOnClickListener {
            val info = viewModel.importedKeyInfo.value
            if (info != null) {
                showSubkeyDetailsDialog(info)
            } else {
                onFilePickerClick.onClick(it)
            }
        }
        binding.keyInfoSection.setOnLongClickListener {
            val info = viewModel.importedKeyInfo.value
            if (info != null) {
                showSubkeyDetailsDialog(info)
                true
            } else {
                onPasteLongClick.onLongClick(it)
            }
        }

        viewModel.pendingAction.observe(viewLifecycleOwner) { action ->
            val busy = action != null
            val op = viewModel.currentOperation.value ?: OpenPgpOperation.NONE
            Log.d(TAG, "pendingAction → busy=$busy operation=$op")

            binding.progressSave.visibility = if (busy && op == OpenPgpOperation.SAVE) View.VISIBLE else View.GONE
            binding.progressRead.visibility = if (busy && op == OpenPgpOperation.READ) View.VISIBLE else View.GONE
            binding.progressWipe.visibility = if (busy && op == OpenPgpOperation.WIPE) View.VISIBLE else View.GONE

            binding.btnSave.isEnabled = !busy && validatedBundle != null
            binding.btnTestAction.isEnabled = !busy
            binding.btnDeleteOpenpgp.isEnabled = !busy

            if (!busy && op != OpenPgpOperation.NONE) {
                if (op == OpenPgpOperation.SAVE) {
                    Log.i(TAG, "Save completed — resetting UI")
                    resetKeyState()
                }
                viewModel.currentOperation.value = OpenPgpOperation.NONE
            }
        }
        viewModel.connectedDevice.observe(viewLifecycleOwner) { device ->
            Log.i(TAG, "Connected device: type=${device.type} firmware=${device.firmwareVersion}")
        }

        bindAutoClearStatus(viewModel.writeStatus, binding.writeStatus, OpenPgpWriter.WRITE_COMPLETE_STATUS) {
            viewModel.postWriteStatus(it)
        }
        bindAutoClearStatus(viewModel.readStatus, binding.readStatus, OpenPgpReader.READ_COMPLETE_STATUS) {
            viewModel.postReadStatus(it)
        }
        bindAutoClearStatus(viewModel.wipeStatus, binding.wipeStatus, OpenPgpWriter.WIPE_COMPLETE_STATUS) {
            viewModel.postWipeStatus(it)
        }

        viewModel.cardInfo.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                viewModel.clearCardInfo()
                showCardInfoDialog(info)
            }
        }

        binding.btnSave.setOnClickListener {
            val bundle = validatedBundle ?: run {
                Log.w(TAG, "Save tapped but validatedBundle is null")
                viewModel.postResult(Result.failure(IllegalStateException(getString(R.string.openpgp_no_key_loaded))))
                return@setOnClickListener
            }
            Log.d(TAG, "Save tapped — device=${viewModel.connectedDevice.value?.type} " +
                "(writer decided once a device is connected)")

            lifecycleScope.launch(Dispatchers.Main) {
                val adminPin = collectAdminPin() ?: return@launch
                val userPin = collectUserPin() ?: run { adminPin.fill('\u0000'); return@launch }

                viewModel.currentOperation.value = OpenPgpOperation.SAVE
                viewModel.pendingAction.value = {
                    val writer = writerFor(viewModel.connectedDevice.value?.type)
                    Log.i(TAG, "pendingAction — device=${viewModel.connectedDevice.value?.type} " +
                        "writer=${writer::class.simpleName}")
                    writer.program(this, bundle, adminPin, userPin, status = viewModel::postWriteStatus)
                }
            }
        }

        binding.btnTestAction.setOnClickListener {
            Log.d(TAG, "Read tapped — device=${viewModel.connectedDevice.value?.type}")
            viewModel.currentOperation.value = OpenPgpOperation.READ
            viewModel.pendingAction.value = {
                val info = OpenPgpReader.read(
                    this,
                    knownFirmwareVersion = viewModel.currentDeviceFirmwareVersion,
                    status = viewModel::postReadStatus,
                )
                viewModel.onCardRead(info)
                null
            }
        }

        binding.btnDeleteOpenpgp.setOnClickListener {
            showResetConfirmationDialog()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        validatedBundle?.destroy()
        validatedBundle = null
    }

    private fun writerFor(type: PgpDeviceType?): OpenPgpWriter = when (type) {
        PgpDeviceType.NITROKEY -> NitrokeyPgpWriter
        else                   -> YubiKeyPgpWriter
    }

    private suspend fun collectAdminPin(): CharArray? = collectPin(
        requireContext(),
        "Enter Device Admin PIN",
        minLength = 8,
        tooShortRes = R.string.openpgp_admin_pin_too_short,
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        tag = TAG,
        logLabel = "Admin PIN",
        defaultValue = String(OpenPgpWriterUtils.DEFAULT_ADMIN_PIN),
        clearTextByDefault = true,
    )

    private suspend fun collectUserPin(): CharArray? = collectPin(
        requireContext(),
        "Enter Device User PIN",
        minLength = 6,
        tooShortRes = R.string.openpgp_user_pin_too_short,
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        tag = TAG,
        logLabel = "User PIN",
        defaultValue = String(OpenPgpWriterUtils.DEFAULT_USER_PIN),
        clearTextByDefault = true,
    )

    private fun showResetConfirmationDialog() {
        showOpenPgpAppletResetDialog(TAG, onConfirmed = {
            Log.i(TAG, "Reset confirmed — device=${viewModel.connectedDevice.value?.type}")
            viewModel.currentOperation.value = OpenPgpOperation.WIPE
            viewModel.pendingAction.value = {
                val writer = writerFor(viewModel.connectedDevice.value?.type)
                Log.i(TAG, "wipe — device=${viewModel.connectedDevice.value?.type} " +
                    "writer=${writer::class.simpleName}")
                writer.wipe(this, status = viewModel::postWipeStatus)
            }
        })
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

    private fun showCardInfoDialog(info: OpenPgpCardInfo) {
        showMonospaceInfoDialog(
            title = getString(R.string.openpgp_card_details_title),
            text = "Firmware    ${info.version}\n" + info.toDisplayText(),
        )
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
                        Log.i(TAG, "File import parsed: userId='${info.userId}'")
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
        var ring = withContext(Dispatchers.IO) {
            runCatching { OpenPgpFileImporter.decrypt(fileBytes, CharArray(0)) }.getOrNull()
        }
        while (ring == null) {
            val passphraseStr = getSecret(requireActivity(), R.string.enter_file_passphrase, showPaste = true)
                ?: run { Log.d(TAG, "File passphrase cancelled"); return null }
            Log.d(TAG, "File passphrase length=${passphraseStr.length} — decrypting…")
            binding.progressSave.visibility = View.VISIBLE
            binding.btnSave.isEnabled = false
            val passphrase = passphraseStr.toCharArray()
            ring = withContext(Dispatchers.IO) {
                try {
                    OpenPgpFileImporter.decrypt(fileBytes, passphrase)
                } catch (e: Exception) {
                    Log.e(TAG, "File decryption failed: ${e::class.simpleName}: ${e.message}", e)
                    null
                }
            }
            binding.progressSave.visibility = View.GONE
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
        OpenPgpKeyParser.parse(rawText)
            .onSuccess { info ->
                Log.i(TAG, "Parsed — userId='${info.userId}' protected=${info.isPassphraseProtected}")
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
            val p = getSecret(requireContext(), R.string.enter_key_passphrase, showPaste = true)
                ?: run { Log.d(TAG, "Passphrase cancelled"); return null }
            binding.progressSave.visibility = View.VISIBLE
            binding.btnSave.isEnabled = false
            val passphrase = p.toCharArray()
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
            binding.btnSave.isEnabled = viewModel.pendingAction.value == null && validatedBundle != null
        }
    }

    private fun resetKeyState() {
        viewModel.clearImportedKey()
        validatedBundle?.destroy()
        validatedBundle = null
    }

    private fun showKeyInfo(info: OpenPgpKeyInfo) {
        with(binding) {
            keyInfoUserId.text      = info.userId
            keyInfoAlgorithm.text   = info.algorithm
            keyInfoCreated.text     = info.creationDate.toDisplayDate()
            keyInfoExpires.text     = info.expirationDate?.toDisplayDate()
                ?: getString(R.string.openpgp_key_no_expiry)
            keyInfoFingerprint.text = info.fingerprint

            importInstructions.visibility = View.GONE
            keyDetailsContainer.visibility = View.VISIBLE

            root.post { root.requestLayout() }
        }
    }

    private fun hideKeyInfo() {
        binding.keyDetailsContainer.visibility = View.GONE
        binding.importInstructions.visibility = View.VISIBLE
    }
}
