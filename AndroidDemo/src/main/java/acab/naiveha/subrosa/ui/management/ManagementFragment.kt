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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.fragment.app.activityViewModels
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentManagementBinding
import acab.naiveha.subrosa.ui.YubiKeyFragment
import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.management.Capability
import com.yubico.yubikit.management.DeviceConfig
import com.yubico.yubikit.management.ManagementSession
import com.yubico.yubikit.support.DeviceUtil

class ManagementFragment : YubiKeyFragment<ManagementSession, ManagementViewModel>() {
    override val viewModel: ManagementViewModel by activityViewModels()

    private lateinit var binding: FragmentManagementBinding

    private val checkboxIds = mapOf(
            (Transport.USB to Capability.OTP) to R.id.checkbox_usb_otp,
            (Transport.USB to Capability.U2F) to R.id.checkbox_usb_u2f,
            (Transport.USB to Capability.PIV) to R.id.checkbox_usb_piv,
            (Transport.USB to Capability.OATH) to R.id.checkbox_usb_oath,
            (Transport.USB to Capability.OPENPGP) to R.id.checkbox_usb_pgp,
            (Transport.USB to Capability.FIDO2) to R.id.checkbox_usb_fido2,

            (Transport.NFC to Capability.OTP) to R.id.checkbox_nfc_otp,
            (Transport.NFC to Capability.U2F) to R.id.checkbox_nfc_u2f,
            (Transport.NFC to Capability.PIV) to R.id.checkbox_nfc_piv,
            (Transport.NFC to Capability.OATH) to R.id.checkbox_nfc_oath,
            (Transport.NFC to Capability.OPENPGP) to R.id.checkbox_nfc_pgp,
            (Transport.NFC to Capability.FIDO2) to R.id.checkbox_nfc_fido2
    )

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
        binding.applicationTable.visibility = View.GONE
        binding.save.visibility             = View.GONE

        viewModel.errorInfo.observe(viewLifecycleOwner) { errorString ->
            errorString?.let { binding.info.text = "Error:\n$it" }
        }

        viewModel.deviceInfo.observe(viewLifecycleOwner) { connected ->
            if (connected == null) {
                binding.emptyView.visibility        = View.VISIBLE
                binding.applicationTable.visibility = View.GONE
                binding.save.visibility             = View.GONE
                return@observe
            }

            binding.emptyView.visibility = View.GONE

            val yubikeyInfo = connected.deviceInfo
            if (yubikeyInfo != null) {
                // ── Yubico path: full DeviceInfo available ──────────────────
                binding.info.text =
                    "Device: ${DeviceUtil.getName(yubikeyInfo, connected.type)}\n" +
                            "Form factor: ${yubikeyInfo.formFactor.name}\n" +
                            "Firmware: ${yubikeyInfo.version}\n" +
                            "Serial: ${yubikeyInfo.serialNumber}\n" +
                            "FIPS: ${yubikeyInfo.isFips}\n" +
                            "SKY: ${yubikeyInfo.isSky}\n" +
                            "Locked: ${yubikeyInfo.isLocked}\n" +
                            "Auto eject timeout: ${yubikeyInfo.config.autoEjectTimeout}\n" +
                            "Challenge response timeout: ${yubikeyInfo.config.challengeResponseTimeout}\n" +
                            "ATR: ${connected.atr}"

                checkboxIds.forEach { (transportCapability, id) ->
                    val (transport, capability) = transportCapability
                    view.findViewById<CheckBox>(id)?.let { cb ->
                        if (yubikeyInfo.getSupportedCapabilities(transport) and capability.bit != 0) {
                            cb.isChecked = (yubikeyInfo.config.getEnabledCapabilities(transport) ?: 0) and capability.bit != 0
                        } else {
                            cb.visibility = View.GONE
                        }
                    }
                }
            } else {
                // ── Nitrokey path: only FIDO2 getInfo data available ────────
                // Hide capability checkboxes (Nitrokey management protocol is different)
                checkboxIds.values.forEach { id ->
                    view.findViewById<CheckBox>(id)?.visibility = View.GONE
                }
                binding.info.text =
                    "Device: ${connected.type?.name ?: "Unknown"}\n" +
                            connected.atr +
                            "\n\n(Management app not available on this device.\n" +
                            "Use nitropy or Nitrokey App 2 for full configuration.)"
            }
        }

        binding.save.setOnClickListener {
            // Nitrokey doesn't support the Yubico management write protocol;
            // only show the save button when a Yubico DeviceInfo was loaded.
            viewModel.deviceInfo.value?.deviceInfo ?: return@setOnClickListener
            viewModel.pendingAction.value = {
                updateDeviceConfig(DeviceConfig.Builder().apply {
                    Transport.values().forEach { transport ->
                        enabledCapabilities(transport, checkboxIds.filter { entry ->
                            entry.key.first == transport &&
                                    view.findViewById<CheckBox>(entry.value).isChecked
                        }.map { it.key.second.bit }.sum())
                    }
                }.build(), true, null, null)
                "Configuration updated"
            }
        }
    }
}