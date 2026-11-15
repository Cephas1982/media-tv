# Media TV

A small Android TV app for the living room. It is a frame around three pages the NUC
already serves, so the TV never holds logic or state of its own.

| Surface | What it is | URL |
| --- | --- | --- |
| **Watch** | Jellyfin's own web client | `http://192.168.1.121:8096/web/` |
| **Ask** | Media Desk: requests, questions, grabs | `http://192.168.1.121:3012/` |
| **Recent** | what arrived or is arriving, with dates | `http://192.168.1.121:3012/recent` |

**Talk** asks Android's system speech recognizer for one utterance and types the result
into the focused field on the page. Apps cannot read the TV remote's microphone directly;
the recognizer can, and it hands back text.

## Build

No toolchain on your machines: run the **Build APK** workflow and download the artifact.

## Install

```sh
adb connect <tv-ip>:5555
adb install -r app-debug.apk
adb shell am start -n app.mediatv/.MainActivity
```

Page console output goes to logcat: `adb logcat -s MediaTV`.

## Deliberate limits

- Framework-only Android, no support libraries: the APK stays tiny and cannot rot.
- The pages are the product. A change to Media Desk or Jellyfin reaches the TV on a reload.
- Cleartext HTTP is enabled because these are LAN addresses; there is no user data on the wire.
