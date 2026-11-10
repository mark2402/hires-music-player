# Hires

Hires is a native Android music player for local lossless and hi-res files. It uses Kotlin, Jetpack Compose, and Media3 (ExoPlayer) with a `MediaSessionService`, so playback continues in the background with notification and lock-screen controls.

It also has a YouTube Music companion. That companion does **not** stream or extract YouTube Music audio. Playback stays inside the YouTube Music app.

## Features

- Library from MediaStore, plus folders chosen with the Storage Access Framework.
- Browse songs, albums, artists, and folders. Search across those fields.
- Now Playing with album art, seek bar, shuffle, repeat, and a queue.
- Quality badge for the file, for example `FLAC 24-bit / 96 kHz`.
  - **Hi-Res** when the file is lossless and better than CD (bit depth above 16 or sample rate above 44.1 kHz).
  - **Lossless** for other lossless files, including 16-bit / 44.1 kHz FLAC, ALAC, WAV, and AIFF.
  - **Lossy** for MP3, AAC, Opus, and Vorbis.
- Gapless transitions when the container provides gapless metadata and the next track uses the same PCM format. A sample-rate or channel change recreates the audio track and can insert a short gap.
- Hi-res output path that prefers 32-bit float for high-resolution PCM and, on Android 14+, requests a bit-perfect USB mixer only when Android reports one that matches the buffers actually written.
- YouTube Music panel: current title, artist, art, and play state; play, pause, next, previous, and seek; open the app; search via `https://music.youtube.com/search?q=...`. If YouTube Music is not installed, those actions open its Play Store listing.

## Supported formats

| Format | How it plays |
| --- | --- |
| FLAC | Media3 extractor, decoded by the bundled FFmpeg extension (FLAC decoder). Falls back to the platform decoder if FFmpeg cannot start. |
| ALAC (`.m4a`) | Media3 MP4 extractor, decoded by the bundled FFmpeg ALAC decoder. Platform decoders usually cannot play ALAC. |
| WAV | Media3 WAV extractor, including PCM and IEEE float. |
| AIFF / AIFC | Media3 AIFF extractor for uncompressed PCM (`NONE`, `sowt`, `twos`, float). |
| MP3 | Platform decoder. |
| AAC in `.m4a` / `.aac` | Platform decoder. |
| Ogg Vorbis and Opus | Platform decoder. |

The FFmpeg extension is the Media3 `FfmpegAudioRenderer` from AndroidX Media 1.11.1, linked against FFmpeg 6.0 with only the `flac` and `alac` decoders enabled. Prebuilt `libffmpegJNI.so` libraries for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` are in `decoder-ffmpeg/src/main/jniLibs`. Rebuild them with `decoder-ffmpeg/rebuild-native.sh` (NDK r26 or newer). FFmpeg is LGPL 2.1; see `decoder-ffmpeg/FFMPEG-LGPL-2.1.txt`. The Media3 JNI and Java sources in that module stay under the Apache 2.0 license in their file headers.

`EXTENSION_RENDERER_MODE_PREFER` puts FFmpeg ahead of MediaCodec for formats it supports, because platform FLAC decoders often emit 16-bit PCM even when the file is 24-bit. Decoder fallback stays on, so a failure in FFmpeg still tries the platform codec.

## Hi-res output, without overclaiming

Android mixes application audio by default. Hires does not call that bit-perfect.

What the app actually does:

- Float output is enabled. For FLAC and ALAC decoded by FFmpeg, the renderer writes 32-bit float when the device can play float directly. 24-bit integers are exactly representable in float32, so this does not throw away a 24-bit sample, but it is **not** integer bit-perfect.
- 16-bit WAV and AIFF stay 16-bit. 24-bit and float WAV/AIFF are sent as 32-bit float when the device allows it.
- On Android 10+, the app asks `AudioManager.getDirectPlaybackSupport` (or the older `AudioTrack.isDirectPlaybackSupported` on Android 9) whether that PCM format can be played at the file sample rate. If the device says no, the Now Playing line says the stream may be resampled.
- On Android 14+ (API 34), if a USB device (`TYPE_USB_DEVICE` or `TYPE_USB_HEADSET`) advertises `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` at the same sample rate, channel count, and PCM encoding the app will write, Hires calls `AudioManager.setPreferredMixerAttributes`. The badge says **Bit-perfect USB** only when that call returns true. The HAL still has to honor the request; the app cannot see inside the DAC.
- A 24-bit integer USB mixer does **not** get a bit-perfect label when the app is writing float. The label is withheld rather than stretched.
- If the active decoder name is a platform codec (`c2.*`, `OMX.*`) for FLAC or ALAC, the app says bit depth above 16-bit was not preserved.
- After playback starts, the opened `AudioTrack` sample rate and encoding replace the prediction. A different sample rate clears any bit-perfect claim.
- Audio offload and silence skipping are left off. Speed control is not exposed, so Sonic resampling is not in the path during normal playback.

There is no DSD or MQA path. Multichannel files play when ExoPlayer and the device accept them; bit-perfect matching is implemented for the reported channel count, and direct-playback checks are only done for mono and stereo.

## YouTube Music companion

YouTube Music has no public streaming API. Hires does not open its network streams, download audio, or parse its files. Local playback does not need the YouTube Music tab or notification access.

The YouTube Music tab:

1. Treats notification access as optional. Without it, the library still plays. The tab explains that the permission is only used to see and control `com.google.android.apps.youtube.music` (play, pause, skip, seek). The app does not read notification text, SMS, banking apps, or other apps.
2. On Honor and other Android 13+ phones, a sideloaded app is blocked by a restricted setting. Hires cannot bypass that. The screen tells the user to open App info, use the three-dot menu, choose **Allow restricted settings**, then enable Notification access. That menu item sometimes appears only after one denied attempt. Buttons open Hires' App info (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`) and the system notification-listener list.
3. When access is allowed, shows the active YouTube Music session from `MediaSessionManager.getActiveSessions` and sends play, pause, next, previous, and seek through that session's `MediaController`.
4. **Open in YouTube Music** launches the app. The search box opens `https://music.youtube.com/search?q=...` with the YouTube Music package. If the app is not installed, both actions open the Play Store (`market://details?id=com.google.android.apps.youtube.music`, then the web listing).

