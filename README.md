# Android Mirroring & Remote Control (`:server` + `:client`)

A low-latency, scrcpy-style Android-to-Android screen mirroring and remote control suite written in Kotlin for a **rooted headless Android 16 (API 36) host (`1080x2400`)** and a **non-rooted Android client** over Wi-Fi TCP.

## Modules

- **`:server`** — Headless APK executed via `app_process` as root:
  - Listens on TCP port `27183` with `TCP_NODELAY` enabled.
  - Uses [LSPosed AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (`HiddenApiBypass.addHiddenApiExemptions("L")`) to call hidden framework APIs on [Android 16 (API 36)](https://developer.android.com/about/versions/16/changes/non-sdk-16).
  - Mirrors `Display.DEFAULT_DISPLAY` onto a hardware `MediaCodec` input `Surface` via `DisplayManager.createVirtualDisplay(String, int, int, int, Surface)` (primary path on Android 14–16, matching [scrcpy](https://github.com/Genymobile/scrcpy/issues/5137)), with a `SurfaceControl.createDisplay` + transaction reflection fallback.
  - Encodes H.264/AVC at **12 Mbps**, **60 FPS**, `KEY_LOW_LATENCY = 1`, `KEY_PRIORITY = 0`, streaming raw Annex-B NAL units with a 12-byte header.
  - Reads 14-byte binary touch/control packets, reconstructs multi-touch `MotionEvent`s scaled to the host display size, and injects touch and navigation `KeyEvent`s via `InputManagerGlobal.injectInputEvent` (Android 14–16, per [scrcpy](https://github.com/Genymobile/scrcpy/issues/4074)) with `InputManager.injectInputEvent` fallback.
- **`:client`** — Standard non-rooted Android APK:
  - Minimal connection screen (`ConnectActivity`) with default host `192.168.31.109:27183` and Bitrate/FPS selectors.
  - Kotlin Coroutines TCP client (`MirrorClient`) with `TCP_NODELAY`.
  - Zero-copy hardware `MediaCodec` H.264 decoder (`VideoDecoder`) outputting directly to a full-screen `SurfaceView`.
  - Aspect-ratio scaling, letterbox offset computation, and multi-touch capture (`TouchOverlayController`).
  - Floating navigation bar overlay (**Back**, **Home**, **Recents**, **Exit**) in `MirrorActivity`.
- **`magisk/mirror.sh`** — Magisk `/data/adb/service.d/mirror.sh` auto-start and watchdog script.

## Binary Wire Protocol

### Video Stream (`:server` → `:client`)

| Field | Size | Type (Big-Endian) | Notes |
| --- | --- | --- | --- |
| `ptsUs` | 8 bytes | `Long` | Presentation timestamp in microseconds (`-1L` = `BUFFER_FLAG_CODEC_CONFIG` SPS/PPS) |
| `length` | 4 bytes | `Int` | Payload byte length |
| `payload` | `length` bytes | `ByteArray` | Raw H.264 Annex-B NAL unit(s) |

### Control Stream (`:client` → `:server`, fixed 14 bytes)

| Field | Size | Type (Big-Endian) | Notes |
| --- | --- | --- | --- |
| `action` | 2 bytes | `Short` | `0`=DOWN, `1`=UP, `2`=MOVE, `3`=CANCEL, `5`=POINTER_DOWN, `6`=POINTER_UP, `100`=KEY_PRESS, `101`=CONFIG_STREAM |
| `param` | 4 bytes | `Int` | `pointerId` (touch), `KeyEvent.KEYCODE_*` (nav key), or `bitrateBps` (config) |
| `normX` | 4 bytes | `Float` | Normalized X `[0.0..1.0]` (touch) or target FPS (config) |
| `normY` | 4 bytes | `Float` | Normalized Y `[0.0..1.0]` (touch) |

## Build & Deployment

### 1. Build Both APKs
```bash
./gradlew :server:assembleDebug :client:assembleDebug
```

### 2. Deploy `:server` & Magisk Auto-Start to Rooted Host
```bash
adb push server/build/outputs/apk/debug/server-debug.apk /data/local/tmp/mirror-server.apk
adb push magisk/mirror.sh /data/local/tmp/mirror.sh
adb shell su -c "cp /data/local/tmp/mirror.sh /data/adb/service.d/mirror.sh && chmod 755 /data/adb/service.d/mirror.sh"
```

To start immediately without rebooting:
```bash
adb shell su -c "/data/adb/service.d/mirror.sh"
adb shell su -c "tail -f /data/local/tmp/mirror-server.log"
```

### 3. Install `:client` on the Non-Rooted Android Device
```bash
adb install client/build/outputs/apk/debug/client-debug.apk
```
Open **Mirror Client**, confirm `192.168.31.109` / `27183`, pick your desired bitrate/FPS, and tap **Connect**.
