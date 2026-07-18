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
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentYubiotpBinding
import acab.naiveha.subrosa.ui.YubiKeyFragment
import com.yubico.yubikit.yubiotp.YubiOtpSession

class OtpFragment : YubiKeyFragment<YubiOtpSession, OtpViewModel>() {
    override val viewModel: OtpViewModel by activityViewModels()
    private lateinit var binding: FragmentYubiotpBinding

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        binding = FragmentYubiotpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.pager.adapter = ProgramModeAdapter(this)

        TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
            tab.setText(when (position) {
                0 -> R.string.otp_yubistatic
                else -> throw IllegalStateException()
            })
        }.attach()

        // TODO: Yubico OTP and Challenge-Response tabs are intentionally disabled for now
//        TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
//            tab.setText(when (position) {
//                0 -> R.string.otp_yubistatic
//                1 -> R.string.otp_yubiotp
//                2 -> R.string.otp_chalresp
//                else -> throw IllegalStateException()
//            })
//        }.attach()

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state != null) {
                binding.emptyView.visibility = View.INVISIBLE
                binding.otpStatusText.text = getString(
                    R.string.otp_slot_status,
                    getString(
                        if (state.slotOneProgrammed) R.string.otp_slot_state_programmed
                        else R.string.otp_slot_state_empty,
                    ),
                    getString(
                        if (state.slotTwoProgrammed) R.string.otp_slot_state_programmed
                        else R.string.otp_slot_state_empty,
                    ),
                )
                binding.otpStatusText.visibility = View.VISIBLE
            } else {
                binding.emptyView.visibility = View.VISIBLE
                binding.otpStatusText.visibility = View.INVISIBLE
            }
        }
    }

    class ProgramModeAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
//        override fun getItemCount(): Int = 3
        override fun getItemCount(): Int = 1

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> StaticPwdFragment()
//            1 -> YubiOtpFragment()
//            2 -> ChallengeResponseFragment()
            else -> throw IllegalStateException()
        }
    }
}