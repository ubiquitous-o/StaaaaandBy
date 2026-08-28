# StaaaaandBy

An Android app inspired by the iPhone's StandBy mode. While charging on a Qi pad, it shows a clock and the artwork of the currently playing track on top of the lock screen.

![Demo](docs/demo.gif)

## How it works

- **ChargingWatchService** (a persistent foreground service) watches for "power connected" and "screen off" events. When the phone is wirelessly (Qi) charging and the screen is off, it launches **StandbyActivity** on top of the lock screen (`showWhenLocked` + `turnScreenOn`). The "Display over other apps" permission (SYSTEM_ALERT_WINDOW) grants the background activity launch
- Wired (AC/USB) charging does not trigger it. The screen closes automatically when charging stops
- Now-playing info comes from **MediaSessionManager** + a notification listener. Works with any music app that uses MediaSession, not just Spotify
- UI is built with Jetpack Compose. Landscape only (`sensorLandscape` auto-detects which way is up), full screen with all system bars hidden

## UI

- The artwork fills the full screen height, and **the vertical column at the current playback position is stretched horizontally, slit-scan style**, to fill the remaining screen width. The slit tracks playback progress every frame (sub-pixel rendering)
- High-resolution art is fetched asynchronously from `ALBUM_ART_URI`, replacing the low-resolution bitmap embedded in the metadata
- Invisible tap zones split the screen in three: left = previous track / center = play–pause / right = next track
- Clock, date, and battery are overlaid on the artwork with drop shadows. The typeface is **Fira Code** (variable font, Bold for the clock / Medium for labels), and the date is shown in English (`THU, AUG 28`)
- Track title and artist appear in the bottom left. Long strings marquee-scroll within their area (clipped with padding so the shadows don't get cut off)
- On launch, the first portrait frame is masked in black, then the UI fades in over 700 ms once the screen is landscape
- The setup screen (MainActivity) uses string resources and supports Japanese and English

## Install

1. Download the latest APK from [Releases](https://github.com/ubiquitous-o/StaaaaandBy/releases)
2. Open the APK on your phone. You may need to allow "Install unknown apps" for your browser or file manager, and Play Protect may show a warning for apps from unknown developers — that's expected for sideloaded apps
3. Follow the two setup steps shown in the app (below)

Only tested on a Galaxy Z Flip 7 (One UI). Other OEMs (especially Xiaomi/OPPO) restrict background activity launches more aggressively and may need extra battery/autostart settings — reports welcome.

## Setup (on the device)

1. Grant notification access (needed for the music display; skip it if you only want the clock)
2. Grant "Display over other apps" (required to launch the standby screen)
3. Place the phone on a Qi charging pad. Pressing the side key to turn the screen off while charging also brings up the standby screen

Note: if the persistent service gets killed on Samsung devices, set Settings → Apps → StaaaaandBy → Battery → Unrestricted.

## Privacy

Everything stays on your device. Notification access is used solely to read the media sessions of music apps (title, artist, artwork, playback state) — notifications themselves are never read or stored. The INTERNET permission is used only to fetch album artwork. Nothing is collected or sent anywhere.

## Build

- Requires JDK 17 and the Android SDK (compileSdk 35). `org.gradle.java.home` in `gradle.properties` points to Homebrew's `openjdk@17`
- `./gradlew :app:assembleDebug`

## License

- Code: [MIT](LICENSE)
- The bundled [Fira Code](https://github.com/tonsky/FiraCode) font is licensed under the SIL Open Font License 1.1 — see [licenses/FiraCode-OFL.txt](licenses/FiraCode-OFL.txt)

## Structure

```
app/src/main/java/com/kazuto/standby/
├── MainActivity.kt                     # Setup screen (notification access / overlay permission)
├── StandbyActivity.kt                  # The standby screen shown over the lock screen
├── service/ChargingWatchService.kt     # Persistent charging/screen watcher → launches StandbyActivity
├── service/BootReceiver.kt             # Restarts the service after reboot
├── media/NowPlayingListenerService.kt  # Notification listener required for MediaSession access (empty)
├── media/MediaSessionWatcher.kt        # Publishes now-playing info and position via StateFlow
└── ui/StandbyScreen.kt                 # Compose UI: slit-scan artwork + clock overlay
```
