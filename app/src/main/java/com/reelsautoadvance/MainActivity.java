package com.reelsautoadvance;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;

public class MainActivity extends AppCompatActivity {

    private MaterialSwitch   switchEnable;
    private MaterialButton   btnOpenAccessibility;
    private MaterialCardView cardStatus;
    private MaterialTextView tvStatus;
    private MaterialTextView tvStatusDetail;
    private SharedPreferences prefs;

    // Handles the Android 13+ notification permission dialog result
    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { /* no-op — app works with or without it */ });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Fix 1: use AndroidX PreferenceManager (not deprecated android.preference)
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        switchEnable         = findViewById(R.id.switch_enable);
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility);
        cardStatus           = findViewById(R.id.card_status);
        tvStatus             = findViewById(R.id.tv_status);
        tvStatusDetail       = findViewById(R.id.tv_status_detail);

        // Fix 2: request POST_NOTIFICATIONS at runtime on Android 13+
        // Without this the service crashes immediately when trying to show
        // the Instagram notification
        requestNotificationPermission();

        switchEnable.setChecked(prefs.getBoolean("service_enabled", true));
        switchEnable.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("service_enabled", checked).apply());

        btnOpenAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusCard();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void updateStatusCard() {
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            cardStatus.setCardBackgroundColor(getColor(R.color.status_active));
            tvStatus.setText("✅  Service Active");
            tvStatusDetail.setText("Reels Auto Advance is running. Open Instagram and browse Reels!");
            btnOpenAccessibility.setText("Accessibility Settings");
            switchEnable.setEnabled(true);
        } else {
            cardStatus.setCardBackgroundColor(getColor(R.color.status_inactive));
            tvStatus.setText("⚠️  Service Not Enabled");
            tvStatusDetail.setText("Tap the button below to enable the Accessibility Service. Find 'Reels Auto Advance' and turn it on.");
            btnOpenAccessibility.setText("Enable Accessibility Service →");
            switchEnable.setEnabled(false);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" +
                ReelsAccessibilityService.class.getCanonicalName();
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(service)) return true;
        }
        return false;
    }
}
