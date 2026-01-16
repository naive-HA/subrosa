/*
 * Copyright (C) 2022 Yubico.
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

package acab.naiveha.subrosa.ui.yubiotp

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.launch
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import acab.naiveha.subrosa.MainViewModel
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentStaticpwdBinding
import com.yubico.yubikit.android.ui.OtpActivity
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.StaticPasswordSlotConfiguration
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import android.os.VibratorManager

class StaticPwdFragment : Fragment() {
    class OtpContract : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: Context, input: Unit): Intent = Intent(context, OtpActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): String? {
            return intent?.getStringExtra(OtpActivity.EXTRA_OTP)
        }
    }

    private val requestOtp = registerForActivityResult(OtpContract()) {
        activityViewModel.setYubiKeyListenerEnabled(true)
        viewModel.postResult(Result.success(when (it) {
            null -> "Cancelled by user"
            else -> "Read OTP: $it"
        }))
    }

    private val activityViewModel: MainViewModel by activityViewModels()
    private val viewModel: OtpViewModel by activityViewModels()
    private lateinit var binding: FragmentStaticpwdBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentStaticpwdBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.checkboxShowPwdId.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.editTextStaticpwdId.transformationMethod = HideReturnsTransformationMethod.getInstance()
                binding.editTextStaticpwdId.setSelection(binding.editTextStaticpwdId.text?.length ?: 0)
            } else {
                binding.editTextStaticpwdId.transformationMethod = PasswordTransformationMethod.getInstance()
                binding.editTextStaticpwdId.setSelection(binding.editTextStaticpwdId.text?.length ?: 0)
            }
        }

        binding.textLayoutStaticpwdId.setEndIconOnClickListener {
            if (binding.editTextStaticpwdId.text.toString().length > 0){
                binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_content_paste_24dp)
                binding.editTextStaticpwdId.setText("")
                binding.editTextStaticpwdId.requestFocus()
                val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.showSoftInput(binding.editTextStaticpwdId, InputMethodManager.SHOW_IMPLICIT)
            } else {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.text?.let {
                    try{
                        if (it.length > 38){
                            throw IllegalStateException("Static password cannot exceed 38 characters")
                        } else {
                            binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_cancel_24dp)
                            binding.editTextStaticpwdId.append(it)
                            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
                            binding.editTextStaticpwdId.setSelection(binding.editTextStaticpwdId.text?.length ?: 0)
                        }
                    } catch (e: Exception) {
                        viewModel.postResult(Result.failure(e))
                        getVibrator().vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            }
        }

        binding.editTextStaticpwdId.addTextChangedListener(object: TextWatcher{
            override fun afterTextChanged(s: Editable?) {
                var staticpwd = binding.editTextStaticpwdId.text.toString()
                if (staticpwd.length == 0) {
                    binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_content_paste_24dp)
                    binding.textLayoutStaticpwdId.hint = "Type or paste from clipboard"
                } else if (staticpwd.length > 0){
                    binding.textLayoutStaticpwdId.endIconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_cancel_24dp)
                    binding.textLayoutStaticpwdId.hint = "Static password"
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }
        })
        binding.keyoardUs.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }
        binding.keyoardUs.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.extrasTabFront.isChecked = false
                binding.extrasTabFront.isEnabled = true
                binding.extrasTabEnd.isChecked = false
                binding.extrasTabEnd.isEnabled = true
                binding.extrasCr.isChecked = false
                binding.extrasCr.isEnabled = true
            }
        }

        binding.keyoardUk.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }
        binding.keyoardUk.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.extrasTabFront.isChecked = false
                binding.extrasTabFront.isEnabled = true
                binding.extrasTabEnd.isChecked = false
                binding.extrasTabEnd.isEnabled = true
                binding.extrasCr.isChecked = false
                binding.extrasCr.isEnabled = true
            }
        }

        binding.keyoardDe.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }
        binding.keyoardDe.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.extrasTabFront.isChecked = false
                binding.extrasTabFront.isEnabled = true
                binding.extrasTabEnd.isChecked = false
                binding.extrasTabEnd.isEnabled = true
                binding.extrasCr.isChecked = false
                binding.extrasCr.isEnabled = true
            }
        }

        binding.keyoardFr.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }
        binding.keyoardFr.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.extrasTabFront.isChecked = false
                binding.extrasTabFront.isEnabled = true
                binding.extrasTabEnd.isChecked = false
                binding.extrasTabEnd.isEnabled = true
                binding.extrasCr.isChecked = false
                binding.extrasCr.isEnabled = true
            }
        }

        binding.keyoardIt.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }
        binding.keyoardIt.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.extrasTabFront.isChecked = false
                binding.extrasTabFront.isEnabled = true
                binding.extrasTabEnd.isChecked = false
                binding.extrasTabEnd.isEnabled = true
                binding.extrasCr.isChecked = false
                binding.extrasCr.isEnabled = true
            }
        }

        binding.keyoardModhex.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }
        binding.keyoardModhex.setOnCheckedChangeListener { _, isChecked ->
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
            if (isChecked) {
                binding.extrasTabFront.isChecked = false
                binding.extrasTabFront.isEnabled = false
                binding.extrasTabEnd.isChecked = false
                binding.extrasTabEnd.isEnabled = false
                binding.extrasCr.isChecked = false
                binding.extrasCr.isEnabled = false
            }
        }

        binding.extrasTabFront.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }

        binding.extrasTabEnd.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }

        binding.extrasCr.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }

        binding.radioSlot1.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }

        binding.radioSlot2.setOnClickListener {
            val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }

        binding.btnSaveStaticpwd.setOnClickListener {
            try {
                var keyboard = when (binding.keyboardRadio.checkedRadioButtonId) {
                    R.id.keyoard_us -> "en_US"
                    R.id.keyoard_uk -> "en_UK"
                    R.id.keyoard_de -> "de_DE"
                    R.id.keyoard_fr -> "fr_FR"
                    R.id.keyoard_it -> "it_IT"
                    R.id.keyoard_modhex -> "en_MODHEX"
                    else -> "en_US"
                }

                var staticpwd = binding.editTextStaticpwdId.text.toString()
                if (staticpwd.length > 38){
                    throw IllegalStateException("Static password cannot exceed 38 characters")
                } else if (staticpwd.length == 0){
                    staticpwd += "Hello kitty"
                    keyboard = "en_US"
                }

                if (binding.extrasTabFront.isChecked()){
                    staticpwd = '\t' + staticpwd
                }

                if (binding.extrasTabEnd.isChecked()){
                    staticpwd += '\t'
                }

                val scancodes = Keyboard().encode(staticpwd, keyboard)
                val configuration = StaticPasswordSlotConfiguration(scancodes)

                if (binding.extrasCr.isChecked()){
                    configuration.appendCr(true)
                } else {
                    configuration.appendCr(false)
                }

                val slot = when (binding.slotRadio.checkedRadioButtonId) {
                    R.id.radio_slot_1 -> Slot.ONE
                    R.id.radio_slot_2 -> Slot.TWO
                    else -> Slot.ONE
                }

                viewModel.pendingAction.value = {
                    putConfiguration(slot, configuration, null, null)
                    "Slot $slot programmed"
                }

                binding.extrasTabFront.setChecked(false)
                binding.extrasTabEnd.setChecked(false)
                binding.extrasCr.setChecked(false)
                binding.editTextStaticpwdId.setText("")
            } catch (e: Exception) {
                viewModel.postResult(Result.failure(e))
                getVibrator().vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        binding.btnRequestStaticpwd.setOnClickListener {
            activityViewModel.setYubiKeyListenerEnabled(false)
            requestOtp.launch()
        }

    }
    private fun getVibrator(): Vibrator {
        val vibratorManager = requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        return vibratorManager.defaultVibrator
    }
}