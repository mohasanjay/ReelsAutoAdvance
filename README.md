# 🎬 Reels Auto Advance

An Android Accessibility Service app that automatically swipes to the next Instagram Reel when the current one ends — no more replays!

---

## How It Works

Instagram Reels loop by default when they end. This app runs as an Android Accessibility Service, watches the Instagram app for signs that a reel has finished playing, then performs an automatic swipe-up gesture to advance to the next reel.

**No root required. No Instagram account needed. Works entirely on-device.**

---

## Project Structure

```
ReelsAutoAdvance/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/reelsautoadvance/
│   │   ├── MainActivity.java              ← Settings/onboarding UI
│   │   └── ReelsAccessibilityService.java ← Core logic
│   └── res/
│       ├── layout/activity_main.xml
│       ├── values/strings.xml
│       ├── values/colors.xml
│       ├── values/themes.xml
│       └── xml/accessibility_service_config.xml
├── build.gradle
└── settings.gradle
```

---

## Build Instructions

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (latest stable)
- Android SDK 26+ (Android 8.0 Oreo or newer)
- A physical Android device or emulator with Instagram installed

### Steps

1. **Open the project**
   - Launch Android Studio
   - Choose **Open** → select the `ReelsAutoAdvance/` folder

2. **Sync Gradle**
   - Android Studio will prompt you to sync — click **Sync Now**
   - Wait for dependencies to download

3. **Build the APK**
   - Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
   - The APK will be at:
     `app/build/outputs/apk/debug/app-debug.apk`

4. **Install on your phone**
   - Connect your phone via USB with Developer Mode + USB Debugging enabled
   - Click the ▶ **Run** button in Android Studio, OR
   - Use ADB: `adb install app/build/outputs/apk/debug/app-debug.apk`

---

## First-Time Setup (on your phone)

1. Open **Reels Auto Advance** from your app drawer
2. Tap **"Enable Accessibility Service →"**
3. In Android Settings, find **"Reels Auto Advance"** and toggle it ON
4. Grant the permission when prompted
5. Go back to the app — the status card should turn green ✅
6. Open Instagram → Reels and enjoy hands-free scrolling!

---

## Customization

### Adjust the swipe delay
In `ReelsAccessibilityService.java`, change:
```java
private static final long SWIPE_DELAY_MS = 800; // milliseconds after video ends
```
- Lower = faster advance (may feel abrupt)
- Higher = more time to re-watch the end of a reel

### Adjust stall detection sensitivity
```java
private static final long STALL_THRESHOLD_MS = 1200;
```
This is the fallback: if no progress bar is detected (happens when Instagram updates their UI), the app will swipe after this many ms of no UI changes.

---

## Detection Strategy

The service uses two methods to detect reel end, with automatic fallback:

1. **Progress bar detection** (primary): Looks for Instagram's reel progress bar by view ID. When it reaches 100%, a swipe is triggered.
2. **Stall detection** (fallback): If Instagram updates their view IDs (which they do frequently), the app falls back to detecting when the UI stops changing — which signals the video has ended and is about to loop.

---

## Known Limitations

- **Instagram UI updates**: Instagram frequently changes their internal view IDs. If the app stops working after an Instagram update, the stall detection fallback will still work, but you may want to inspect the new view IDs using `uiautomatorviewer` or Layout Inspector in Android Studio.
- **Only works on Web Reels feed**: The swipe gesture targets the main Reels tab. It won't activate on Reels in Stories or Explore.
- **Instagram ToS**: Automating interactions may violate Instagram's Terms of Service. This app is for personal use.

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| App not advancing reels | Make sure the Accessibility Service is enabled (green status in app) |
| Double-swipes | Increase `SWIPE_DELAY_MS` to reduce sensitivity |
| Not triggering | Instagram may have updated view IDs — stall detection will still work after ~1.2s |
| Swipe in wrong position | Check screen resolution — the swipe uses 70%→30% of screen height |

---

## Publishing to Play Store

1. Create a Google Play Developer account ($25 one-time fee)
2. Generate a signed APK: **Build → Generate Signed Bundle/APK**
3. Create a new app in [Google Play Console](https://play.google.com/console)
4. Upload the AAB file, fill in store listing, and submit for review

> ⚠️ Note: Apps that automate interactions with other apps may face scrutiny during Play Store review. Consider framing the app description carefully, or distribute via direct APK sideloading.
