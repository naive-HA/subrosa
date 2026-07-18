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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentYubiotpChalrespBinding
import com.yubico.yubikit.core.util.RandomUtils
import com.yubico.yubikit.yubiotp.HmacSha1SlotConfiguration
import org.bouncycastle.util.encoders.Hex

class ChallengeResponseFragment : Fragment() {
    companion object {
        private const val TAG = "ChallengeResponseFragment"
    }

    private val viewModel: OtpViewModel by activityViewModels()
    private lateinit var binding: FragmentYubiotpChalrespBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        binding = FragmentYubiotpChalrespBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.textLayoutKey.bindRandomGenerator(binding.editTextKey) {
            String(Hex.encode(RandomUtils.getRandomBytes(8)))
        }
        binding.textLayoutChallenge.bindRandomGenerator(binding.editTextChallenge) {
            String(Hex.encode(RandomUtils.getRandomBytes(8)))
        }

        binding.btnSave.setOnClickListener {
            runValidated(viewModel) {
                val key = Hex.decode(binding.editTextKey.text.toString())
                val touch = binding.switchRequireTouch.isChecked
                val slot = resolveSlot(binding.slotRadio.checkedRadioButtonId, R.id.radio_slot_1, R.id.radio_slot_2)

                Log.d(TAG, "btnSave — queuing program of slot $slot (requireTouch=$touch)")
                viewModel.pendingAction.value = {
                    Log.i(TAG, "pendingAction — programming slot $slot")
                    putConfiguration(slot, HmacSha1SlotConfiguration(key).requireTouch(touch), null, null)
                    Log.i(TAG, "pendingAction — slot $slot programmed")
                    OtpViewModel.slotProgrammedStatus(slot)
                }
            }
        }

        binding.btnCalculateResponse.setOnClickListener {
            runValidated(viewModel) {
                val challenge = Hex.decode(binding.editTextChallenge.text.toString())
                val slot = resolveSlot(
                    binding.slotCalculateRadio.checkedRadioButtonId,
                    R.id.radio_calculate_slot_1,
                    R.id.radio_calculate_slot_2,
                )

                Log.d(TAG, "btnCalculateResponse — queuing calculation on slot $slot")
                viewModel.pendingAction.value = {
                    Log.i(TAG, "pendingAction — calculating response on slot $slot")
                    val response = calculateHmacSha1(slot, challenge, null)
                    OtpViewModel.calculatedResponseStatus(response)
                }
            }
        }
    }
}