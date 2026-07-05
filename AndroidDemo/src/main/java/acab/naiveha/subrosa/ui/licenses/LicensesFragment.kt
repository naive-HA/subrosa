package acab.naiveha.subrosa.ui.licenses

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import acab.naiveha.subrosa.R

class LicensesFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_licenses, container, false)
        root.findViewById<TextView>(R.id.licenses_details).movementMethod = LinkMovementMethod.getInstance()
        return root
    }
}