The panel is labeled **Lossy · plays in YouTube Music**. Local files are labeled **Plays in this app**.

## Your files stay put

Hires has no `INTERNET` permission and does not upload audio. Library scans use MediaStore queries and read-only Storage Access Framework trees (`FLAG_GRANT_READ_URI_PERMISSION` only). The app does not request all-files access, and it does not delete, rename, move, or overwrite music files. Album art is read from the file or MediaStore. Uninstalling Hires removes the app and its private settings only. Backup, when enabled, stores the folder bookmark preference, not audio. The Library screen has an About and safety note with the same statement.

## Build

- JDK 17 or newer (the project is built with JDK 21).
- Android SDK platform **36** (Android 16, stable). `compileSdk` and `targetSdk` are **36**. `minSdk` is 26. The first debug build targeted API 37 and was signed with scheme v2 only; sideload installers on phones such as MagicOS reject that. Use the release APK below.
- Set `ANDROID_HOME` or `local.properties` `sdk.dir`.

```bash
./gradlew :format-core:test assembleRelease
```

The sideload APK is `app/build/outputs/apk/release/app-release.apk`. It is signed with the committed keystore `app/keystore/hires-release.jks` (alias `hires`, store and key password `hires-sideload`) using APK Signature Schemes v1, v2, and v3. It is not debuggable, not `testOnly`, and not minified. All four ABIs are included. This keystore is for sideloading only, not a Play upload key.

Unit tests live in `format-core` and do not need a device. They cover header probing (including FFmpeg-generated FLAC, ALAC, WAV, AIFF, MP3, AAC, Opus, and Vorbis fixtures), the Hi-Res / Lossless / Lossy rules, output planning, and YouTube Music search URLs.

## Permissions

- `READ_MEDIA_AUDIO` on Android 13+, or `READ_EXTERNAL_STORAGE` through Android 12, for the MediaStore library.
- Storage Access Framework folder access, read-only. The tree grant is `FLAG_GRANT_READ_URI_PERMISSION` only.
- `POST_NOTIFICATIONS` so the media notification can be posted on Android 13+. Playback continues if this is denied; the notification may be hidden.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` for background playback.
- Notification listener access is optional and only for the YouTube Music companion. Sideloaded copies on Android 13+ may need App info → Allow restricted settings before the toggle can be turned on.

## Layout

- `format-core` — pure Kotlin probing, quality badges, and output planning.
- `decoder-ffmpeg` — Media3 FFmpeg audio renderer and native libraries.
- `app` — Compose UI, Media3 playback service, MediaStore and SAF library, YouTube Music companion.
