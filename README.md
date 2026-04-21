# ADB Relay Android

Small Android app that acts as the **`device`** side of the remote ADB bridge: it opens an outbound WebSocket to your relay, completes the JSON handshake, then demultiplexes **ADBM** frames to a TCP connection toward `adbd` (same contract as `tools/remote-adb-relay/agent.cjs` in the `android-ide-extension` repo).

## Requirements

- **JDK 11** (Gradle 7.6 + Android Gradle Plugin 7.4)
- **Android SDK** (Android Studio or command-line `sdkmanager`)
- Set `ANDROID_HOME` (or create `local.properties` with `sdk.dir=...`)

## Build

```bash
export JAVA_HOME=/path/to/jdk-11
export ANDROID_HOME=$HOME/Library/Android/sdk   # example on macOS
./gradlew assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

UI is **Material 3** with **edge-to-edge** (transparent system bars, window insets on toolbar and content) and **day/night** color schemes.

## Configure on the phone

1. Enable **Wireless debugging** (Developer options); the app’s **Open Developer options** button jumps there so you can open **Wireless debugging** (pair with your PC when prompted).
2. Fill **Host** and **Port** under **Custom address** from the Wireless debugging screen (often `127.0.0.1` and the debug port shown).
3. Paste the relay **WebSocket URL** (`ws://` / `wss://`) and **session token** exactly as on the developer machine — **Wireless / remote ADB…** → **Internet relay** → same fields or **Copy for phone**.
4. Tap **Connect**. A foreground notification stays active while the tunnel is up; use **Disconnect** or the notification action to stop.

Relay URL, token, host, and port are stored with **EncryptedSharedPreferences**.

### Optional: open Wireless debugging via `adb` (USB)

If the Settings UI is hard to reach, on many AOSP builds:

```bash
adb shell am start -n com.android.settings/.Settings \
  --es :settings:fragment com.android.settings.development.WirelessDebuggingFragment
```

**Quick Settings:** in **Developer options** → **Quick settings developer tiles**, add **Wireless debugging**; **long-press** the tile opens the full screen.

## Protocol

See `android-ide-extension` → `docs/remote-adb-bridge-protocol.md` for handshake, **ADBM** mux layout, and security notes.

## Notes

- `usesCleartextTraffic` is enabled so `ws://` LAN relays work; prefer **`wss://`** on the public internet.
- The app does not replace Android’s wireless-debug pairing flow; it only forwards multiplexed traffic to local `adbd` after debugging is enabled.
