# Bit-Perfect Audio Detector

## What is bit-perfect?

Bit-perfect means the audio reaches the DAC exactly as it is in the file, bit for bit. Normally
Android sends all sound through its mixer, which can change the sample rate (for example, to
48 kHz), adjust the volume digitally and mix it with other system sounds. In bit-perfect mode the
mixer is bypassed and the DAC receives the original data, at its own sample rate and bit depth.

## Bit-perfect on Android

Android added [official support](https://source.android.com/docs/core/audio/preferred-mixer-attr)
in Android 14, with the AudioMixerAttributes API and the MIXER_BEHAVIOR_BIT_PERFECT mode, only for
USB DACs. However, having Android 14 is not enough: the manufacturer must enable it in the phone's
audio configuration, and the music app must request it. Before Android 14, only some apps achieved
it by using their own proprietary USB driver (e.g. USB Audio Player Pro, FiiO Music, HiBy Music,
among others).

## Why is it useful?

It helps audiophiles to identify which phones have native bit-perfect support before purchase.

## How to make the test?

Download the Bit-Perfect Audio Detector app from the [Releases](https://github.com/branches78/Bit-Perfect-Audio-Detector/releases)
page of this repository and install it on your phone (Android 14 or later). Open it to see the
results; for a definitive result, connect your USB DAC. Then you can tap "Send report" to add your
phone to the [public table of phones](https://bit-perfect-detector-web.onrender.com).

## Technical features

Native Android app (Kotlin + Jetpack Compose) that tells you whether a phone supports Hi-Res
audio (192 kHz / 24 bits) and Android's bit-perfect mode (`AUDIO_OUTPUT_FLAG_BIT_PERFECT`),
whether a USB DAC is connected, and which installed music apps use Android's native bit-perfect
mode.

Users can send their results to the public table of phones on the
[Bit-Perfect Audio Detector website](https://bit-perfect-detector-web.onrender.com).

- `minSdk 34`: installs only on Android 14 or later (bit-perfect mode was added in Android 14).
- Only the `INTERNET` permission, used just to send the anonymous report when the user taps "Send".
  The manifest declares `<queries>` to see installed audio players (it doesn't use
  `QUERY_ALL_PACKAGES`).
- Available in 10 languages: English, Spanish, German, French, Italian, Portuguese, Turkish,
  Japanese, Korean and Hindi.

### Building

1. Open the project in Android Studio (Ladybug or later) and let Gradle sync.
2. Run the `app` configuration on a phone with Android 14+, or run `./gradlew assembleDebug`.
   The APK is written to `app/build/outputs/apk/debug/`.
3. Unit tests (policy reader and app scanner): `./gradlew test`.

#### Release build

Release builds are shrunk with R8 and signed with the keystore set in `keystore.properties` (ignored
by git; see `keystore.properties.example`). Without that file they come out unsigned.
`./gradlew assembleRelease` writes the APK to `app/build/outputs/apk/release/`.

### How support is detected

| Situation | Source | Reliability |
|---|---|---|
| USB DAC connected | `AudioManager.getSupportedMixerAttributes(dac)` contains `MIXER_BEHAVIOR_BIT_PERFECT` | Definitive |
| No DAC | Reads `audio_policy_configuration.xml` (and its `xi:include`s, e.g. `usb_audio_policy_configuration.xml`) from `/odm/etc`, `/vendor/etc/audio`, `/vendor/etc` and `/system/etc`, and looks for a `mixPort` with `AUDIO_OUTPUT_FLAG_BIT_PERFECT` routed to `AUDIO_DEVICE_OUT_USB_DEVICE` | High, "according to the manufacturer" |
| No DAC, Hi-Res | Output `mixPort`s with profiles >= 192 kHz and >= 24 bits, dynamic USB output, `getDirectPlaybackSupport(192k/24)` and the profiles of connected outputs | High / medium |

#### Main limitation

Android has no public API that reports bit-perfect support without a connected DAC:
`getSupportedMixerAttributes()` requires an `AudioDeviceInfo`, and Android only provides
`AudioDeviceInfo` objects for connected devices. That's why, without a DAC, the app reads the
manufacturer's audio configuration, which is exactly where the flag is declared according to the
[AOSP documentation](https://source.android.com/docs/core/audio/preferred-mixer-attr).

Cases where reading without a DAC may fail:
- A manufacturer that doesn't allow reading those files: the app says so ("Can't tell without a DAC").
- Phones with an AIDL HAL that build the configuration in code instead of XML.
  The app shows this in the technical details of the "Send report" dialog so it can be checked.

With a DAC connected, the result comes from Android and is definitive.

### "Compatible apps" card

Always shown, with or without a DAC. It lists the installed music apps that use Android 14's
native bit-perfect mode and, below them, other compatible apps available on the Play Store
(a hand-picked list in `SUGGESTED_APPS`, `audio/BitPerfectAppScanner.kt`).

Android has no API to ask which apps support it, so `BitPerfectAppScanner`:

1. Finds installed players: apps that open audio (`ACTION_VIEW` with `audio/*`), with the
   `APP_MUSIC` category, or with a `MediaBrowserService` / Media3 service. The apps in
   `SUGGESTED_APPS` are also looked up by package name (declared in `<queries>`), because Android
   sometimes leaves an app out of those queries even though it matches (seen with PureBit on
   Android 16).
2. Reads the `classes*.dex` files of their APK and looks for the call to
   `AudioManager.setPreferredMixerAttributes`, the only way to request bit-perfect mode.
   R8/ProGuard can't rename system methods, so the call shows up even in obfuscated apps.
3. Apps with their own USB driver (e.g. UAPP) don't use that API and are left out. There's also an
   exclusion list (`EXCLUDED_PACKAGES`) in case one contains the call without relying on it.

The scan takes a few seconds: it runs when the app opens and when you tap "Analyze again".

Limitations:
- Having the call in the code doesn't guarantee the app always uses it (it may depend on a
  setting or come from an unused library).
- An app that calls the API through reflection isn't detected.

### Reports and website

The "Send report" button shows what will be sent and, when the user taps "Send", posts the report
(`report/ReportSender.kt`) to the
[report server](https://github.com/branches78/Bit-Perfect-Audio-Detector-Server), a small open-source
Node.js server that forwards it to the maintainer by email and **stores nothing**. The maintainer
reviews each report and adds the phone to the website's table. The website itself isn't part of this
repository.

The app allows one report every 48 hours, and the server enforces the same limit per IP address
(it keeps only a keyed hash of the IP in memory, never the IP itself).

The URLs are set in `REPORT_SERVER_URL` and `WEBSITE_URL` (`app/build.gradle.kts`). If you fork the
project, deploy your own server and point those settings to it.

#### What a report contains

Phone manufacturer and model, Android version, the detected audio capabilities (with and without
a DAC, and the DAC's name if one is connected), the names of compatible installed apps, and the
technical details shown in the app. Only the first ones are published; the technical details are
only seen by the maintainer. **Reports are anonymous:** they don't include names, email addresses,
accounts or any other data that identifies the sender.

### Project structure

```
app/src/main/java/com/bitperfect/detector/
  MainActivity.kt                  Lifecycle, listens for DACs being connected/disconnected
  audio/PolicyConfigScanner.kt     Reads the manufacturer's XML (no DAC)
  audio/AudioRuntimeProbe.kt       Android 14 APIs (with a DAC)
  audio/BitPerfectAppScanner.kt    Installed music apps that use native bit-perfect
  audio/Verdicts.kt                Combines both sources into the on-screen results
  report/ReportSender.kt           Sends the anonymous report to the report server
  ui/MainScreen.kt                 Compose UI and report text
```


## License

Copyright (C) 2026 Bit-Perfect Audio Detector

This program is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the License,
or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
[GNU General Public License](LICENSE) for more details.
