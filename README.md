# Tablo Auto

Listen to live TV from a **Tablo** over-the-air DVR while you drive, through **Android Auto**.

It is a media app and nothing more: the car browses the channel line-up, you tap one, and the
sound comes out of the car speakers. There is no picture — a car is no place for one, and sending
one would cost about a gigabyte an hour of mobile data for something nobody can watch.

> Unofficial. Not affiliated with, endorsed by, or supported by Tablo, Scripps or Google.

## What it does

- **Every channel, with what is on now** — antenna channels and the free streaming (FAST) channels
  on your account, each listed with the programme currently airing.
- **Next and previous change channel.** Tapping a channel loads the whole of its group around it,
  so the buttons on the steering wheel are channel up and channel down.
- **Audio only, at about 96 kbps** — roughly 46 MB an hour, against about 1.1 GB for the video
  stream. The server drops the picture before it encodes anything, so it costs it less too.
- **Reconnects by itself.** Drive through a dead spot and it comes back on its own; see below.
- **Voice search** — "play four one" or a station's name finds the channel.
- **Picks up where it left off.** Start the car, press play, and the last channel returns.
- **Keeps itself up to date** from this repository's releases.

## What it needs

A **[tablo-web](https://github.com/ksaye/tablo-web)** server (version 1.3.0 or newer) that can be
reached from the road — this app talks to that, not to the DVR directly. The DVR itself is on your
home network and the transcoding has to happen somewhere; tablo-web is what does both.

The audio stream comes from `/api/audio/channel/{id}.m3u8`, which is the endpoint that makes
reconnecting work, so an older tablo-web will not do.

## Install

The app is sideloaded.

```bash
adb install io.github.ksaye.tabloauto-1.0.0.apk
```

On Android 16 and newer, installing from a file manager or browser may be blocked by *Apps from
unverified developers* — which imposes a 24-hour delay before the setting can even be turned on.
Installing over `adb` is not subject to it.

Then, on the phone:

1. Open **Tablo Auto** and enter the address of your tablo-web site.
2. Press **Save and test**. If the site is behind a Microsoft Entra sign-in, a **Sign in** button
   appears — it signs in once, in a web view, and the app keeps the session from then on. The car
   never shows a sign-in screen.
3. Press **Play the first channel** to hear it working before you go anywhere.

### Making it appear in Android Auto

Android Auto hides sideloaded media apps until you tell it not to:

1. Open the **Android Auto** settings on the phone.
2. Tap the version number ten times to unlock **Developer settings**.
3. In the ⋮ menu, turn on **Unknown sources**.

## Reconnecting

This is the part the app exists for. A car drives out of coverage mid-sentence and back into it a
mile later, and none of that should need touching.

Every channel has one address on the server that never changes. It does not name a transcode
session — it joins whichever session is running for that channel, or starts one, and redirects.
So recovering from anything is the same move: prepare the player again, at the same URL.

Three things go wrong and all three end there:

| What happens | What the app does |
|---|---|
| The player gives up after failed loads | Prepares again, backing off 1s, 2s, 4s … to 30s |
| The player hangs in a buffering state instead | A watchdog sees the position stop advancing and forces the same recovery |
| Signal returns during a backoff | A network callback retries at once rather than waiting out the delay |

Playback resumes a little way behind the live edge on purpose: those few seconds are already
downloaded, so a short dropout is covered by the buffer instead of a silence.

## Building

Needs a JDK 17 and the Android SDK (platform 36).

```bash
./build-apk.sh 1.0.0 1        # version, versionCode
```

Signing comes from a `keystore.env` in the project root:

```
KEYSTORE_PATH=/path/to/tabloauto.keystore
KEYSTORE_PASSWORD=…
KEY_ALIAS=tabloauto
KEY_PASSWORD=…
```

Android only installs an update over an app signed with the **same** key, so keep that keystore and
reuse it for every release.

Self-updates default to this repository's GitHub releases. A private build can point somewhere
else — a Gitea server on your own network, say — with an `update.env` alongside it:

```
UPDATE_API_BASE=https://example.org/gitea/api/v1
UPDATE_OWNER=…
UPDATE_REPO=…
UPDATE_TOKEN=…
```

Neither file is committed.

## How it is put together

| File | What it does |
|---|---|
| `TabloMediaService.kt` | Everything the car sees: the browse tree, the queue, the player |
| `Reconnector.kt` | Surviving dead spots — backoff, stall watchdog, live-edge handling |
| `TabloClient.kt` | Talking to tablo-web, and attaching the sign-in cookie in one place |
| `ChannelRepository.kt` | The channel list, cached to disk so the car never waits on a request |
| `SignInActivity.kt` | The one-time Entra sign-in |
| `MainActivity.kt` | The phone screen: address, sign-in, a way to try it, updates |
| `Updater.kt` | Checking the release feed and installing a newer build |

## Licence

MIT — see [LICENSE](LICENSE).
