# AuraVibe Controller

A small, battery-friendly Android app built for the **vivo X200 FE (OriginOS)** that:

1. **Forces vibration for selected apps** (LinkedIn, Gmail, Google, WhatsApp…) while the phone is in **Vibrate** or **Silent** mode. On OriginOS the *Notifications* volume slider is locked in those modes, so third-party notifications stop vibrating; only calls do.
2. **Controls the rear Aura light outside the Camera app** with a one-tap power button, an orange ⟷ white colour slider, 5-step brightness, pulse patterns, and a Quick Settings tile.
3. **Flashes the Aura light on notifications** for the apps you pick.

> Works on Android 13+ (minSdk 33, targetSdk 35). Built with Kotlin, Jetpack Compose and Material 3.

---

## Download

- **Every push:** open the [Actions tab](../../actions), pick the latest *Build APK* run and download the `AuraVibe-apk` artifact.
- **Tagged releases:** push a tag such as `v1.0.0` and the APKs are attached to a GitHub Release.

`AuraVibe-release.apk` is minified (~2.4 MB) and signed with the CI debug key, so it is fine for sideloading but not for the Play Store.

---

## Setup on the phone (OriginOS)

1. Install the APK (allow *Install unknown apps* for your browser / file manager).
2. Open **AuraVibe → Setup** (start [Shizuku](https://shizuku.rikka.app/) first if you want the orange LED) and:
   - **Notification access → Grant**: turn on *AuraVibe notification engine*. Android 13+ may first show *Restricted setting*. If it does, go to *Settings → Apps → AuraVibe → ⋮ → Allow restricted settings* and try again.
   - **Battery: unrestricted → Allow**.
3. In **App info → Battery** turn on **High background power consumption** (or *Unrestricted*), and enable **Auto-start**. Without this OriginOS/iManager may kill the listener.
4. In **My Apps**, search an app and tap **📳 Vibrate** and/or **💡 Light**. Tap the app row for custom colour and vibration.
5. Put the phone in **Vibration** mode. In **Setup → Vibration attribute tester**, tap each option (Alarm, Communication request, Hardware feedback, Ringtone) and keep the one you actually feel. *Alarm* is the most likely to get through OriginOS's restriction.

### Quick Settings tile
Pull down the shade, tap the pencil/edit icon and drag **Aura Light** into your tiles. It switches the light on using your saved colour/brightness. The auto turn-off (Setup → More options) still turns it off automatically.

---

## The app in 3 tabs

- **Light & Vibe:** one big power button (glows in the chosen colour), quick *Warm Orange / Mixed Natural / Pure White* buttons, then two cards:
  - **Light Tuning:** colour slider (orange ⟷ white), 5-step brightness, style (Always On, Smooth Pulse, Reverse Pulse, Fast Blink, Heartbeat), speed, how many times (or until you unlock the phone), and a test button.
  - **Vibration Tuning:** on/off, style (Single, Double, Long Strong Buzz, Heartbeat, Urgent Rapid), strength, length, repeat, and a "feel it now" button.
- **My Apps:** search and filter (Enabled / Social & Work / All). Each app has a 📳 Vibrate and a 💡 Light button, and enabled apps stay pinned to the top. Tap an app to use your main settings or a custom colour and vibration.
- **Setup:** permissions, Shizuku, the orange-light finder, the vibration-type tester and safety options.

## Getting the orange (warm) Aura LED to work

Android's flashlight API only reaches the main white LED, so the app tries every other route it knows and lets you pick the one that works:

1. **Kernel LED nodes via Shizuku:** `/sys/class/leds/*` (for example `led:torch_0`, `led:torch_1`…). After writing brightness, the app *commits* through any `led:switch_N` nodes (write 0, set levels, write 1). On vivo / MediaTek this commit step is what actually turns the LED on.
2. **MediaTek flashlight core via Shizuku:** `/sys/class/flashlight_core/flashlight/flashlight_torch`, trying each LED index.
3. **Hidden and physical camera IDs:** flash-capable IDs that aren't in the normal camera list.
4. **Vendor camera keys:** opens a tiny camera session in torch mode and sets any vivo/aura/CCT/fill-light request key the firmware exposes.

**What a real X200 FE report showed:** `/sys/class/leds` is empty and `flashlight_core` is blocked for the Shizuku shell (SELinux), so routes 1–2 need root on that phone. The camera HAL does expose vivo's own Aura ("soft light") keys: `vivo.control.isSoftLight`, `vivo.control.softLightStudio`, `vivo.control.colour.temperature`, plus `com.mediatek.flashfeature.customizedTorchDuty`. Since v1.2 the finder tries these first as "Aura recipes", and **Setup → 🧪 Vendor Key Lab** lets you test any `key=value` combination (use `L` for brightness, `K` for kelvin) and save it as the orange light.

**🟠 Fix / Calibrate Orange Light** (Home card or Setup) blinks each channel one at a time. Tap **"Yes, it's ORANGE — Save This!"** when the orange LED lights up. If a white channel lights instead, mark it as white. After that the colour slider mixes the two: `warm = brightness × (1 − tone)`, `cool = brightness × tone`.

If nothing lights up, the wizard shows why (for example *Permission denied*). Use **Copy LED Hardware Report** to share the list of LED nodes. On many production kernels these nodes can only be written by root, and no app can get past that without root.

## Battery design

- **No always-on service, no wake locks, no polling.** The only long-lived component is the system-bound `NotificationListenerService`, which does nothing until Android calls `onNotificationPosted`.
- Per-app rules sit in a hot in-memory `Map`, mirrored from Jetpack DataStore, so each notification needs a single O(1) lookup.
- Group summaries, ongoing and foreground-service notifications are ignored. A per-app **cooldown** (default 3 s) stops message bursts from spamming vibration and light.
- Light effects run as one short coroutine on a single-threaded lane. When the effect finishes, is cancelled or hits the **safety cutoff**, the torch is turned off and the Shizuku shell is closed.

---

## Project layout

```
app/src/main/java/com/auravibe/controller/
├── AuraVibeApp.kt                 # tiny service locator
├── data/   Models.kt, SettingsRepo.kt (DataStore)
├── hw/     TorchBackend.kt, ShizukuBackend.kt, SessionBackend.kt, Calibrator.kt, AuraHal.kt, Vibes.kt
├── engine/ PatternEngine.kt       # Breathe / Inverse breathe / Strobe / Heartbeat
├── service/AuraNotificationListener.kt, AuraTileService.kt
└── ui/     MainActivity, HomeScreen, OrangeWizard, AppsScreen, SetupScreen, Components, Theme
```

## Build locally

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # minified, debug-signed
```

Requires JDK 17 and Android SDK platform 35.

## Known limitations

- Whether a given `VibrationAttributes` usage gets past OriginOS in Silent mode depends on the firmware. That's why there's a tester instead of a hard-coded choice.
- The orange LED works only if one of the four routes above is open on your firmware. If none is, brightness and patterns still work on the white LED.
- The safety cutoff timer runs inside the app process. If OriginOS kills the app while the light is on, turn it off from the tile or the power button.
