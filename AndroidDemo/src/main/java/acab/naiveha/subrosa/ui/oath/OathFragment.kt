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

package acab.naiveha.subrosa.ui.oath

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentOathBinding
import acab.naiveha.subrosa.ui.YubiKeyFragment
import acab.naiveha.subrosa.ui.getSecret
import com.yubico.yubikit.core.smartcard.ApduException
import com.yubico.yubikit.core.smartcard.SW
import com.yubico.yubikit.core.util.RandomUtils
import com.yubico.yubikit.oath.Base32
import com.yubico.yubikit.oath.CredentialData
import com.yubico.yubikit.oath.HashAlgorithm
import com.yubico.yubikit.oath.OathSession
import com.yubico.yubikit.oath.OathType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OathFragment : YubiKeyFragment<OathSession, OathViewModel>() {
    override val viewModel: OathViewModel by activityViewModels()

    private lateinit var binding: FragmentOathBinding
    private lateinit var listAdapter: OathListAdapter

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        binding = FragmentOathBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listAdapter = OathListAdapter(object : OathListAdapter.ItemListener {
            override fun onDelete(credentialId: ByteArray) {
                lifecycleScope.launch(Dispatchers.Main) {
                    AlertDialog.Builder(requireContext())
                            .setTitle("Delete credential?")
                            .setPositiveButton("Delete") { _, _ ->
                                viewModel.pendingAction.value = {
                                    deleteCredential(credentialId)
                                    "Credential deleted"
                                }
                            }.setNegativeButton(android.R.string.cancel) { dialog, _ ->
                                dialog.cancel()
                            }.show()
                }
            }
        })
        with(binding.credentialList) {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
        }

        binding.swiperefresh.setOnRefreshListener {
            viewModel.pendingAction.value = { null }  // NOOP: Force credential refresh
            binding.swiperefresh.isRefreshing = false
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            result.onFailure { e ->
                if (e is ApduException && e.sw == SW.SECURITY_CONDITION_NOT_SATISFIED) {
                    viewModel.oathDeviceId.value?.let { deviceId ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            getSecret(
                                requireContext(),
                                R.string.enter_password,
                                R.string.password
                            )?.let {
                                viewModel.password = Pair(deviceId, it.toCharArray())
                            }
                        }
                    }
                }
            }
        }

        viewModel.credentials.observe(viewLifecycleOwner) {
            listAdapter.submitList(it?.toList())
            binding.emptyView.visibility = if (it == null) View.VISIBLE else View.GONE
        }

        binding.textLayoutKey.setEndIconOnClickListener {
            binding.editTextKey.setText(Base32.encode(RandomUtils.getRandomBytes(10)))
        }
        binding.editTextKey.setText(Base32.encode(RandomUtils.getRandomBytes(10)))

        binding.btnSave.setOnClickListener {
            try {
                val secret = Base32.decode(binding.editTextKey.text.toString())
                val issuer = binding.editTextIssuer.text.toString()
                if (issuer.isBlank()) {
                    binding.editTextIssuer.error = "Issuer must not be empty"
                } else {
                    viewModel.pendingAction.value = {
                        putCredential(CredentialData("user@example.com", OathType.TOTP, HashAlgorithm.SHA1, secret, 6, 30, 0, issuer), false)
                        "Credential added"
                    }
                }
            } catch (e: Exception) {
                viewModel.postResult(Result.failure(e))
            }
        }
    }
}