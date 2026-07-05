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

import android.graphics.Color
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import acab.naiveha.subrosa.MainViewModel
import acab.naiveha.subrosa.R
import com.google.android.material.button.MaterialButton
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.slf4j.LoggerFactory

import java.io.Closeable
import androidx.core.graphics.drawable.toDrawable

abstract class YubiKeyFragment<App : Closeable, VM : YubiKeyViewModel<App>> : Fragment() {

    private val logger = LoggerFactory.getLogger(YubiKeyFragment::class.java)

    private val activityViewModel: MainViewModel by activityViewModels()
    protected abstract val viewModel: VM

    private lateinit var yubiKeyPrompt: android.app.Dialog
    private lateinit var emptyText: TextView

    private lateinit var promptHelpText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        emptyText = view.findViewById(R.id.empty_view)
        emptyText.visibility = View.VISIBLE

        val themedContext = ContextThemeWrapper(requireContext(), com.yubico.yubikit.android.R.style.YubiKitPromptDialogTheme)
        val promptView = LayoutInflater.from(themedContext).inflate(com.yubico.yubikit.android.R.layout.yubikit_yubikey_prompt_content, null)
        
        val primaryColor = ContextCompat.getColor(themedContext, com.yubico.yubikit.android.R.color.yubikit_text_color_primary)
        val secondaryColor = ContextCompat.getColor(themedContext, com.yubico.yubikit.android.R.color.yubikit_text_color_secondary)
        
        promptView.findViewById<TextView>(com.yubico.yubikit.android.R.id.yubikit_prompt_title).apply {
            text = getString(R.string.yubikit_prompt_activity_title)
            setTextColor(primaryColor)
        }
        promptHelpText = promptView.findViewById<TextView>(com.yubico.yubikit.android.R.id.yubikit_prompt_help_text_view).apply {
            text = getString(R.string.yubikit_prompt_plug_in_or_tap)
            setTextColor(secondaryColor)
        }

        promptView.findViewById<Button>(com.yubico.yubikit.android.R.id.yubikit_prompt_cancel_btn).apply {
            background = Color.TRANSPARENT.toDrawable()
            stateListAnimator = null
            elevation = 0f
            if (this is MaterialButton) {
                strokeWidth = 0
            }
            setTextColor(primaryColor)
            setOnClickListener { yubiKeyPrompt.cancel() }
        }

        yubiKeyPrompt = android.app.Dialog(requireContext(), com.yubico.yubikit.android.R.style.YubiKitPromptDialogTheme).apply {
            setContentView(promptView)
            setOnCancelListener { viewModel.pendingAction.value = null }
        }

        activityViewModel.yubiKey.observe(viewLifecycleOwner) {
            if (it != null) {
                onYubiKey(it)
            } else {
                emptyText.setText(R.string.need_key)
            }
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            result.onSuccess {
                it?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                logger.error("Error:", it)
                Toast.makeText(context, it.message ?: "No message", Toast.LENGTH_SHORT).show()
                if (it is ApplicationNotAvailableException) {
                    emptyText.setText(R.string.app_missing)
                }
            }
            viewModel.clearResult()
        }

        viewModel.pendingAction.observe(viewLifecycleOwner) {
            if (it != null) {
                activityViewModel.yubiKey.value.let { device ->
                    if (device != null) {
                        onYubiKey(device)
                    } else {
                        promptHelpText.text = getString(R.string.yubikit_prompt_plug_in_or_tap)
                        yubiKeyPrompt.show()
                    }
                }
            }
        }
    }

    override fun onPause() {
        if (yubiKeyPrompt.isShowing) {
            yubiKeyPrompt.dismiss()
        }
        super.onPause()
    }

    private fun onYubiKey(it: YubiKeyDevice) {
        lifecycleScope.launch {
            withContext(activityViewModel.singleDispatcher) {
                viewModel.onYubiKeyDevice(it)

                if (it is NfcYubiKeyDevice) {
                    withContext(Dispatchers.Main) {
                        if (yubiKeyPrompt.isShowing) {
                            promptHelpText.text = resources.getString(R.string.remove_key)
                        } else {
                            emptyText.setText(R.string.remove_key)
                        }
                    }
                    it.remove(yubiKeyPrompt::dismiss)
                } else if (yubiKeyPrompt.isShowing) {
                    yubiKeyPrompt.dismiss()
                }
                Unit
            }
        }
    }
}
