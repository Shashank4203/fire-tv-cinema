# tv-assets: the Mac side

Everything the Mac keeps for the TV. `tv-home/deploy.sh` expects this folder at `../tv-assets`.
If you schedule the daily refresh with launchd, keep it outside `~/Documents` (macOS blocks
background jobs there) and put a symlink next to `tv-home`.

## In this repo

| Path | What it is |
|---|---|
| `tools/refresh_pool.py` | The daily refresh. Builds Suggestions (the newest well-rated titles on your installed apps, ranked by taste), mood cards, light-strip title matches, the day's wallpapers and quotes, then pushes them to the TV with adb. `--no-push` refreshes only. `--auto` is for launchd: it refreshes once a day after 6 pm and keeps retrying the push until the TV is on. |
| `tools/find_tv.py` | Finds the TV again when the router gives it a new address. It checks the saved address against the TV's serial number, and otherwise sweeps the /24 for adb's port. |
| `tools/taste.py` | Scores TMDB titles against `taste.json`. |
| `tools/cutout.swift` | Cuts the subject out of a still with macOS Vision. Build it with `swiftc -O tools/cutout.swift -o tools/cutout`. |
| `tools/prep_characters.py`, `tools/preview_lineup.py` | Prepare the fixed character lineup and preview it. |
| `tools/com.shashank.tv-favourites.plist` | The launchd job (every 30 min). Change `YOUR_USER` and the Python path, copy it to `~/Library/LaunchAgents/`, then `launchctl load` it. |
| `quotes-extra.txt` | Extra home-screen quotes, `quote ~ source`, mixed with AFI's 100 and Wikiquote. |
| `tv/dock.txt`, `tv/favourites.txt` | The dock and the fixed character lineup (formats are in [docs/fire-tv-home.md](../docs/fire-tv-home.md)). |
| `tv/weather.txt.example`, `taste.example.json` | Copy each without `.example` and fill it in. |

## Not in this repo (create or collect them yourself)

| Path | What it is |
|---|---|
| `tmdb.key`, `youtube.key`, `gemini.key` | API keys, one per file |
| `taste.json` | your taste profile (optional: without it Suggestions are just the newest well-rated titles) |
| `tv/weather.txt` | `latitude longitude` for the weather |
| `tv-serial.txt`, `tv-id.json` | the TV's last address and serial number, written by `deploy.sh` and `find_tv.py` |
| `tv/wallpapers/`, `tv/logos/`, `tv/icons/`, `tv/fonts/`, `tv/characters/`, `tv/scenes/` | images and fonts, pushed by `deploy.sh assets` |
| `tv/pool/`, `cache/` | written by the daily refresh |
