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

package acab.naiveha.subrosa.ui.piv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentPivBinding
import acab.naiveha.subrosa.ui.YubiKeyFragment
import acab.naiveha.subrosa.ui.getSecret
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.SW
import com.yubico.yubikit.piv.PivSession
import com.yubico.yubikit.piv.Slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bouncycastle.util.encoders.Hex

class PivFragment : YubiKeyFragment<PivSession, PivViewModel>() {
    private val slots = listOf(
        PageProperties(Slot.AUTHENTICATION, R.string.piv_authentication),
        PageProperties(Slot.SIGNATURE, R.string.piv_signature),
        PageProperties(Slot.KEY_MANAGEMENT, R.string.piv_key_mgmt),
        PageProperties(Slot.CARD_AUTH, R.string.piv_card_auth)
    )

    override val viewModel: PivViewModel by activityViewModels()
    private lateinit var binding: FragmentPivBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPivBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.pager.adapter = PagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
            tab.text = String.format("Slot %02X", slots[position].slot.value)
        }.attach()

        showCerts(false)

        viewModel.certificates.observe(viewLifecycleOwner) {
            it?.run {
                showCerts(true)
            }
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            result.onFailure { e ->
                when (e) {
                    is ApplicationNotAvailableException -> showCerts(false)
                    is ApduException -> if (e.sw == SW.SECURITY_CONDITION_NOT_SATISFIED) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            viewModel.mgmtKey = Hex.decode(
                                getSecret(
                                    requireContext(),
                                    R.string.piv_enter_mgmt_key,
                                    R.string.piv_mgmt_key_hint
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showCerts(visible: Boolean) {
        binding.pager.visibility = if (visible) View.VISIBLE else View.GONE
        binding.emptyView.visibility = if (visible) View.GONE else View.VISIBLE
        activity?.invalidateOptionsMenu()
    }

    inner class PagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = slots.size

        override fun createFragment(position: Int): Fragment {
            return PivCertificateFragment.newInstance(
                slots[position].slot,
                slots[position].nameResId
            )
        }
    }

    private data class PageProperties(val slot: Slot, val nameResId: Int)
}