# Loukai for Android

A karaoke player for Android and Android Auto, built to be sideloaded. It plays
the same files as [Loukai](https://github.com/monteslu/loukai):

- **Loukai stem files** (`.stem.mp4` / `.m4a`): drums/bass/other/vocals mixed live,
  with a vocals on/off toggle. Lyrics come from the `kara` atom (line timing only,
  same as Loukai). `backup:PA` lines bring the vocals in even when they're off.
- **MP3 + CDG pairs** (same base name, same folder): CDG graphics on screen.

No editing, no song creation, no Butterchurn.

## Install

Download the APK from the [latest release](../../releases/latest) (or the
artifact of any Actions run) and install it on your phone, allowing installs
from that source when Android asks.

## Build

Needs JDK 17+ and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in
`local.properties`). Android Studio ships both.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### CI and signing

`.github/workflows/build.yml` builds a release APK on every push and attaches
it to a GitHub release for `v*` tags. Android only installs an update signed
with the same key as the installed copy, so CI signs with a key from these
repository secrets:

| Secret | Value |
|---|---|
| `LOUKAI_KEYSTORE_BASE64` | the `.jks` keystore, base64-encoded |
| `LOUKAI_KEYSTORE_PASSWORD` | keystore password |
| `LOUKAI_KEY_ALIAS` | key alias |
| `LOUKAI_KEY_PASSWORD` | key password |

Without them (forks, pull requests) the APK is signed with a throwaway debug key.

First launch asks for your karaoke folder (any folder, scanned recursively).
**Rescan** picks up new files.

## Android Auto

The app shows up twice in the car:

- **Loukai** (media app): browse A–Z or search by voice, steering-wheel controls,
  and a Vocals button on the now-playing screen.
- **Loukai Lyrics**: the karaoke screen (lyrics or CDG graphics) with
  play/pause, next, and vocals buttons. It opens with a passengers-only
  notice that has to be acknowledged each time. It registers as a navigation app because
  that's the only kind of car app that can draw its own screen; while it's open
  it takes the navigation slot from Maps.

Sideloaded apps are hidden in Android Auto until you allow them, once:

1. Phone: Settings → Connected devices → Android Auto (or open the Android Auto app).
2. Scroll to **Version** and tap it about 10 times to unlock developer settings.
3. ⋮ menu → **Developer settings** → turn on **Unknown sources**.
4. Reconnect to the car. If Loukai Lyrics doesn't show up, open the car's app
   launcher → Customize launcher and enable it.

### Testing without the car (Desktop Head Unit)

With the phone on USB:

1. Android Auto ⋮ menu → **Start head unit server**.
2. `adb forward tcp:5277 tcp:5277`
3. `$ANDROID_HOME/extras/google/auto/desktop-head-unit` (install it with
   `sdkmanager "extras;google;auto"`)

## Debugging the mix

Debug builds can dump the mixed output for checking against the stems:

```sh
adb shell run-as com.loukai.player touch files/dump   # then play a song
adb exec-out run-as com.loukai.player cat "files/mix-<song>.pcm" > mix.pcm  # s16le stereo 44.1k, one file per song load
adb shell run-as com.loukai.player rm files/dump
```

## License

AGPL-3.0, same as Loukai. See `LICENSE` and `THIRD-PARTY-NOTICES.md`.
