package __PACKAGE_NAME__

import android.os.Bundle
import com.google.android.material.color.DynamicColors
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    /** Implemented in native code (src/main/cpp). */
    external fun stringFromJNI(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.sample_text).text = stringFromJNI()
    }

    companion object {
        init {
            // Must match the library name in the native build file.
            System.loadLibrary("native-lib")
        }
    }
}
