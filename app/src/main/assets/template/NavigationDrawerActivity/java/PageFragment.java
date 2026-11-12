package __PACKAGE_NAME__;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** One simple screen. Replace it with your own fragments. */
public class PageFragment extends Fragment {

    private static final String ARG_TITLE = "title";

    public static PageFragment newInstance(String title) {
        PageFragment f = new PageFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        TextView tv = new TextView(requireContext());
        tv.setText(requireArguments().getString(ARG_TITLE));
        tv.setTextSize(24f);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }
}
