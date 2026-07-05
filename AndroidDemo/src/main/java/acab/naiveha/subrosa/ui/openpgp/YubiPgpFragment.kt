package acab.naiveha.subrosa.ui.openpgp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentYubipgpBinding
import acab.naiveha.subrosa.ui.YubiKeyFragment
import com.yubico.yubikit.openpgp.OpenPgpSession

class YubiPgpFragment : YubiKeyFragment<OpenPgpSession, OpenPgpViewModel>() {
    override val viewModel: OpenPgpViewModel by activityViewModels()
    private lateinit var binding: FragmentYubipgpBinding

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        binding = FragmentYubipgpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.pager.adapter = PgpModeAdapter(this)

        TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
            tab.setText(when (position) {
                0 -> R.string.menu_openpgp
                else -> throw IllegalStateException()
            })
        }.attach()

        viewModel.status.observe(viewLifecycleOwner) {
            if (it != null) {
                binding.emptyView.visibility = View.INVISIBLE
                binding.statusText.text = it
                binding.statusText.visibility = View.VISIBLE
            } else {
                binding.emptyView.visibility = View.VISIBLE
                binding.statusText.visibility = View.INVISIBLE
            }
        }
    }

    class PgpModeAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 1

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> OpenPgpFragment()
            else -> throw IllegalStateException()
        }
    }
}
