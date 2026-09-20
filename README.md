# Tablo Auto

Listen to live TV from a **Tablo** over‑the‑air DVR while you drive, through **Android Auto**.  
The app streams only audio (≈96 kbps) so it uses little data and no picture is shown in the car.

> Unofficial. Not affiliated with, endorsed by, or supported by Tablo, Scripps or Google.

---

## Features

| Feature | Description |
|---------|-------------|
| **Full channel list** | Antenna channels + free‑streaming (FAST) channels from your account, each shown with the program that is currently airing. |
| **Channel navigation** | Tap a channel to load its group; use steering‑wheel “channel up/down” buttons for next/previous. |
| **Audio‑only streaming** | 96 kbps (~46 MB/hr). The server drops the picture before encoding, saving bandwidth and CPU. |
| **Automatic reconnect** | Exponential back‑off on player errors, watchdog for stalled buffering, immediate retry when network returns. |
| **Voice search** | “Play four one” or a station’s name finds the channel (implemented in `TabloMediaService`). |
| **Resume last channel** | When playback starts with no selection, the app restores the previously chosen channel. |
| **Self‑update** | Checks its own GitHub releases and downloads/install updates automatically. |

---

## Architecture

```
┌───────────────────────┐
│ Android Auto (UI)     │
├─────────────┬─────────┤
│             │         │
│ TabloClient ├─► Reconnector  (reconnect logic)
│             │         │
│ TabloMediaService (media session, ExoPlayer)
│             │
│ Updater      (self‑update via GitHub API)
└─────────────┴─────────┘
```

* **`TabloClient`** – HTTP client that talks to a `tabloweb` server.  
  It attaches the sign‑in cookie only for requests to the configured host.
* **`Reconnector`** – Handles player errors, buffering stalls and network changes; re‑initialises the ExoPlayer instance with the same stable channel URL (`/api/audio/channel/{id}.m3u8`).
* **`TabloMediaService`** – Implements `MediaLibraryService` for Android Auto browsing and playback.  
  Uses Media3 (`ExoPlayer`, `MediaSession`) to stream audio.
* **`Updater`** – Reads the GitHub releases API (configurable via env vars) and installs new APKs through a `FileProvider`.

All configuration is stored in `Settings` (shared preferences). The app can also read a cookie directly from the user or from an environment variable (`COOKIE`) for advanced use.

---

## Prerequisites

* **Tablo Web server** – version 1.3.0+ reachable over the Internet.  
  The app talks to this server, not directly to the DVR.
* **Android device** (Android 16+).  
  Android Auto requires at least API 23; the app targets API 26–36.

---

## Installation

The app is sideloaded – it is not available on Google Play.

```bash
adb install io.github.ksaye.tabloauto-1.0.0.apk
```

> On Android 16+ installing from a file manager or browser may be blocked by *Apps from unverified developers*.  
> Installing via `adb` bypasses this restriction.

### Making the app visible in Android Auto

Android Auto hides sideloaded media apps until you enable developer mode:

1. Open **Android Auto** on your phone.  
2. Tap the version number ten times → **Developer settings**.  
3. In the menu, turn on **Unknown sources**.

---

## Configuration

Open the app and follow these steps:

1. **Enter Tablo Web address** (e.g., `https://tv.example.org`).  
   The URL must include the scheme (`http` or `https`).
2. Tap **Save and test**.  
   *If the site requires Microsoft Entra sign‑in*, a **Sign in** button appears – tap it to authenticate once; the session cookie is stored automatically.
3. (Optional) Paste a session cookie manually by tapping **Advanced → Paste a session cookie instead**.  
   The cookie will be used for all requests to the same host.

The app remembers the address and cookie between launches.

---

## Usage

* **Browse channels** – tap any channel in the list; the car’s media browser shows the group.
* **Play first channel** – use the “Play the first channel” button on the main screen to test playback before driving.
* **Stop** – press the stop button or use the car’s media controls.
* **Voice search** – say a station number, call sign or program title; the app will select the matching channel.

The app automatically reconnects if you drive through a dead spot and returns when connectivity is restored.

---

## Building from source

You need JDK 17 and Android SDK (platform 36).  
`build-apk.sh` writes the version into `app/build.gradle.kts`, builds, signs (if a keystore is available) and copies the APK to `app/build/outputs/apk/release`.

```bash
# Build release with version 1.0.0 and Android versionCode 1
./build-apk.sh 1.0.0 1
```

### Signing

Create a keystore once:

```bash
keytool -genkeypair -v \
    -keystore tabloauto.keystore -alias tabloauto \
    -keyalg RSA -keysize 4096 -validity 10950
```

Place the following in `keystore.env` (not committed):

```
KEYSTORE_PATH=/path/to/tabloauto.keystore
KEYSTORE_PASSWORD=...
KEY_ALIAS=tabloauto
KEY_PASSWORD=...
```

If `keystore.env` is missing, the build produces a debug‑signed APK that cannot be upgraded.

### Self‑update configuration

The app can check its own GitHub releases.  
Set optional environment variables in `update.env` (not committed) or export them:

| Variable | Purpose | Default |
|----------|---------|---------|
| `UPDATE_API_BASE` | API base URL | `https://api.github.com` |
| `UPDATE_OWNER` | Repo owner | `ksaye` |
| `UPDATE_REPO` | Repo name | `tablo-auto` |
| `UPDATE_TOKEN` | Personal access token (for private repos) | empty |

These values are injected into the APK as `BuildConfig` fields.

---

## License

MIT – see [LICENSE](LICENSE).

---
