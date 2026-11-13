# AuraVibe Controller

A small, battery-friendly Android app built for the **vivo X200 FE (OriginOS)** that:

1. **Forces vibration for selected apps** (LinkedIn, Gmail, Google, WhatsApp…) while the phone is in **Vibrate** or **Silent** mode. On OriginOS the *Notifications* volume slider is locked in those modes, so third-party notifications stop vibrating; only calls do.
2. **Controls the rear Aura light outside the Camera app** with a camera-style Studio (colour temperature + 5-step brightness), pulse patterns, and a Quick Settings tile.
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
2. Open **AuraVibe → Setup** and:
   - **Notification access → Grant**: turn on *AuraVibe notification engine*. Android 13+ may first show *Restricted setting*. If it does, go to *Settings → Apps → AuraVibe → ⋮ → Allow restricted settings* and try again.
   - **Battery: unrestricted → Allow**.
3. In **App info → Battery** turn on **High background power consumption** (or *Unrestricted*), and enable **Auto-start**. Without this OriginOS/iManager may kill the listener.
4. In **App rules**, search an app and switch on **Vibe** and/or **Aura**. Tap a row to pick a vibration pattern (Short double tap, Strong pulse, Heartbeat, Custom duration).
5. Put the phone in **Vibration** mode. In **Setup → Vibration attribute tester**, tap each option (Alarm, Communication request, Hardware feedback, Ringtone) and keep the one you actually feel. *Alarm* is the most likely to get through OriginOS's restriction.

### Quick Settings tile
Pull down the shade, tap the pencil/edit icon and drag **Aura Light** into your tiles. It switches the light on using your saved colour/brightness. The Studio's safety cutoff still turns it off automatically.

---

## Aura light hardware: how it is driven

Third-party apps do **not** get an official API for vivo's dual-tone Aura ring, so the app has a 3-tier abstraction and an in-app **Hardware probe** (Setup tab → *Scan light hardware*):

| Tier | Method | Notes |
|---|---|---|
| 1 | `CameraManager.turnOnTorchWithStrengthLevel()` | Standard Android 13+ API. Lists every flash-capable camera ID (`cam:0`, `cam:1`, …) with its strength levels. If the device exposes separate IDs for the warm and cool LEDs, map them and colour temperature works. With one ID you control **brightness only**. |
| 2 | Vendor camera characteristics | The probe lists any non-`android.*` keys mentioning flash/torch/aura/led/vivo. This tier is **probe-only** for now: writing vendor capture-request tags needs an open camera session and has to be reverse-engineered per device. |
| 3 | Shizuku → `/sys/class/leds/*/brightness` | Runs a persistent `sh` through Shizuku (no root) and writes LED driver nodes directly. The scanner shows `max_brightness`, current value, and **whether each node is writable by the shell user**. On many production kernels these nodes are root-only. If yours are read-only, this tier can't work without root. |

**Calibrating:** after scanning, press **Test 1 s** on each channel to see which LED lights up, then tap **Warm** on the orange one and **Cool** on the white one. Once both are mapped, the Studio's temperature slider crossfades between them: `warm = b·min(1, 2(1−t))`, `cool = b·min(1, 2t)`. Select a backend (Auto / Camera torch / Shizuku sysfs) to force one tier.

Shizuku: install it from Play Store / GitHub, start it (wireless debugging or ADB), then tap **Authorise** in Setup.

---

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
├── hw/     TorchBackend.kt (tier 1+2), ShizukuBackend.kt (tier 3), AuraHal.kt, Vibes.kt
├── engine/ PatternEngine.kt       # Breathe / Inverse breathe / Strobe / Heartbeat
├── service/AuraNotificationListener.kt, AuraTileService.kt
└── ui/     MainActivity, StudioScreen, AppsScreen, SetupScreen, Components, Theme
```

## Build locally

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # minified, debug-signed
```

Requires JDK 17 and Android SDK platform 35.

## Known limitations

- Whether a given `VibrationAttributes` usage gets past OriginOS in Silent mode depends on the firmware. That's why there's a tester instead of a hard-coded choice.
- True warm/cool colour mixing needs either two torch IDs or writable sysfs LED nodes. With neither, the Studio still controls brightness and patterns, and the colour slider only changes the on-screen preview.
- The safety cutoff timer runs inside the app process. If OriginOS kills the app while the light is on, turn it off from the tile or the Studio.
