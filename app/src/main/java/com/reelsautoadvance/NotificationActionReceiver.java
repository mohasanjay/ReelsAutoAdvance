package com.reelsautoadvance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class NotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_ENABLE  = "com.reelsautoadvance.ACTION_ENABLE";
    public static final String ACTION_DISABLE = "com.reelsautoadvance.ACTION_DISABLE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        // Use the same plain SharedPreferences file as MainActivity and the Service
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences("reels_prefs", Context.MODE_PRIVATE);

        switch (intent.getAction()) {
            case ACTION_ENABLE:
                prefs.edit().putBoolean("session_enabled", true).apply();
                break;
            case ACTION_DISABLE:
                prefs.edit().putBoolean("session_enabled", false).apply();
                break;
        }

        // Tell the running service to refresh the notification
        Intent refresh = new Intent(context, ReelsAccessibilityService.class);
        refresh.setAction(ReelsAccessibilityService.ACTION_REFRESH_NOTIFICATION);
        context.startService(refresh);
    }
}
