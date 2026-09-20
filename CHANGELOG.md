# Changelog

Newest first.

## 1.0.0 — 2026-09-20

First release.

- Live TV in the car, sound only: every antenna and FAST channel, listed with what is on now.
- Next and previous change channel, because the queue is the channel's own group.
- Audio-only streams at about 96 kbps (~46 MB an hour) from tablo-web's
  `/api/audio/channel/{id}.m3u8`.
- Reconnects by itself after a dead spot: exponential backoff on player errors, a watchdog for the
  case where the player hangs rather than fails, and an immediate retry when the network returns.
- One-time Microsoft Entra sign-in on the phone; the car never asks.
- Voice search over channel numbers, call signs and the programme that is on.
- Resumes the last channel when the car asks for playback with nothing chosen.
- Checks its own release feed for updates.
