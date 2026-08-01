package acab.naiveha.subrosa.ui.licenses

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import acab.naiveha.subrosa.databinding.FragmentLicensesBinding
import acab.naiveha.subrosa.ui.setupCoffeeTipsClipboard

class LicensesFragment : Fragment() {
    private var _binding: FragmentLicensesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLicensesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.licensesDetails.movementMethod = LinkMovementMethod.getInstance()

        setupCoffeeTipsClipboard(binding.coffeeTipsContainer)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}