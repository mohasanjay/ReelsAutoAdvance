package com.reelsautoadvance;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;

public class MainActivity extends AppCompatActivity {

    private static final int NOTIF_PERMISSION_CODE = 100;

    private MaterialSwitch   switchEnable;
    private MaterialButton   btnOpenAccessibility;
    private MaterialCardView cardStatus;
    private MaterialTextView tvStatus;
    private MaterialTextView tvStatusDetail;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);

            prefs = getSharedPreferences("reels_prefs", MODE_PRIVATE);

            switchEnable         = findViewById(R.id.switch_enable);
            btnOpenAccessibility = findViewById(R.id.btn_open_accessibility);
            cardStatus           = findViewById(R.id.card_status);
            tvStatus             = findViewById(R.id.tv_status);
            tvStatusDetail       = findViewById(R.id.tv_status_detail);

            switchEnable.setChecked(prefs.getBoolean("service_enabled", true));
            switchEnable.setOnCheckedChangeListener((btn, checked) ->
                    prefs.edit().putBoolean("service_enabled", checked).apply());

            btnOpenAccessibility.setOnClickListener(v ->
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

            requestNotificationPermission();

        } catch (Exception e) {
            Toast.makeText(this, "Startup error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIF_PERMISSION_CODE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            updateStatusCard();
        } catch (Exception e) {
            Toast.makeText(this, "Resume error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateStatusCard() {
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            // Fix: use ContextCompat.getColor() instead of getColor() — safer across all Android versions
            cardStatus.setCardBackgroundColor(
                    ContextCompat.getColor(this, R.color.status_active));
            tvStatus.setText("Service Active");
            tvStatusDetail.setText("Reels Auto Advance is running. Open Instagram and browse Reels!");
            btnOpenAccessibility.setText("Accessibility Settings");
            switchEnable.setEnabled(true);
        } else {
            cardStatus.setCardBackgroundColor(
                    ContextCompat.getColor(this, R.color.status_inactive));
            tvStatus.setText("Service Not Enabled");
            tvStatusDetail.setText("Tap the button below to enable the Accessibility Service. Find 'Reels Auto Advance' and turn it on.");
            btnOpenAccessibility.setText("Enable Accessibility Service");
            switchEnable.setEnabled(false);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        // Fix: use getName() instead of getCanonicalName() — getCanonicalName() can return null
        String service = getPackageName() + "/" +
                ReelsAccessibilityService.class.getName();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(service)) return true;
        }
        return false;
    }
}
