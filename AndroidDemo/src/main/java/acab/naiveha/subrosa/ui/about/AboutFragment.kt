package acab.naiveha.subrosa.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import acab.naiveha.subrosa.BuildConfig
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {
    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.version.text = getString(R.string.version, BuildConfig.VERSION_NAME)

        binding.coffeeTipsContainer.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val btcAddress = getString(R.string.btc_address)
            val clip = ClipData.newPlainText("BTC address", btcAddress)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(
                requireContext(),
                getString(R.string.copied_to_clipboard_msg, btcAddress),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
