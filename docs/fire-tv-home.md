# Fire TV home

The TV app is `tv.dumbtv.home`, built from `tv-home/launcher/` with the plain Android SDK (`build.sh`,
no Gradle). It targets Fire OS 6–8. This page covers what was added on top of dumbtv's launcher,
and the Fire OS quirks we ran into along the way.

## Pages

HOME always lands on the apps. From there, Down moves through the pages, and Up or Back goes back the way you came:

1. **Apps.** A dock of white app logos, each with a glow colour and rotating quotes (`dock.txt`), over
   a slowly drifting wallpaper, with the time and weather. The top bar has a light icon that opens
   the light menu. *More apps* (`Library.java`) lists every app and HDMI input.
2. **Continue watching.** Whatever played lately on any app, newest first (`Watching.java`,
   `Continue.java`). OK resumes the title, and Menu removes a card.
3. **Suggestions.** Characters cut out of a still from each title, standing over the wallpaper
   (`Favourites.java`). There are 8 per visit, dealt from a pool that the Mac rebuilds every day.
4. **Moods.** A deck of mood cards (`Moods.java`). OK spins a card's posters like a slot machine and
   opens the title it lands on.

## Theme files

The app reads its look from `/sdcard/Android/data/tv.dumbtv.home/files/cinema/`. `deploy.sh assets`
pushes these from `tv-assets/tv/`, so the look can change without a rebuild:

| File | Format |
|---|---|
| `dock.txt` | `package \| name \| glow colour \| quote ~ source \|\| quote ~ source …` |
| `favourites.txt` | the fixed character lineup: `file \| title \| package \| link \| row \| x \| bottom \| height \| colour` |
| `weather.txt` | `latitude longitude` (Open-Meteo, no key needed; without this file there's no weather and nothing goes online) |
| `wallpapers/`, `logos/`, `icons/`, `fonts/`, `characters/`, `scenes/` | images and fonts |
| `tmdb.key`, `youtube.key` | pushed from `tv-assets/` so the TV can look titles up itself |

The daily refresh (`tv-assets/tools/refresh_pool.py`) writes and pushes `pool/`. That's
`pool.txt` (Suggestions), `moods.txt`, `lights.txt` (media-session names matched to TMDB titles,
for the strip), `quotes.txt` for each wallpaper folder, posters, cut-outs and the day's
wallpapers. It runs from launchd every 30 minutes, does the day's refresh at the first check after
6 pm, and keeps retrying the push until the TV is on. Every other check exits straight away.

Ranking: about 80% of Suggestions come from your taste profile (clusters, TMDB recommendations
for titles you loved or liked, and a boost for your watchlist). The other 20% are the newest
well-rated releases on the apps you have installed. Titles shown in the last 4 days are held back.

## Keeping HOME on our app

Fire OS binds HOME to Amazon's launcher and handles the key before any accessibility filter sees it.
So `Guard.java` (an accessibility service) waits for Amazon's home window and covers it, then
opens ours through an alarm-fired PendingIntent. That gets around Android's 5-second app-switch
delay. `Watchdog.java` puts the guard back whenever Fire OS drops it.

Fire OS quirks, learned the hard way:

- **Force-stopping the app or running `adb install -r` unbinds the guard.** `deploy.sh` force-stops
  first, then re-arms the guard, and waits for the guard to log `connected`.
- **Force-stopping right after an install can jam the accessibility manager.** After that it
  ignores every change to `enabled_accessibility_services`, even a reinstall. Only `adb reboot`
  brings the guard back. `deploy.sh` warns when this happens.
- **Uninstalling any other package** makes Fire OS rewrite the accessibility settings and drop the
  guard. Run `deploy.sh` again afterwards.
- **Turning VoiceView on or off wipes all accessibility services.** The watchdog notices and re-adds
  the guard within 3 seconds. It needs `WRITE_SECURE_SETTINGS`, which `deploy.sh` grants.
- **The guard can show as bound in `dumpsys accessibility` without running.** The watchdog checks
  `Guard.connected()` every 30 s and toggles the guard if it isn't running.
- **Amazon's home is still reachable** through the Amazon-arrow button at the top left.

## What the TV can see of what's playing

Continue watching and the light strip both need to know what's playing. Each app reveals a different amount:

| App | Title source |
|---|---|
| YouTube | the media session (video title). Its ID and thumbnail come from the YouTube Data API. |
| JioHotstar | the episode from the media session. The series name comes from its player screen (read by the guard) while the controls are up. |
| Prime Video | its player says "playing <title>" (read by the guard). For a series, the phone's camera supplies the episode. |
| Netflix, Apple TV | nothing at all. The phone films the screen when playback starts and when it's paused, and Gemini reads the title. Until then, the title opened from our home screen is used. |

Other limits: the TV can't capture its own picture (DRM: `screencap` gives an empty file) or its
own sound (outputs are offloaded or direct, with no loopback for apps). That's why the strip needs
a phone. `uiautomator dump` fails on the home screen while the wallpaper animates.

## Also replaced

- **Screensaver.** `Dream.java` shows the wallpapers, the time, the weather and a line from the film.
  It's set with `settings put secure screensaver_components tv.dumbtv.home/.Dream`. Amazon's
  original value is saved in `report/screensaver-before.txt`.
- **Alexa search.** When Alexa shows Amazon's results, the guard reads the query and opens
  `Search.java`, which only lists titles available on your installed apps. Back shows Amazon's
  own results.

## Undo

`tv-home/scripts/restore.sh` puts the TV back the way it was, using the snapshot in `report/`
(which stays on the Mac). The screensaver setting has to be restored by hand.
