package acab.naiveha.subrosa.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import acab.naiveha.subrosa.BuildConfig
import acab.naiveha.subrosa.R
import acab.naiveha.subrosa.databinding.FragmentAboutBinding
import acab.naiveha.subrosa.ui.setupCoffeeTipsClipboard

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

        setupCoffeeTipsClipboard(binding.coffeeTipsContainer)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
