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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import acab.naiveha.subrosa.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import acab.naiveha.subrosa.databinding.FragmentManagementBinding
import acab.naiveha.subrosa.ui.YubiKeyFragment
import acab.naiveha.subrosa.ui.setupCoffeeTipsClipboard
import com.yubico.yubikit.management.ManagementSession
import kotlin.time.Duration.Companion.milliseconds

class ManagementFragment : YubiKeyFragment<ManagementSession, ManagementViewModel>() {
    override val viewModel: ManagementViewModel by activityViewModels()

    private lateinit var binding: FragmentManagementBinding

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

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            val dash = getString(R.string.dash)
            state.errorInfo?.let { binding.deviceModel.text = "Error: $it" }

            if (!state.isDeviceConnected && !state.loading) {
                binding.progressLoading.visibility = View.GONE
                startDotAnimation(state.errorInfo)
                binding.deviceModel.text = dash
                binding.deviceFirmware.text = dash
                binding.staticPasswordStatus.text = dash
                binding.openpgpCardStatus.text = dash
                return@observe
            }

            binding.progressLoading.visibility = if (state.loading) View.VISIBLE else View.GONE
            stopDotAnimation()
            binding.emptyView.text = ""
            val infoLines = state.infoText.lines()
            binding.deviceModel.text = infoLines.find { it.startsWith("Device:") }?.substringAfter("Device:")?.trim() ?: dash
            binding.deviceFirmware.text = infoLines.find { it.startsWith("Firmware:") }?.substringAfter("Firmware:")?.trim() ?: dash

            renderPgpStatus(state.pgpStatus)
        }

        setupCoffeeTipsClipboard(binding.coffeeTipsContainer)
    }

    private fun renderPgpStatus(status: PgpStatus) {
        val dash = getString(R.string.dash)
        val (staticText, pgpText) = when (status) {
            is PgpStatus.YubiKey -> {
                val pgpLine = if (status.programmed) "programmed" else "not programmed"
                Pair("supported", pgpLine)
            }
            is PgpStatus.Nitrokey -> {
                val staticLine = "not supported"
                val pgpLine = when {
                    status.programmed != null -> if (status.programmed) "programmed" else "not programmed"
                    status.nfcUnsupported -> "not supported over NFC"
                    else -> null
                }
                Pair(staticLine, pgpLine ?: dash)
            }
            is PgpStatus.OtherDevice -> {
                val pgpLine = if (status.programmed) "programmed" else "not programmed"
                val staticLine = if (status.staticPasswordSupported == false) "not supported" else dash
                Pair(staticLine, pgpLine)
            }
            PgpStatus.AwaitingSecondTap -> Pair(dash, "tap again")
            PgpStatus.None -> Pair(dash, dash)
        }
        binding.staticPasswordStatus.text = staticText
        binding.openpgpCardStatus.text = pgpText
    }

    private var dotAnimationJob: Job? = null

    private fun startDotAnimation(errorInfo: String?) {
        if (dotAnimationJob?.isActive == true) return
        val baseText = errorInfo ?: getString(R.string.need_key_generic)
        dotAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            val steps = arrayOf("", ".", "..", "...")
            var step = 0
            while (isActive) {
                binding.emptyView.text = StringBuilder(baseText).append(steps[step]).toString()
                delay(500.milliseconds)
                step = (step + 1) % steps.size
            }
        }
    }

    private fun stopDotAnimation() {
        dotAnimationJob?.cancel()
        dotAnimationJob = null
    }

    override fun onDestroyView() {
        stopDotAnimation()
        super.onDestroyView()
    }
}
