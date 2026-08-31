# StaaaaandBy

An Android app inspired by the iPhone's StandBy mode. While charging on a Qi pad, it shows a clock and the artwork of the currently playing track on top of the lock screen.

![Demo](docs/demo.gif)

## How it works

- **ChargingWatchService** (a persistent foreground service) watches for "power connected" and "screen off" events. When the phone is wirelessly (Qi) charging and the screen is off, it launches **StandbyActivity** on top of the lock screen (`showWhenLocked` + `turnScreenOn`). The "Display over other apps" permission (SYSTEM_ALERT_WINDOW) grants the background activity launch
- By default, wired (AC/USB) charging does not trigger it, and it only launches when the phone is physically lying sideways (an accelerometer watch keeps checking while charging, so rotating it to landscape later also works). Both can be changed in the app's Preferences: **Also start on wired charging** and **Also start in portrait**. The screen closes automatically when charging stops
- Now-playing info comes from **MediaSessionManager** + a notification listener. Works with any music app that uses MediaSession, not just Spotify
- UI is built with Jetpack Compose. Landscape by default (sensor auto-detects which way is up), full screen with all system bars hidden. With portrait enabled, the activity follows the full sensor and switches to a portrait layout

## UI

- The artwork fills the full screen height, and **the vertical column at the current playback position is stretched horizontally, slit-scan style**, to fill the remaining screen width. The slit tracks playback progress every frame (sub-pixel rendering)
- In portrait (opt-in), the same thing happens vertically: the artwork fills the width and the row at the playback position is stretched downward, so the slit travels from top to bottom as the song plays
- High-resolution art is fetched asynchronously from `ALBUM_ART_URI`, replacing the low-resolution bitmap embedded in the metadata
- Invisible tap zones split the screen in three: left = previous track / center = play–pause / right = next track
- Clock, date, and battery are overlaid on the artwork with drop shadows. The typeface is **Fira Code** (variable font, Bold for the clock / Medium for labels), and the date is shown in English (`THU, AUG 28`)
- Track title and artist appear in the bottom left. Long strings marquee-scroll within their area (clipped with padding so the shadows don't get cut off)
- On launch, the first portrait frame is masked in black, then the UI fades in over 700 ms once the screen is landscape
- The setup screen (MainActivity) uses string resources and supports Japanese and English. It also lists installed music apps and lets you exempt them from battery optimization with one tap (see Setup)

## Install

1. Download the latest APK from [Releases](https://github.com/ubiquitous-o/StaaaaandBy/releases)
2. Open the APK on your phone. You may need to allow "Install unknown apps" for your browser or file manager, and Play Protect may show a warning for apps from unknown developers — that's expected for sideloaded apps
3. Follow the three setup steps shown in the app (below)

Only tested on a Galaxy Z Flip 7 (One UI). Other OEMs (especially Xiaomi/OPPO) restrict background activity launches more aggressively and may need extra battery/autostart settings — reports welcome.

## Setup (on the device)

1. Grant notification access (needed for the music display; skip it if you only want the clock)
2. Grant "Display over other apps" (required to launch the standby screen)
3. Keep your music apps awake — see below
4. Place the phone on a Qi charging pad, lying sideways. Pressing the side key to turn the screen off while charging also brings up the standby screen

Preferences (bottom of the setup screen): **Also start on wired charging** (USB too, not just Qi) and **Also start in portrait** (any orientation, with a portrait layout).

Note: if the persistent service gets killed on Samsung devices, set Settings → Apps → StaaaaandBy → Battery → Unrestricted.

### Keeping music apps awake (remote playback)

If you play music on another device — e.g. Spotify on your Mac with Spotify Connect — the phone's Spotify app mirrors that playback into a MediaSession, and that's what StaaaaandBy displays. The catch: while mirroring, the phone's Spotify app isn't playing audio locally, so Android treats it as an idle background app and may kill it to reclaim memory. When that happens the mirror is gone and the artwork stops updating even though the music keeps playing on the other device.

The third setup step lists the supported music apps installed on the phone, shows whether each one is exempt from battery optimization, and opens its app settings with one tap. For each app, choose **Battery → Unrestricted**. This applies to any music app, not just Spotify. It makes the kill much less likely, though a true low-memory situation can still take the app down — reopening the music app restores the display.

On top of that, StaaaaandBy actively keeps the music app alive while the standby screen is showing: it holds a `bindService` connection to the app's public `MediaBrowserService` (the same entry point Android Auto uses). A process bound by a foreground app isn't treated as a background app, so Samsung's nightly "auto optimization" leaves it alone, and if it does die, the bind restarts it right away.

There is a second failure mode: the phone's Spotify stays alive but its Connect mirror silently desyncs — it keeps reporting the last state it saw (often "paused") while the Mac plays on. A watchdog logs the session state every 15 s and flags a session that looks stale (still "playing" but the extrapolated position ran past the end of the track, or "paused" for over a minute on a session that was previously mirroring another device — detected via `AudioManager.isMusicActive()`: playing, but no audio on this phone). Everything is logged under the `StaaaaandBy` logcat tag.

Measured on a real device: **when remote playback stays paused for 10 minutes, Spotify tears the mirror down** and immediately re-publishes a session containing the phone's own stale local state — a different track, paused at some old position. Worse, sending `play` to a desynced mirror doesn't resume the other device: it **transfers Connect playback to the phone** (the other device goes silent and the phone starts playing). Since v1.0.9 StaaaaandBy defends against both: it remembers the last track that was mirrored, treats a reappearing session with a different track as a fake, shows **the clock only** while that suspicion holds (so you can see at a glance that the music info is gone), and ignores the tap controls so a tap can never hijack playback. As soon as playback actually resumes — on the other device or deliberately on the phone — the display recovers by itself.

**If the display does get stuck anyway, open Spotify on the phone once and go back home** — Spotify only refreshes its Connect state when its own UI comes to the foreground. StaaaaandBy deliberately does not automate this: launching Spotify's UI while the phone is locked leaves it in a half-started state, after which Spotify opens a local audio stream every time playback resumes on the other device, and multipoint Bluetooth earbuds jump to the phone. The code for that automatic resync is still in the repo (`AUTO_RESYNC_ENABLED`), disabled for that reason.

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
├── MainActivity.kt                     # Setup screen (permissions / music app battery / preferences)
├── Prefs.kt                            # User preferences (wired trigger, portrait)
├── StandbyActivity.kt                  # The standby screen shown over the lock screen
├── service/ChargingWatchService.kt     # Persistent charging/screen watcher → launches StandbyActivity
├── service/BootReceiver.kt             # Restarts the service after reboot
├── media/NowPlayingListenerService.kt  # Notification listener required for MediaSession access (empty)
├── media/MediaSessionWatcher.kt        # Publishes now-playing info and position via StateFlow; stale-session watchdog
├── media/MusicAppKeepAlive.kt          # Keeps the music app process alive while standby is showing (bindService)
└── ui/StandbyScreen.kt                 # Compose UI: slit-scan artwork (landscape/portrait) + clock overlay
```
