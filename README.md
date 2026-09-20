# Tablo Auto

Listen to live TV from a **Tablo** over‑the‑air DVR while you drive, through **Android Auto**.  
The app streams only audio (≈96 kbps) so it uses little data and no picture is shown in the car.

> Unofficial. Not affiliated with, endorsed by, or supported by Tablo, Scripps or Google.

---

## Features

| Feature | Description |
|---------|-------------|
| Full channel list | Antenna channels + free‑streaming (FAST) channels from your account, each shown with the program that is currently airing. |
| Channel navigation | Tap a channel to load its group; use steering‑wheel “channel up/down” buttons for next/previous. |
| Audio‑only streaming | 96 kbps (~46 MB/hr). The server drops the picture before encoding, saving bandwidth and CPU. |
| Automatic reconnect | Exponential back‑off on player errors, watchdog for stalled buffering, immediate retry when network returns. |
| Voice search | “Play four one” or a station’s name finds the channel (implemented in `TabloMediaService`). |
| Resume last channel | When playback starts with no selection, the app restores the previously chosen channel. |
| Self‑update | Checks its own GitHub releases and downloads/installs updates automatically. |

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
* **Android device** (API 26+) with Android Auto installed.
* A USB cable or Wi‑Fi for `adb` if you want to sideload.

---

## Installation

The app is sideloaded – it is not available on Google Play.

```bash
adb install io.github.ksaye.tabloauto-1.0.1.apk
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
* **Play first channel** – press the *Play the first channel* button on the phone screen.  
  The audio starts playing in Android Auto, and you can control it with steering‑wheel buttons or voice commands.
* **Stop playback** – press the *Stop* button on the phone screen or use the car’s stop command.
* **Voice search** – say a station number, call sign or program title; the app will find the matching channel.

The app automatically reconnects if the network drops and resumes the last channel when you start playback again.

---

## Building

### Prerequisites

* JDK 17
* Android SDK (platform 36)
* `keystore.env` (optional) – contains signing credentials for a release build.
* `update.env` (optional) – contains GitHub API token and update feed URL.

### Build a signed APK

```bash
./build-apk.sh <version> <versionCode>
# e.g. ./build-apk.sh 1.0.1 2
```

The script writes the version into `app/build.gradle.kts`, runs Gradle, and copies the resulting APK to:

```
app/build/outputs/apk/release/io.github.ksaye.tabloauto-<version>.apk
```

If `keystore.env` is present the APK will be signed with your release key; otherwise it will be debug‑signed.

### Build without the helper script

```bash
./gradlew :app:assembleRelease
```

The resulting APK can be found in:

```
app/build/outputs/apk/release/app-release.apk
```

---

## Self‑Update

`TabloAuto` checks its own GitHub releases and downloads a new APK if available.  
Configuration is read from `update.env` or the following environment variables:

| Variable | Default |
|----------|---------|
| `UPDATE_API_BASE` | `https://api.github.com` |
| `UPDATE_OWNER` | `ksaye` |
| `UPDATE_REPO` | `tablo-auto` |
| `UPDATE_TOKEN` | (empty) |

If no update feed is configured, the app shows *Updates are not configured for this build.*

---

## License

MIT – see [LICENSE](LICENSE).

---
