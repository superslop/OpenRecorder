# Open Recorder Power

A fork of [NoneBaiano/OpenRecorder](https://github.com/NoneBaiano/OpenRecorder), an
open-source screen recorder for Android with a modern interface. No ads, no trackers
and no internet access. This fork changes how recording stops and what audio it
records. Everything else keeps the upstream behavior.

## What changed in this fork

- **All code converted from Java to Kotlin.** The recording engine keeps the same
  algorithms, constants, and lifecycle behavior; only the language changed.
- **Power-button-only stop.** While recording, there is no stop or pause button:
  no floating overlay, no notification stop/pause actions, no main-screen stop
  button, and the Quick Settings tile does not stop a recording. Press the Power
  button once and the recording stops and saves when the screen turns off.
- **Microphone-only audio.** The audio choice is microphone or no audio. Internal
  audio options were removed from the settings. A previously saved internal-audio
  setting is treated as microphone.
- **Package renamed** from `com.openrecorder.app` to
  `io.github.superslop.openrecorder`, so this fork can be installed next to the
  upstream app.
- **Version** `1.0.3-power1` (version code 10004).

## How to use it

1. Open the app, pick your settings, and tap Start recording.
2. Confirm the Android screen-capture prompt.
3. Record. While recording, the app shows "Recording. Press the Power button
   to stop." and offers no stop control.
4. Press the **Power button once**. The screen turns off, the recording stops,
   and the video is saved to `Movies/Open Recorder`.
5. Unlock the phone to watch, share, or delete the video from the saved
   notification or the Recordings tab.

To cancel a recording countdown before it starts, tap Cancel while it counts
down. Once recording has started, only the Power button stops it.

## Warning: any screen-off stops the recording

Stopping works by reacting to the screen turning off. That means a display
timeout also stops the recording. Before a long recording, set the display
timeout to the longest available value in Android Settings.

## Verification status

This fork is built and unit-tested by GitHub Actions. It has **not** been tested
on a physical device. The recording engine is the upstream code converted to
Kotlin, but on-device behavior (including the Power-button stop) is unverified
until someone runs it on a phone.

## Features

### Recording

- Record your screen to MP4 files saved in `Movies/Open Recorder`
- **Audio sources:** microphone or no audio
- **Audio sample rate:** 44.1 kHz or 48 kHz
- **Video codec:** H.264 or H.265
- **Resolution:** native, 2160p, 1440p, 1080p, 720p, or 480p
- **Frame rate:** automatic, 120, 90, 60, or 30 fps
- **Video bitrate:** automatic, 4, 8, 16, or 24 Mbps
- **Orientation:** automatic, portrait, or landscape
- **Force 16:9 with letterboxing:** fits the full capture inside a 16:9 frame without cropping
- **Stop on screen off:** always on. The recording stops when the screen locks
- **Recording countdown:** off, 3, 5, or 10 seconds, so you can open the screen you want to capture. You can cancel while it counts down
- **File naming pattern:** `dd-mm-yyyy`, `mm-dd-yyyy`, `yyyy-mm-dd`, or `yyyy-dd-mm`, followed by `_hh-mm-ss`

### Controls

- Start from the app's main screen or from the Quick Settings tile
- **No stop or pause controls while recording.** The foreground notification has
  no actions, the main screen shows status text only, and tapping the Quick
  Settings tile while recording shows a reminder to press the Power button
- Recording keeps running if you close the app
- After saving, a notification lets you **watch**, **share**, or **delete** the video

### Recordings tab

- Lists the videos recorded by this app, with total count
- Sort by date, size, or duration
- Open a video in your default player
- Share one or multiple selected recordings with other apps
- Select one, several, or all recordings and delete them (Android asks you to confirm)

### Appearance and settings

- Light, dark, or follow-system themes
- Dynamic color (Monet) variants of each theme
- Page scale adjust the interface from 90% to 110% using a slider
- Optional predictive back gesture (off by default)
- Settings are saved between sessions

### About

- Project page with a link to the source code and the GPL-3.0 license
- Built-in list of open-source licenses used by the app

## Privacy

- **No internet permission.** The app cannot connect to the network
- No ads, no analytics, no crash reporting, no tracking
- Backups are disabled
- Recordings stay on your device
- Other apps are not allowed to capture this app's audio

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone capture |
| `POST_NOTIFICATIONS` | Recording notification and "saved" notification |
| `FOREGROUND_SERVICE` | Keeps recording while the app is in the background |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required by Android for screen capture services |
| `FOREGROUND_SERVICE_MICROPHONE` | Required by Android for microphone capture services |

## Requirements

Android 10 (API 29) or later.

## Limitations

These come from Android itself, not from this app:

- Android asks for your confirmation every time you start a recording.
- Apps that block capture (DRM video, banking apps, anything using `FLAG_SECURE`) can appear black or silent.

## Credits

- This fork is based on [Open Recorder](https://github.com/NoneBaiano/OpenRecorder)
  by NoneBaiano, licensed under GPL-3.0-only.
- Capture engine inspired by the Android Open Source Project (AOSP) SystemUI screen recorder
- UI built with [Miuix](https://github.com/compose-miuix-ui/miuix) for Compose
- Dynamic colors powered by [MaterialKolor](https://github.com/jordond/materialkolor)
- Navigation and UI are adapted from [AsteriskNG](https://github.com/Asterisk4Magisk/AsteriskNG) (Copyright 2026, AsteriskNG contributors), licensed under GPL-3.0.
See [NOTICE] (NOTICE) for full third-party attributions.

## License

[GPL-3.0-only](LICENSE)
