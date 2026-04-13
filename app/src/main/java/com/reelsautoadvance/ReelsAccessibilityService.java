package com.reelsautoadvance;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayDeque;
import java.util.List;

public class ReelsAccessibilityService extends AccessibilityService {

    private static final String TAG              = "ReelsAutoAdvance";
    private static final String INSTAGRAM_PACKAGE = "com.instagram.android";

    // ── Notification ──────────────────────────────────────────────────────────
    private static final String CHANNEL_ID               = "reels_control";
    private static final int    NOTIFICATION_ID           = 1001;
    public  static final String ACTION_REFRESH_NOTIFICATION =
            "com.reelsautoadvance.REFRESH_NOTIFICATION";

    // ── Swipe timing ──────────────────────────────────────────────────────────
    // Short delay after detection before actually swiping — avoids double-fires
    private static final long SWIPE_DELAY_MS = 800;

    // ── Strategy 02 — Loop-reset thresholds ──────────────────────────────────
    // Primary detection: watch for the progress bar resetting to ~0%
    // AFTER the video has meaningfully played (i.e. the reel is looping).
    private static final float S2_MIN_PLAY_RATIO  = 0.08f; // must have seen ≥ 8% progress
    private static final float S2_RESET_THRESHOLD = 0.03f; // reset = progress drops to ≤ 3%

    // ── Strategy 04 — Event burst thresholds ─────────────────────────────────
    // Fallback: when the progress bar is unavailable, detect the burst of rapid
    // accessibility events that Android fires when a video seeks back to frame 0.
    private static final int  S4_BURST_COUNT    = 4;    // need this many events in the window
    private static final long S4_BURST_WINDOW   = 60L;  // all within 60ms of each other (ms)
    private static final long S4_MIN_PLAY_MS    = 5000L;// reel must have played ≥ 5s first

    // ── Strategy 08 — Fixed timer ─────────────────────────────────────────────
    // Last resort: if neither S02 nor S04 fires within this duration, force-advance.
    // Set to 2 minutes — long enough to not interrupt normal viewing of most reels,
    // but ensures the app never gets permanently stuck on a looping reel.
    private static final long S8_TIMEOUT_MS = 2 * 60 * 1000L; // 2 minutes

    // ── Runtime state ─────────────────────────────────────────────────────────
    private final Handler  handler      = new Handler(Looper.getMainLooper());
    private       Runnable pendingSwipe = null;
    private       Runnable s8Timer      = null; // the 2-minute last-resort timer

    private boolean isInReels     = false;
    private boolean instagramOpen = false;

    // Strategy 02 state — reset on each new reel
    private float   s2PeakRatio      = 0f;
    private float   s2LastRatio      = 0f;
    private boolean s2VideoStarted   = false;
    private boolean s2BarEverFound   = false; // tracks if S02 bar was ever usable this reel

    // Strategy 04 state — rolling window of recent event timestamps
    private final ArrayDeque<Long> s4EventTimes = new ArrayDeque<>();
    private       long             s4ReelStartMs = 0L; // when the current reel was detected

