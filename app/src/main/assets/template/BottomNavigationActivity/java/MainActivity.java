package __PACKAGE_NAME__;

import android.os.Bundle;
import com.google.android.material.color.DynamicColors;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView nav = findViewById(R.id.nav_view);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) show("Home");
            else if (id == R.id.nav_dashboard) show("Dashboard");
            else show("Notifications");
            return true;
        });
        if (savedInstanceState == null) show("Home");
    }

    private void show(String title) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, PageFragment.newInstance(title))
                .commit();
    }
}
