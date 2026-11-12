package __PACKAGE_NAME__

import android.os.Bundle
import com.google.android.material.color.DynamicColors
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.nav_view)
        nav.setOnItemSelectedListener { item ->
            show(
                when (item.itemId) {
                    R.id.nav_home -> "Home"
                    R.id.nav_dashboard -> "Dashboard"
                    else -> "Notifications"
                }
            )
            true
        }
        if (savedInstanceState == null) show("Home")
    }

    private fun show(title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, PageFragment.newInstance(title))
            .commit()
    }
}
