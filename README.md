# SCREAM DINO  🦖🎤

Chrome-Dino-style endless runner for Android where you **shout to jump**.
Kotlin, no third-party libraries, no image/audio assets (everything is drawn and synthesised in code), no INTERNET permission.

## Features
- Desert runner: cacti (1–3 clusters), birds at three heights, speed rises with score
- **Voice jump**: on-device loudness detector (20 ms frames, adaptive noise floor, loud-onset trigger so normal talking doesn't jump)
- Adjustable mic sensitivity with a live level meter + threshold marker (Settings)
- Touch jumping always available; voice control can be switched off
- Mic permission requested only when voice control is on; audio is analysed in memory and discarded (never stored or sent)
- Start / Pause / Resume / Game Over / Restart, persistent high score
- Day → sunset → night → dawn world as the score grows; synthesised sound effects; vibration on crash
- English and Persian (فارسی, with RTL layout and Persian digits)
- Battery friendly: 60 fps only while playing, 30 fps on menus, mic only active in-game / settings

## Build the APK

### Option A – Android Studio (easiest)
1. Install Android Studio (Koala or newer, includes JDK 17).
2. *File ▸ Open* → select this folder. Let Gradle sync (it downloads Gradle 8.9 + Android SDK 34 prompts automatically).
3. *Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)* (debug) — or run on a connected phone with ▶.
4. APK: `app/build/outputs/apk/debug/app-debug.apk`

### Option B – GitHub Actions (no local tools)
1. Create a GitHub repo and push this folder.
2. Open the **Actions** tab ▸ *Build APK* ▸ *Run workflow* (it also runs on every push).
3. Download the `ScreamDino-APK` artifact → contains `app-release.apk` (signed with the debug key, installable).

### Option C – Command line
Requires JDK 17, Android SDK (platform 34, build-tools) and Gradle 8.9+:
```
gradle assembleRelease      # or assembleDebug
```

## Install on a phone
1. Copy the APK to the phone.
2. Open it, and allow "Install unknown apps" for your file manager/browser if asked.
3. Launch **SCREAM DINO**, tap PLAY, and allow the microphone when prompted.
Requires Android 7.0+ (API 24).

## Tuning the voice
Settings ▸ Mic sensitivity. Watch the meter: the white tick is the trigger level.
Raise sensitivity if your shout doesn't trigger; lower it if talking/noise does. Default 40.

## Project layout
```
app/src/main/java/com/screamdino/game/
  MainActivity.kt   permissions, fullscreen, lifecycle
  GameView.kt       game loop, physics, rendering, menus
  VoiceDetector.kt  local shout detection (AudioRecord)
  SoundFx.kt        synthesised sound effects
  Prefs.kt          settings + high score (SharedPreferences)
  Strings.kt        English / Persian text
```
For Play Store release, replace the debug signing config in `app/build.gradle.kts` with your own keystore.
