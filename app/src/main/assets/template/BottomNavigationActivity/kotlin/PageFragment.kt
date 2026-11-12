package __PACKAGE_NAME__

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/** One simple screen. Replace it with your own fragments. */
class PageFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = TextView(requireContext()).apply {
        text = requireArguments().getString(ARG_TITLE)
        textSize = 24f
        gravity = Gravity.CENTER
    }

    companion object {
        private const val ARG_TITLE = "title"

        fun newInstance(title: String) = PageFragment().apply {
            arguments = Bundle().apply { putString(ARG_TITLE, title) }
        }
    }
}