    private SharedPreferences        prefs;
    private NotificationManagerCompat notificationManager;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        prefs               = PreferenceManager.getDefaultSharedPreferences(this);
        notificationManager = NotificationManagerCompat.from(this);
        createNotificationChannel();
        Log.d(TAG, "Service connected");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REFRESH_NOTIFICATION.equals(intent.getAction())) {
            updateNotification();
        }
        return START_STICKY;
    }

    @Override
    public void onInterrupt() {
        cancelAll();
        dismissNotification();
    }

    @Override
    public void onDestroy() {
        cancelAll();
        dismissNotification();
        super.onDestroy();
    }

    // =========================================================================
    // Accessibility event routing
    // =========================================================================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;

        String pkgStr    = pkg.toString();
        int    eventType = event.getEventType();

        // ── Detect Instagram entering / leaving foreground ────────────────────
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            boolean wasOpen = instagramOpen;
            instagramOpen   = INSTAGRAM_PACKAGE.equals(pkgStr);
            if  (instagramOpen && !wasOpen) onInstagramOpened();
            else if (!instagramOpen && wasOpen) onInstagramClosed();
        }

        if (!INSTAGRAM_PACKAGE.equals(pkgStr)) return;
        if (!prefs.getBoolean("service_enabled", true))  return;
        if (!prefs.getBoolean("session_enabled", false)) return;

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            // Feed Strategy 04's rolling window on every content event
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                s4EventTimes.addLast(System.currentTimeMillis());
                // Keep the deque bounded — only last 10 events needed
                while (s4EventTimes.size() > 10) s4EventTimes.pollFirst();
            }

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            boolean wasInReels = isInReels;
            isInReels = isReelsScreen(root);

            if (isInReels) {
                // If we just entered Reels, record the start time for S04 min-play guard
                if (!wasInReels) {
                    s4ReelStartMs = System.currentTimeMillis();
                }
                detectVideoEnd(root);
            } else {
                cancelAll();
            }

            root.recycle();
        }
    }

    // =========================================================================
    // Instagram open / close
    // =========================================================================

    private void onInstagramOpened() {
        Log.d(TAG, "Instagram opened");
        prefs.edit().putBoolean("session_enabled", false).apply();
        resetReelState();
        showNotification(false);
    }

    private void onInstagramClosed() {
        Log.d(TAG, "Instagram closed");
        prefs.edit().putBoolean("session_enabled", false).apply();
        cancelAll();
        resetReelState();
        dismissNotification();
    }

    // =========================================================================
    // Per-reel state reset
    // Called on Instagram open/close AND after each successful swipe
    // =========================================================================

    private void resetReelState() {
        // Strategy 02
        s2PeakRatio    = 0f;
        s2LastRatio    = 0f;
        s2VideoStarted = false;
        s2BarEverFound = false;

        // Strategy 04
        s4EventTimes.clear();
        s4ReelStartMs = System.currentTimeMillis();

        cancelAll();
        Log.d(TAG, "Reel state reset");
    }

    // =========================================================================
    // Notification
    // =========================================================================

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Reels Auto Advance", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Control auto-advance while using Instagram");
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void showNotification(boolean enabled) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(enabled));
    }

    public void updateNotification() {
        showNotification(prefs.getBoolean("session_enabled", false));
    }

    private void dismissNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private Notification buildNotification(boolean enabled) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent enableI = new Intent(this, NotificationActionReceiver.class);
        enableI.setAction(NotificationActionReceiver.ACTION_ENABLE);
        PendingIntent enablePi = PendingIntent.getBroadcast(this, 1, enableI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent disableI = new Intent(this, NotificationActionReceiver.class);
        disableI.setAction(NotificationActionReceiver.ACTION_DISABLE);
        PendingIntent disablePi = PendingIntent.getBroadcast(this, 2, disableI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = enabled
                ? "Auto-advance ON — reels will skip automatically"
                : "Auto-advance OFF — tap Enable to activate";

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_next)
                .setContentTitle("Reels Auto Advance")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        b.addAction(enabled
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_next,
                enabled ? "Disable" : "Enable",
                enabled ? disablePi : enablePi);

        return b.build();
    }

    // =========================================================================
    // Reels screen detection
    // =========================================================================

    private boolean isReelsScreen(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Reels");
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isSelected() || n.isChecked()) { recycleAll(nodes); return true; }
                CharSequence d = n.getContentDescription();
                if (d != null && d.toString().toLowerCase().contains("reel")) {
                    recycleAll(nodes); return true;
                }
            }
            recycleAll(nodes);
        }
        List<AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId(
                INSTAGRAM_PACKAGE + ":id/reel_viewer_progress_bar");
        if (bars != null && !bars.isEmpty()) { recycleAll(bars); return true; }

        List<AccessibilityNodeInfo> like = root.findAccessibilityNodeInfosByViewId(
                INSTAGRAM_PACKAGE + ":id/row_feed_button_like");
        if (like != null && !like.isEmpty()) { recycleAll(like); return true; }

        return false;
    }

    // =========================================================================
    // Overlay detection (comments, share, description, etc.)
    // Blocks auto-advance whenever any bottom sheet is open.
    // =========================================================================

    private boolean isOverlayOpen(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> handle = root.findAccessibilityNodeInfosByViewId(
                INSTAGRAM_PACKAGE + ":id/bottom_sheet_drag_handle");
        if (handle != null && !handle.isEmpty()) { recycleAll(handle); return true; }

        List<AccessibilityNodeInfo> scrim = root.findAccessibilityNodeInfosByViewId(
                INSTAGRAM_PACKAGE + ":id/modal_scrim");
        if (scrim != null && !scrim.isEmpty()) { recycleAll(scrim); return true; }

        return hasBottomSheetInTree(root, 0);
    }

    private boolean hasBottomSheetInTree(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 6) return false;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().toLowerCase().contains("bottomsheet")
                && node.isVisibleToUser()) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (hasBottomSheetInTree(child, depth + 1)) {
                if (child != null) child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        return false;
    }

    // =========================================================================
    // Ad detection
    // =========================================================================

    private boolean isAdPlaying(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> sp = root.findAccessibilityNodeInfosByText("Sponsored");
        if (sp != null && !sp.isEmpty()) { recycleAll(sp); return true; }

        List<AccessibilityNodeInfo> cta = root.findAccessibilityNodeInfosByViewId(
                INSTAGRAM_PACKAGE + ":id/ad_cta_button");
        if (cta != null && !cta.isEmpty()) { recycleAll(cta); return true; }

        List<AccessibilityNodeInfo> ac = root.findAccessibilityNodeInfosByViewId(
                INSTAGRAM_PACKAGE + ":id/ad_choices_icon");
        if (ac == null || ac.isEmpty())
            ac = root.findAccessibilityNodeInfosByViewId(
                    INSTAGRAM_PACKAGE + ":id/why_this_ad");
        if (ac != null && !ac.isEmpty()) { recycleAll(ac); return true; }

        String[] ctaLabels = { "Learn More", "Install Now", "Shop Now",
                               "Sign Up", "Book Now", "Get Offer",
                               "Watch More", "Apply Now", "Contact Us" };
        for (String label : ctaLabels) {
            List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByText(label);
            if (ns != null && !ns.isEmpty()) {
                for (AccessibilityNodeInfo n : ns) {
                    CharSequence c = n.getClassName();
                    if (c != null && (c.toString().contains("Button") || n.isClickable())) {
                        recycleAll(ns);
                        return true;
                    }
                }
                recycleAll(ns);
            }
        }
        return false;
    }

    // =========================================================================
    // Master detection orchestrator
    // =========================================================================

    private void detectVideoEnd(AccessibilityNodeInfo root) {
        // Never advance while the user has a panel open
        if (isOverlayOpen(root)) {
            cancelPendingSwipe();
            pauseS8Timer();  // pause the 2-min timer too — user is actively engaged
            return;
        }

        // Never interfere with ads — reset S04 timing so it doesn't false-fire
        if (isAdPlaying(root)) {
            cancelPendingSwipe();
            resetReelState(); // treat the ad as a fresh reel for timing purposes
            return;
        }

        // ── Strategy 02 — Loop-reset detection (primary) ─────────────────────
        if (tryStrategy02(root)) {
            return; // S02 handled it — don't fall through
        }

        // ── Strategy 04 — Event burst fingerprinting (fallback) ───────────────
        // Only run if S02 never found a usable progress bar for this reel.
        // If S02's bar exists but just hasn't reset yet, let it keep trying.
        if (!s2BarEverFound) {
            tryStrategy04();
        }

        // ── Strategy 08 — 2-minute safety timer (last resort) ─────────────────
        // Arm S08 on every detectVideoEnd call. It self-cancels if S02 or S04
        // fires first (because scheduleSwipe → resetReelState → cancelAll).
        armS8TimerIfNeeded();
    }

    // =========================================================================
    // Strategy 02 — Loop-reset (progress bar drops to ~0% after playing)
    // =========================================================================

    /**
     * Reads the progress bar. Updates peak and detects when the bar resets
     * to near-zero after the video has meaningfully started playing.
     *
     * @return true if this strategy has a usable bar (even if it hasn't fired yet)
     */
    private boolean tryStrategy02(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId(
                INSTAGRAM_PACKAGE + ":id/reel_viewer_progress_bar");

        if (bars == null || bars.isEmpty()) {
            return false; // bar not found — tell caller to try S04
        }

        for (AccessibilityNodeInfo bar : bars) {
            if (bar.getRangeInfo() == null) continue;

            float max     = bar.getRangeInfo().getMax();
            float current = bar.getRangeInfo().getCurrent();
            if (max <= 0) continue;

            // We have a usable bar — mark it so S04 stays dormant
            s2BarEverFound = true;

            float ratio = current / max;

            // Track the peak progress seen for this reel
            if (ratio > s2PeakRatio) {
                s2PeakRatio = ratio;
            }

            // Mark video as meaningfully started once we've seen enough progress
            if (!s2VideoStarted && s2PeakRatio >= S2_MIN_PLAY_RATIO) {
                s2VideoStarted = true;
                Log.d(TAG, "[S02] Video started — peak passed " +
                        (int)(S2_MIN_PLAY_RATIO * 100) + "%");
            }

            // THE TRIGGER: progress drops to near-zero after video has started
            // This means the reel looped back to the beginning
            if (s2VideoStarted && ratio <= S2_RESET_THRESHOLD && s2LastRatio > S2_RESET_THRESHOLD) {
                Log.d(TAG, "[S02] Loop detected! Progress reset from " +
                        s2LastRatio + " to " + ratio + " — scheduling swipe");
                scheduleSwipe();
                recycleAll(bars);
                return true;
            }

            s2LastRatio = ratio;
        }

        recycleAll(bars);
        return true; // bar was found, even if not triggered yet
    }

    // =========================================================================
    // Strategy 04 — Event burst fingerprinting
    // =========================================================================

    /**
     * Examines the recent event timestamp window. If we see S4_BURST_COUNT
     * or more events all within S4_BURST_WINDOW milliseconds of each other,
     * AND the reel has been playing for at least S4_MIN_PLAY_MS, it's a loop.
     *
     * The burst occurs because Android's View system redraws multiple
     * components simultaneously when the video seeks back to frame 0:
     * the video surface, progress bar, and timestamp all update at once.
     */
    private void tryStrategy04() {
        if (s4EventTimes.size() < S4_BURST_COUNT) return;

        // Enforce minimum play time to avoid firing on initial reel load
        long playedMs = System.currentTimeMillis() - s4ReelStartMs;
        if (playedMs < S4_MIN_PLAY_MS) return;

        // Check the most recent S4_BURST_COUNT events
        Long[] times = s4EventTimes.toArray(new Long[0]);
        int len = times.length;

        // Walk backwards through the window looking for a burst
        for (int i = len - 1; i >= S4_BURST_COUNT - 1; i--) {
            long newest = times[i];
            long oldest = times[i - (S4_BURST_COUNT - 1)];
            long spread = newest - oldest;

            if (spread <= S4_BURST_WINDOW) {
                // Found S4_BURST_COUNT events within S4_BURST_WINDOW ms
                Log.d(TAG, "[S04] Event burst detected — " + S4_BURST_COUNT +
                        " events in " + spread + "ms — scheduling swipe");
                scheduleSwipe();
                return;
            }
        }
    }

    // =========================================================================
    // Strategy 08 — 2-minute safety timer (last resort)
    // =========================================================================

    /**
     * Arms the 2-minute timer if it isn't already running.
     * If either S02 or S04 fires first, resetReelState() → cancelAll()
     * will cancel this timer before it fires.
     */
    private void armS8TimerIfNeeded() {
        if (s8Timer != null) return; // already armed — don't reset it
        s8Timer = () -> {
            Log.d(TAG, "[S08] 2-minute safety timer fired — force advancing");
            s8Timer = null;
            scheduleSwipe();
        };
        handler.postDelayed(s8Timer, S8_TIMEOUT_MS);
        Log.d(TAG, "[S08] Safety timer armed (" + (S8_TIMEOUT_MS / 1000) + "s)");
    }

    private void pauseS8Timer() {
        // When user opens an overlay (comments, share etc.) cancel and re-arm
        // later — so the 2 minutes only counts actual reel-watching time.
        if (s8Timer != null) {
            handler.removeCallbacks(s8Timer);
            s8Timer = null;
            Log.d(TAG, "[S08] Timer paused (overlay open)");
        }
    }

    // =========================================================================
    // Swipe gesture
    // =========================================================================

    private void scheduleSwipe() {
        cancelPendingSwipe();
        pendingSwipe = () -> {
            performSwipeUp();
            // After swiping, reset everything for the next reel
            resetReelState();
        };
        handler.postDelayed(pendingSwipe, SWIPE_DELAY_MS);
    }

    private void cancelPendingSwipe() {
        if (pendingSwipe != null) {
            handler.removeCallbacks(pendingSwipe);
            pendingSwipe = null;
        }
    }

    /** Cancels all pending actions — swipe + S08 timer. */
    private void cancelAll() {
        cancelPendingSwipe();
        if (s8Timer != null) {
            handler.removeCallbacks(s8Timer);
            s8Timer = null;
        }
    }

    private void performSwipeUp() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);

        Path path = new Path();
        path.moveTo(m.widthPixels / 2f, m.heightPixels * 0.7f);
        path.lineTo(m.widthPixels / 2f, m.heightPixels * 0.3f);

        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));

        dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                Log.d(TAG, "Swiped to next reel"); }
            @Override public void onCancelled(GestureDescription g) {
                Log.d(TAG, "Swipe cancelled"); }
        }, null);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) if (n != null) n.recycle();
    }
}
