/*
 * Copyright (C) 2022-2023 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package acab.naiveha.subrosa.ui.management

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import acab.naiveha.subrosa.MainViewModel
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentManagementBinding
import acab.naiveha.subrosa.ui.YubiKeyFragment
import acab.naiveha.subrosa.ui.YubiKeyPromptDialog
import acab.naiveha.subrosa.ui.bindAutoClearStatus
import acab.naiveha.subrosa.ui.collectPin
import acab.naiveha.subrosa.ui.getSecret
import acab.naiveha.subrosa.ui.showOpenPgpAppletResetDialog
import acab.naiveha.subrosa.ui.openpgp.NitrokeyPgpWriter
import acab.naiveha.subrosa.ui.openpgp.OpenPgpOperation
import acab.naiveha.subrosa.ui.openpgp.OpenPgpViewModel
import acab.naiveha.subrosa.ui.openpgp.OpenPgpWriter
import acab.naiveha.subrosa.ui.openpgp.OpenPgpWriterUtils
import acab.naiveha.subrosa.ui.openpgp.PgpDeviceType
import acab.naiveha.subrosa.ui.openpgp.YubiKeyPgpWriter
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.InvalidPinException
import com.yubico.yubikit.management.ManagementSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManagementFragment : YubiKeyFragment<ManagementSession, ManagementViewModel>() {
    private companion object {
        const val TAG = "ManagementFragment"
    }
    override val viewModel: ManagementViewModel by activityViewModels()

    private val openPgpViewModel: OpenPgpViewModel by activityViewModels()

    private val activityViewModel: MainViewModel by activityViewModels()

    private lateinit var binding: FragmentManagementBinding

    private lateinit var openPgpPrompt: YubiKeyPromptDialog

    override fun shouldClearOnDisconnect(): Boolean =
        openPgpViewModel.currentOperation.value == OpenPgpOperation.NONE

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        binding = FragmentManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.errorInfo.observe(viewLifecycleOwner) { errorString ->
            errorString?.let { binding.info.text = "Error:\n$it" }
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state == null) {
                binding.emptyView.visibility = View.VISIBLE
                binding.connectedContent.visibility = View.GONE
                binding.pgpInfo.text = ""
                binding.info.text = ""
                return@observe
            }

            binding.emptyView.visibility = View.GONE
            binding.connectedContent.visibility = View.VISIBLE
            binding.managementActions.visibility = if (state.showManagementActions) View.VISIBLE else View.GONE
            binding.info.text = state.infoText
            renderPgpStatus(state.pgpStatus)

            val retries = state.pinRetries
            if (retries != null) {
                binding.pinRetries.visibility = View.VISIBLE
                val userRetries = if (retries.admin == 0) 0 else retries.user
                binding.pinRetries.text = getString(R.string.openpgp_pin_retries, userRetries, retries.admin)

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
            } else {
                binding.pinRetries.visibility = View.GONE
                binding.btnChangeUserPin.visibility = View.VISIBLE
                binding.btnChangeUserPin.text = getString(R.string.openpgp_btn_change_user_pin)
                binding.btnChangeAdminPin.text = getString(R.string.openpgp_btn_change_admin_pin)
            }
        }

        openPgpPrompt = YubiKeyPromptDialog(requireContext()) { openPgpViewModel.pendingAction.value = null }

        openPgpViewModel.pendingAction.observe(viewLifecycleOwner) {
            val busy = it != null
            val op = openPgpViewModel.currentOperation.value ?: OpenPgpOperation.NONE
            val adminBusy = busy && (op == OpenPgpOperation.CHANGE_ADMIN_PIN || op == OpenPgpOperation.RESET_ADMIN_PIN)
            val userBusy = busy && (op == OpenPgpOperation.CHANGE_USER_PIN || op == OpenPgpOperation.RESET_USER_PIN)
            binding.progressChangeAdminPin.visibility = if (adminBusy) View.VISIBLE else View.GONE
            binding.progressChangeUserPin.visibility = if (userBusy) View.VISIBLE else View.GONE
            binding.btnChangeAdminPin.isEnabled = !busy
            binding.btnChangeUserPin.isEnabled = !busy
            if (busy) {
                val device = activityViewModel.yubiKey.value
                if (device != null) {
                    onOpenPgpDevice(device)
                } else {
                    openPgpPrompt.setHelpText(getString(R.string.yubikit_prompt_plug_in_or_tap))
                    openPgpPrompt.show()
                }
            }
        }

        activityViewModel.yubiKey.observe(viewLifecycleOwner) { device ->
            if (device != null && openPgpViewModel.pendingAction.value != null) {
                onOpenPgpDevice(device)
            }
        }

        bindAutoClearStatus(
            openPgpViewModel.pinChangeStatus,
            binding.pinChangeStatus,
            OpenPgpViewModel.PIN_CHANGE_COMPLETE_STATUS,
            OpenPgpViewModel.PIN_RESET_COMPLETE_STATUS,
            OpenPgpWriter.WIPE_COMPLETE_STATUS,
            hideWhenBlank = true,
            shouldHide = { it.contains("Remaining attempts", ignoreCase = true) },
        ) {
            openPgpViewModel.postPinChangeStatus(it)
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

        binding.coffeeTipsContainer.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val btcAddress = getString(R.string.btc_address)
            val clip = ClipData.newPlainText("BTC address", btcAddress)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard_msg, btcAddress), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        if (::openPgpPrompt.isInitialized && openPgpPrompt.isShowing) {
            openPgpPrompt.dismiss()
        }
        super.onPause()
    }

    private fun renderPgpStatus(status: PgpStatus) {
        binding.pgpInfo.text = when (status) {
            is PgpStatus.YubiKey -> {
                val pgpLine = getString(
                    if (status.programmed) R.string.openpgp_card_programmed else R.string.openpgp_card_not_programmed
                )
                "${getString(R.string.static_password_supported)}\n$pgpLine"
            }
            is PgpStatus.Nitrokey -> {
                val staticLine = getString(R.string.static_password_not_supported)
                val pgpLine = when {
                    status.programmed != null -> getString(
                        if (status.programmed) R.string.openpgp_card_programmed else R.string.openpgp_card_not_programmed
                    )
                    status.nfcUnsupported -> getString(R.string.openpgp_not_supported_nfc)
                    else -> null
                }
                if (pgpLine != null) "$staticLine\n$pgpLine" else staticLine
            }
            is PgpStatus.OtherDevice -> getString(
                if (status.programmed) R.string.openpgp_card_programmed else R.string.openpgp_card_not_programmed
            )
            PgpStatus.AwaitingSecondTap -> getString(R.string.openpgp_tap_again)
            PgpStatus.None -> ""
        }
    }

    private fun onOpenPgpDevice(device: YubiKeyDevice) {
        if (openPgpPrompt.isShowing) {
            openPgpPrompt.dismiss()
        }

        lifecycleScope.launch {
            withContext(activityViewModel.singleDispatcher) {
                openPgpViewModel.onYubiKeyDevice(device)
                if (device is NfcYubiKeyDevice) {
                    device.remove {}
                }
                Unit
            }
        }
    }

    private fun onChangePinClicked(isAdmin: Boolean) {
        val label = if (isAdmin) "Admin" else "User"
        val minLength = if (isAdmin) 8 else 6
        val tooShortRes = if (isAdmin) R.string.openpgp_admin_pin_too_short else R.string.openpgp_user_pin_too_short
        val numericPassword = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val operation = if (isAdmin) OpenPgpOperation.CHANGE_ADMIN_PIN else OpenPgpOperation.CHANGE_USER_PIN

        Log.d(TAG, "Change $label PIN tapped")
        openPgpViewModel.currentOperation.value = operation

        lifecycleScope.launch(Dispatchers.Main) {
            val currentPin = collectPin(
                requireContext(),
                "Enter current Device $label PIN",
                minLength = minLength,
                tooShortRes = tooShortRes,
                inputType = numericPassword,
                tag = TAG,
                logLabel = "Current $label PIN",
            ) ?: run {
                openPgpViewModel.currentOperation.value = OpenPgpOperation.NONE
                return@launch
            }

            val newPin = collectNewPin(label, minLength, tooShortRes, numericPassword)
                ?: run {
                    Log.d(TAG, "Change $label PIN cancelled (new PIN)")
                    currentPin.fill('\u0000')
                    openPgpViewModel.currentOperation.value = OpenPgpOperation.NONE
                    return@launch
                }

            val oldPin = currentPin
            val finalNewPin = newPin
            openPgpViewModel.pendingAction.value = {
                try {
                    Log.i(TAG, "pendingAction — changing $label PIN")
                    if (isAdmin) {
                        OpenPgpWriterUtils.changeAdminPin(this, oldPin, finalNewPin, TAG, status = openPgpViewModel::postPinChangeStatus)
                    } else {
                        OpenPgpWriterUtils.changeUserPin(this, oldPin, finalNewPin, TAG, status = openPgpViewModel::postPinChangeStatus)
                    }
                    openPgpViewModel.postPinChangeStatus(OpenPgpViewModel.PIN_CHANGE_COMPLETE_STATUS)
                    viewModel.refreshPinRetries(this)
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "Change $label PIN failed: ${e.message}")

                    if (e is InvalidPinException) {
                        if (isAdmin) {
                            viewModel.updatePinRetries(admin = e.attemptsRemaining)
                            lifecycleScope.launch(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Admin PIN change failed", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            viewModel.updatePinRetries(user = e.attemptsRemaining)
                            lifecycleScope.launch(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "User PIN change failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    openPgpViewModel.postPinChangeStatus(e.message ?: "Failed to change $label PIN")
                    null
                } finally {
                    oldPin.fill('\u0000')
                    finalNewPin.fill('\u0000')
                    Log.d(TAG, "Change $label PIN — old/new PIN zeroed")
                    lifecycleScope.launch(Dispatchers.Main) { openPgpViewModel.currentOperation.value = OpenPgpOperation.NONE }
                }
            }
        }
    }

    private fun onResetUserPinClicked() {
        val numericPassword = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        Log.d(TAG, "Reset blocked User PIN tapped")
        openPgpViewModel.currentOperation.value = OpenPgpOperation.RESET_USER_PIN

        lifecycleScope.launch(Dispatchers.Main) {
            val adminPin = collectPin(
                requireContext(),
                "Enter Device Admin PIN",
                minLength = 8,
                tooShortRes = R.string.openpgp_admin_pin_too_short,
                inputType = numericPassword,
                tag = TAG,
                logLabel = "Admin PIN (for User PIN reset)",
            ) ?: run {
                openPgpViewModel.currentOperation.value = OpenPgpOperation.NONE
                return@launch
            }

            val newUserPin = collectNewPin("User", minLength = 6, tooShortRes = R.string.openpgp_user_pin_too_short, numericPassword)
                ?: run {
                    Log.d(TAG, "Reset User PIN cancelled (new PIN)")
                    adminPin.fill('\u0000')
                    openPgpViewModel.currentOperation.value = OpenPgpOperation.NONE
                    return@launch
                }

            openPgpViewModel.pendingAction.value = {
                try {
                    Log.i(TAG, "pendingAction — resetting blocked User PIN")
                    OpenPgpWriterUtils.resetBlockedUserPin(this, adminPin, newUserPin, TAG, status = openPgpViewModel::postPinChangeStatus)
                    openPgpViewModel.postPinChangeStatus(OpenPgpViewModel.PIN_RESET_COMPLETE_STATUS)
                    viewModel.refreshPinRetries(this)
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "Reset User PIN failed: ${e.message}")

                    if (e is InvalidPinException) {
                        viewModel.updatePinRetries(admin = e.attemptsRemaining)
                        lifecycleScope.launch(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Reset User PIN failed: wrong Admin PIN", Toast.LENGTH_SHORT).show()
                        }
                    }

                    openPgpViewModel.postPinChangeStatus(e.message ?: "Failed to reset User PIN")
                    null
                } finally {
                    adminPin.fill('\u0000')
                    newUserPin.fill('\u0000')
                    Log.d(TAG, "Reset User PIN — Admin PIN/new User PIN zeroed")
                    lifecycleScope.launch(Dispatchers.Main) { openPgpViewModel.currentOperation.value = OpenPgpOperation.NONE }
                }
            }
        }
    }

    private fun onResetOpenPgpClicked() {
        Log.d(TAG, "Reset OpenPGP applet tapped (Admin PIN retries exhausted)")
        openPgpViewModel.currentOperation.value = OpenPgpOperation.RESET_ADMIN_PIN
        showOpenPgpResetConfirmationDialog()
    }

    private fun writerFor(type: PgpDeviceType?): OpenPgpWriter = when (type) {
        PgpDeviceType.NITROKEY -> NitrokeyPgpWriter
        else                   -> YubiKeyPgpWriter
    }

    private fun showOpenPgpResetConfirmationDialog() {
        showOpenPgpAppletResetDialog(
            TAG,
            onConfirmed = {
                Log.i(TAG, "OpenPGP applet reset confirmed — device=${openPgpViewModel.connectedDevice.value?.type}")
                openPgpViewModel.pendingAction.value = {
                    try {
                        val writer = writerFor(openPgpViewModel.connectedDevice.value?.type)
                        Log.i(TAG, "pendingAction — resetting OpenPGP applet, writer=${writer::class.simpleName}")
                        writer.wipe(this, status = openPgpViewModel::postPinChangeStatus)
                        viewModel.updatePinRetries(user = 3, admin = 3)
                        null
                    } catch (e: Exception) {
                        Log.w(TAG, "Reset OpenPGP applet failed: ${e.message}")
                        openPgpViewModel.postPinChangeStatus(e.message ?: "Failed to reset OpenPGP applet")
                        null
                    } finally {
                        lifecycleScope.launch(Dispatchers.Main) { openPgpViewModel.currentOperation.value = OpenPgpOperation.NONE }
                    }
                }
            },
            onCancelled = { openPgpViewModel.currentOperation.value = OpenPgpOperation.NONE },
        )
    }

    private suspend fun collectNewPin(label: String, minLength: Int, tooShortRes: Int, inputType: Int): CharArray? {
        while (true) {
            val entered = collectPin(
                requireContext(),
                "Enter new Device $label PIN",
                minLength = minLength,
                tooShortRes = tooShortRes,
                inputType = inputType,
                tag = TAG,
                logLabel = "New $label PIN",
            ) ?: return null

            val confirm = getSecret(
                requireContext(),
                "Confirm new Device $label PIN",
                inputType = inputType,
            ) ?: run { Log.d(TAG, "New $label PIN confirmation cancelled"); return null }

            if (confirm != String(entered)) {
                Log.w(TAG, "New $label PIN confirmation did not match — re-showing dialog")
                Toast.makeText(requireContext(), R.string.openpgp_new_pin_mismatch, Toast.LENGTH_SHORT).show()
                continue
            }

            Log.d(TAG, "New $label PIN collected and confirmed (length=${entered.size})")
            return entered
        }
    }
}
