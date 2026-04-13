package com.reelsautoadvance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

/**
 * Receives the Enable / Disable button taps from the persistent notification.
 * Flips the "session_enabled" preference and tells the service to refresh
 * the notification to reflect the new state.
 */
public class NotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_ENABLE  = "com.reelsautoadvance.ACTION_ENABLE";
    public static final String ACTION_DISABLE = "com.reelsautoadvance.ACTION_DISABLE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        switch (intent.getAction()) {
            case ACTION_ENABLE:
                prefs.edit().putBoolean("session_enabled", true).apply();
                break;
            case ACTION_DISABLE:
                prefs.edit().putBoolean("session_enabled", false).apply();
                break;
        }

        // Ask the running service to redraw the notification with the new state
        Intent refresh = new Intent(context, ReelsAccessibilityService.class);
        refresh.setAction(ReelsAccessibilityService.ACTION_REFRESH_NOTIFICATION);
        context.startService(refresh);
    }
}
