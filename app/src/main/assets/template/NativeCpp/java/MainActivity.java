package __PACKAGE_NAME__;

import android.os.Bundle;
import com.google.android.material.color.DynamicColors;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    static {
        // Must match the library name in the native build file.
        System.loadLibrary("native-lib");
    }

    /** Implemented in native code (src/main/cpp). */
    public native String stringFromJNI();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView tv = findViewById(R.id.sample_text);
        tv.setText(stringFromJNI());
    }
}
