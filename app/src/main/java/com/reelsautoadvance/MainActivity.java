package com.reelsautoadvance;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.CompoundButton;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class MainActivity extends AppCompatActivity {

    private MaterialSwitch switchEnable;
    private MaterialButton btnOpenAccessibility;
    private MaterialCardView cardStatus;
    private MaterialTextView tvStatus;
    private MaterialTextView tvStatusDetail;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        switchEnable = findViewById(R.id.switch_enable);
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility);
        cardStatus = findViewById(R.id.card_status);
        tvStatus = findViewById(R.id.tv_status);
        tvStatusDetail = findViewById(R.id.tv_status_detail);

        // Load saved preference
        switchEnable.setChecked(prefs.getBoolean("service_enabled", true));

        switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("service_enabled", isChecked).apply();
        });

        btnOpenAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusCard();
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
        String service = getPackageName() + "/" + ReelsAccessibilityService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(service)) return true;
        }
        return false;
    }
}
