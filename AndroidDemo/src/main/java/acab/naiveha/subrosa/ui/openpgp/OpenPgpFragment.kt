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
import acab.naiveha.subrosa.ui.getSecret
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yubico.yubikit.openpgp.Do
import com.yubico.yubikit.openpgp.KeyRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class OpenPgpFragment : Fragment() {
    private companion object {
        const val TAG = "OpenPgpFragment"
        const val READ_COMPLETE_STATUS = "Read complete"
    }
    private val activityViewModel: MainViewModel by activityViewModels()
    private val viewModel: OpenPgpViewModel by activityViewModels()
    private lateinit var binding: FragmentOpenpgpBinding
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var rawKeyArmor: String? = null
    private var validatedBundle: ImportBundle? = null
    private var saveInProgress = false
    private var readInProgress = false
    private var wipeInProgress = false
    private var importInProgress = false
    private var writeStatusClearJob: Job? = null
    private var readStatusClearJob: Job? = null
    private var wipeStatusClearJob: Job? = null
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
                binding.btnSave.isEnabled = !saveInProgress
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
            Log.d(TAG, "pendingAction → busy=$busy (save=$saveInProgress, read=$readInProgress, wipe=$wipeInProgress)")
            
            binding.progressSave.visibility = if (busy && saveInProgress) View.VISIBLE else View.GONE
            binding.progressRead.visibility = if (busy && readInProgress) View.VISIBLE else View.GONE
            binding.progressWipe.visibility = if (busy && wipeInProgress) View.VISIBLE else View.GONE
            
            binding.btnSave.isEnabled = !busy && validatedBundle != null
            binding.btnTestAction.isEnabled = !busy
            binding.btnDeleteOpenpgp.isEnabled = !busy
            
            if (!busy) {
                if (saveInProgress) {
                    saveInProgress = false
                    Log.i(TAG, "Save completed — resetting UI")
                    resetKeyState()
                }
                readInProgress = false
                wipeInProgress = false
            }
        }
        viewModel.connectedDevice.observe(viewLifecycleOwner) { deviceType ->
            Log.i(TAG, "Connected device type: $deviceType")
        }
        viewModel.writeStatus.observe(viewLifecycleOwner) { message ->
            writeStatusClearJob?.cancel()
            binding.writeStatus.text = message
            if (message == OpenPgpWriter.WRITE_COMPLETE_STATUS) {
                writeStatusClearJob = lifecycleScope.launch {
                    delay(2000.milliseconds)
                    viewModel.postWriteStatus("")
                }
            }
        }
        viewModel.readStatus.observe(viewLifecycleOwner) { message ->
            readStatusClearJob?.cancel()
            binding.readStatus.text = message
            if (message == READ_COMPLETE_STATUS) {
                readStatusClearJob = lifecycleScope.launch {
                    delay(2000.milliseconds)
                    viewModel.postReadStatus("")
                }
            }
        }
        viewModel.wipeStatus.observe(viewLifecycleOwner) { message ->
            wipeStatusClearJob?.cancel()
            binding.wipeStatus.text = message
            if (message == OpenPgpWriter.WIPE_COMPLETE_STATUS) {
                wipeStatusClearJob = lifecycleScope.launch {
                    delay(2000.milliseconds)
                    viewModel.postWipeStatus("")
                }
            }
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
            Log.d(TAG, "Save tapped — device=${viewModel.currentDeviceType} (writer decided once a device is connected)")

            lifecycleScope.launch(Dispatchers.Main) {
                var adminPin: CharArray? = null
                while (adminPin == null) {
                    val pin = getSecret(
                        requireContext(),
                        "Enter Device Admin PIN",
                        defaultValue = "12345678",
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                        clearTextByDefault = true
                    ) ?: run { Log.d(TAG, "Admin PIN cancelled"); return@launch }
                    if (pin.length < 8) {
                        Log.w(TAG, "Admin PIN too short (${pin.length} < 8) — re-showing dialog")
                        Toast.makeText(requireContext(), R.string.openpgp_admin_pin_too_short, Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d(TAG, "Admin PIN collected (length=${pin.length})")
                        adminPin = pin.toCharArray()
                    }
                }
                val pin = adminPin
                saveInProgress = true
                viewModel.pendingAction.value = {
                    val writer: OpenPgpWriter = when (viewModel.currentDeviceType) {
                        PgpDeviceType.NITROKEY -> NitrokeyPgpWriter
                        else                   -> YubiKeyPgpWriter
                    }
                    Log.i(TAG, "pendingAction — device=${viewModel.currentDeviceType} writer=${writer::class.simpleName}")
                    writer.program(this, bundle, pin, status = viewModel::postWriteStatus)
                }
            }
        }

        binding.btnTestAction.setOnClickListener {
            Log.d(TAG, "Read tapped — device=${viewModel.currentDeviceType}")
            readInProgress = true
            viewModel.pendingAction.value = {
                Log.i(TAG, "Reading card data…")
                var succeeded = false
                try {
                    viewModel.postReadStatus("Reading card status…")
                    val disc = getApplicationRelatedData().discretionary
                    val pw   = disc.pwStatus

                    viewModel.postReadStatus("Reading cardholder name…")
                    val name = runCatching {
                        String(getCardholderRelatedData().name, Charsets.UTF_8).trim()
                    }.recoverCatching {
                        String(getData(Do.NAME), Charsets.UTF_8).trim()
                    }.onFailure { e ->
                        Log.w(TAG, "Could not read cardholder name: ${e::class.simpleName}: ${e.message}")
                    }.getOrDefault("")

                    viewModel.postReadStatus("Reading login data…")
                    val login = runCatching {
                        String(getData(Do.LOGIN_DATA), Charsets.UTF_8).trim()
                    }.getOrDefault("")

                    val slots = listOf(KeyRef.SIG, KeyRef.DEC, KeyRef.AUT).map { ref ->
                        viewModel.postReadStatus("Reading slot ${ref.name}…")
                        val attr = runCatching { disc.getAlgorithmAttributes(ref) }.getOrNull()
                        val fp   = runCatching { disc.getFingerprint(ref) }.getOrNull()
                        val gt   = runCatching { disc.getGenerationTime(ref) }.getOrDefault(0)
                        val hasKey = fp?.any { it != 0.toByte() } == true
                        OpenPgpCardInfo.SlotInfo(
                            ref         = ref,
                            algorithm   = attr?.toDisplayString() ?: "—",
                            fingerprint = if (hasKey) fp.toFingerprintDisplay() else "—",
                            created     = if (gt != 0) gt.toDateDisplay() else "—",
                            hasKey      = hasKey,
                        )
                    }

                    val rawVersion = version.toString()
                    val displayVersion = if (rawVersion == "0.0.0") {
                        viewModel.currentFirmwareVersion ?: rawVersion
                    } else {
                        rawVersion
                    }

                    val info = OpenPgpCardInfo(
                        version         = displayVersion,
                        cardholderName  = name.fromNameDo(),
                        login           = login.ifBlank { "—" },
                        slots           = slots,
                        userPinRetries  = pw.attemptsUser,
                        resetRetries    = pw.attemptsReset,
                        adminPinRetries = pw.attemptsAdmin,
                    )

                    Log.i(TAG, "Card read: v=${info.version} name='${info.cardholderName}' " +
                               "slots=${slots.count { it.hasKey }} key(s)")
                    viewModel.onCardRead(info)
                    viewModel.postReadStatus(READ_COMPLETE_STATUS)
                    succeeded = true
                    null
                } finally {
                    if (!succeeded) viewModel.postReadStatus("")
                }
            }
        }

        binding.btnDeleteOpenpgp.setOnClickListener {
            if (activityViewModel.yubiKey.value == null) {
                viewModel.pendingAction.value = {
                    lifecycleScope.launch(Dispatchers.Main) {
                        showResetConfirmationDialog()
                    }
                    null
                }
            } else {
                showResetConfirmationDialog()
            }
        }
    }

    private fun showResetConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.openpgp_reset_title)
            .setMessage(R.string.openpgp_reset_message)
            .setPositiveButton(R.string.openpgp_reset_confirm) { _, _ ->
                Log.i(TAG, "Reset confirmed — device=${viewModel.currentDeviceType}")
                wipeInProgress = true
                viewModel.pendingAction.value = {
                    val writer: OpenPgpWriter = when (viewModel.currentDeviceType) {
                        PgpDeviceType.NITROKEY -> NitrokeyPgpWriter
                        else                   -> YubiKeyPgpWriter
                    }
                    Log.i(TAG, "wipe — device=${viewModel.currentDeviceType} writer=${writer::class.simpleName}")
                    writer.wipe(this, status = viewModel::postWipeStatus)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                Log.d(TAG, "Reset cancelled")
            }
            .show()
    }

    private fun showCardInfoDialog(info: OpenPgpCardInfo) {
        val text = buildString {
            appendLine("Firmware    ${info.version}")
            if (info.cardholderName != "—") appendLine("Name        ${info.cardholderName}")
            if (info.login          != "—") appendLine("Login       ${info.login}")
            appendLine()

            for (slot in info.slots) {
                val mark = if (slot.hasKey) "✓" else "·"
                appendLine("── ${slot.ref.name}  $mark  ${slot.algorithm}")
                if (slot.hasKey) {
                    appendLine("   ${slot.created}")
                    appendLine("   ${slot.fingerprint}")
                } else {
                    appendLine("   (empty slot)")
                }
                appendLine()
            }

            appendLine("── PIN retries")
            appendLine("   User         ${info.userPinRetries}")
            appendLine("   Reset code   ${info.resetRetries}")
            append(  "   Admin        ${info.adminPinRetries}")
        }

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val tv = TextView(requireContext()).apply {
            this.text = text
            typeface  = Typeface.MONOSPACE
            textSize  = 11.5f
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val sv = ScrollView(requireContext()).also { it.addView(tv) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.openpgp_card_details_title)
            .setView(sv)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showSubkeyDetailsDialog(info: OpenPgpKeyInfo) {
        val text = buildString {
            appendLine("User ID     ${info.userId}")
            appendLine("Algorithm   ${info.algorithm}")
            appendLine("Created     ${dateFormat.format(info.creationDate)}")
            val expiry = info.expirationDate?.let { dateFormat.format(it) } ?: "no expiry"
            appendLine("Expires     $expiry")
            appendLine("Fingerprint ${info.fingerprint}")
            appendLine()
            
            appendLine("── Keys (${info.keyCount})")
            for (subkey in info.keys) {
                appendLine("${subkey.usage.padEnd(12)} ${subkey.algorithm}")
                appendLine(subkey.fingerprint)
                val subExpiry = subkey.expirationDate?.let { dateFormat.format(it) } ?: "no expiry"
                appendLine("Created: ${dateFormat.format(subkey.creationDate)} | Expires: $subExpiry")
                appendLine()
            }
        }

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val tv = TextView(requireContext()).apply {
            this.text = text
            typeface  = Typeface.MONOSPACE
            textSize  = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val sv = ScrollView(requireContext()).also { it.addView(tv) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("OpenPGP key details")
            .setView(sv)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Remove Key") { _, _ -> resetKeyState() }
            .show()
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

                var ring: org.bouncycastle.openpgp.PGPSecretKeyRing? = withContext(Dispatchers.IO) {
                    try {
                        OpenPgpFileImporter.decrypt(fileBytes, CharArray(0))
                    } catch (e: Exception) {
                        null
                    }
                }

                while (ring == null) {
                    val passphraseStr = getSecret(requireActivity(), R.string.enter_file_passphrase, showPaste = true)
                        ?: run { Log.d(TAG, "File passphrase cancelled"); return@launch }
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

    private fun validateAndStoreBundle(armor: String, info: OpenPgpKeyInfo) {
        lifecycleScope.launch(Dispatchers.Main) {
            validatedBundle?.destroy()
            validatedBundle = null

            if (info.isPassphraseProtected) {
                Log.d(TAG, "Key is passphrase-protected — collecting passphrase…")
                var bundle: ImportBundle? = null
                while (bundle == null) {
                    val p = getSecret(requireContext(), R.string.enter_key_passphrase, showPaste = true)
                        ?: run { Log.d(TAG, "Passphrase cancelled"); resetKeyState(); return@launch }
                    binding.progressSave.visibility = View.VISIBLE
                    binding.btnSave.isEnabled = false
                    val passphrase = p.toCharArray()
                    bundle = withContext(Dispatchers.IO) {
                        try { OpenPgpKeyImporter.prepare(armor, passphrase) }
                        catch (e: Exception) { Log.e(TAG, "prepare() failed: ${e.message}", e); null }
                        finally { passphrase.fill('\u0000') }
                    }
                    binding.progressSave.visibility = View.GONE
                    if (bundle == null) {
                        viewModel.clearImportedKey()
                        viewModel.postResult(Result.failure(Exception(getString(R.string.openpgp_wrong_passphrase))))
                    }
                }
                validatedBundle = bundle
            } else {
                binding.progressSave.visibility = View.VISIBLE
                binding.btnSave.isEnabled = false
                val bundle = withContext(Dispatchers.IO) {
                    runCatching { OpenPgpKeyImporter.prepare(armor) }
                        .onFailure { Log.e(TAG, "prepare() failed: ${it.message}", it) }
                        .getOrNull()
                }
                binding.progressSave.visibility = View.GONE
                if (bundle == null) {
                    viewModel.clearImportedKey()
                    viewModel.postResult(Result.failure(Exception(getString(R.string.openpgp_key_prepare_failed))))
                    return@launch
                }
                validatedBundle = bundle
            }
            binding.btnSave.isEnabled = viewModel.pendingAction.value == null && validatedBundle != null
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        validatedBundle?.destroy()
        validatedBundle = null
    }

    private fun resetKeyState() {
        viewModel.clearImportedKey()
        validatedBundle?.destroy()
        validatedBundle = null
        saveInProgress = false
    }

    private fun showKeyInfo(info: OpenPgpKeyInfo) {
        with(binding) {
            keyInfoUserId.text      = info.userId
            keyInfoAlgorithm.text   = info.algorithm
            keyInfoCreated.text     = dateFormat.format(info.creationDate)
            keyInfoExpires.text     = info.expirationDate
                ?.let { dateFormat.format(it) }
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
