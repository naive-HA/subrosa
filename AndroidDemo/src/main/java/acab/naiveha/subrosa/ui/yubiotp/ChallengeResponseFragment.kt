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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentYubiotpChalrespBinding
import com.yubico.yubikit.core.util.RandomUtils
import com.yubico.yubikit.yubiotp.HmacSha1SlotConfiguration
import com.yubico.yubikit.yubiotp.Slot
import org.bouncycastle.util.encoders.Hex

class ChallengeResponseFragment : Fragment() {
    private val viewModel: OtpViewModel by activityViewModels()
    private lateinit var binding: FragmentYubiotpChalrespBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        binding = FragmentYubiotpChalrespBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.textLayoutKey.setEndIconOnClickListener {
            binding.editTextKey.setText(String(Hex.encode(RandomUtils.getRandomBytes(8))))
        }
        binding.editTextKey.setText(String(Hex.encode(RandomUtils.getRandomBytes(8))))

        binding.textLayoutChallenge.setEndIconOnClickListener {
            binding.editTextChallenge.setText(String(Hex.encode(RandomUtils.getRandomBytes(8))))
        }
        binding.editTextChallenge.setText(String(Hex.encode(RandomUtils.getRandomBytes(8))))

        binding.btnSave.setOnClickListener {
            try {
                val key = Hex.decode(binding.editTextKey.text.toString())
                val touch = binding.switchRequireTouch.isChecked
                val slot = when (binding.slotRadio.checkedRadioButtonId) {
                    R.id.radio_slot_1 -> Slot.ONE
                    R.id.radio_slot_2 -> Slot.TWO
                    else -> throw IllegalStateException("No slot selected")
                }
                viewModel.pendingAction.value = {
                    putConfiguration(slot, HmacSha1SlotConfiguration(key).requireTouch(touch), null, null)
                    "Slot $slot programmed"
                }
            } catch (e: Exception) {
                viewModel.postResult(Result.failure(e))
            }
        }

        binding.btnCalculateResponse.setOnClickListener {
            try {
                val challenge = Hex.decode(binding.editTextChallenge.text.toString())
                val slot = when (binding.slotCalculateRadio.checkedRadioButtonId) {
                    R.id.radio_calculate_slot_1 -> Slot.ONE
                    R.id.radio_calculate_slot_2 -> Slot.TWO
                    else -> throw IllegalStateException("No slot selected")
                }

                viewModel.pendingAction.value = {
                    val response = calculateHmacSha1(slot, challenge, null)
                    "Calculated response: " + String(Hex.encode(response))
                }
            } catch (e: java.lang.Exception) {
                viewModel.postResult(Result.failure(e))
            }
        }
    }
}