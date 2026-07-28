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

package acab.naiveha.subrosa.ui

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import acab.naiveha.subrosa.MainViewModel

import org.slf4j.LoggerFactory

import java.io.Closeable

abstract class YubiKeyFragment<App : Closeable, VM : YubiKeyViewModel<App>> : Fragment() {

    private val logger = LoggerFactory.getLogger(YubiKeyFragment::class.java)

    private val activityViewModel: MainViewModel by activityViewModels()
    protected abstract val viewModel: VM

    private lateinit var yubiKeyPrompt: YubiKeyPromptDialog

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        yubiKeyPrompt = YubiKeyPromptDialog(requireContext()) { viewModel.pendingAction.value = null }

        bindDeviceActions(
            viewModel = viewModel,
            activityViewModel = activityViewModel,
            prompt = yubiKeyPrompt,
            alwaysReadOnConnect = true,
            isTapSuspended = { isYubiKeyTapSuspended() },
            shouldClearOnDisconnect = { shouldClearOnDisconnect() },
            onDisconnected = { viewModel.onDeviceDisconnected() },
        )

        viewModel.result.observe(viewLifecycleOwner) { result ->
            result.onSuccess {
                it?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                logger.error("Error:", it)
                errorVibrate()
                Toast.makeText(context, it.message ?: "No message", Toast.LENGTH_SHORT).show()
            }
            viewModel.clearResult()
        }
    }

    override fun onPause() {
        if (yubiKeyPrompt.isShowing) {
            yubiKeyPrompt.dismiss()
        }
        super.onPause()
    }

    protected open fun shouldClearOnDisconnect(): Boolean = true

    protected open fun isYubiKeyTapSuspended(): Boolean = false

    protected fun errorVibrate() {
        getVibrator().vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun getVibrator(): Vibrator {
        val vibratorManager = requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        return vibratorManager.defaultVibrator
    }
}
