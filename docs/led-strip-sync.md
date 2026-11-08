# LED strip sync

The TV's home app drives a Magic Home Wi-Fi LED strip behind the TV. An old phone (`lightcam`)
stands near the TV, films the screen, and listens to the room. The TV can't see its own picture
(DRM) or hear its own sound (no loopback for apps), so the phone provides both.

## Modes

Pick a mode from the light icon in the home screen's top bar. It's also under `lights=` in
`home.conf` and in Options. Modes stay active everywhere: in apps, on the home screen and in the
screensaver.

| Mode | Strip shows |
|---|---|
| static | your own colour, as set in the Magic Home app; the strip is left alone |
| sound | the colours of what's playing, pulsing to the sound (strobe or gradual) |
| video | the picture's colour, from the phone's camera |
| both | the picture's colour, pulsing to the sound |

With no phone, the strip uses colours from the title's TMDB backdrop (`Palette.java`). They flow
slowly from one to the next and dim while paused. If no packet comes from the phone for 1 s, the
strip falls back to these colours too. When the TV goes off, or you pick static, your own colour comes back. The switch
turns the strip off.

`Lights.java` is a notification listener only because that's how an app can see other apps'
media sessions on Fire OS 7. It reads no notifications. `deploy.sh` enables it with
`cmd notification allow_listener`.

## Magic Home controller (`Strip.java`)

Local control over **TCP 5577**, no cloud. The UDP discovery broadcast (port 48899) gets no reply
through a Wi-Fi extender, so the TV finds the strip by trying every address on its subnet for
port 5577, and remembers the address that answers.

| Bytes (a checksum byte follows: the sum of the bytes, & 0xFF) | Meaning |
|---|---|
| `41 r g b w f0 0f` | temporary colour: applied at once, not saved (the mode reads 0x62). About 23 per second is fine. |
| `31 r g b w f0 0f` | colour, saved (the mode reads 0x61) |
| `71 23 0f` / `71 24 0f` | power on / off |
| `81 8a 8b` | status query. The reply is a 0x81 frame: power at byte 2 (0x23 on, 0x24 off), RGBW at bytes 6–9. Replies can arrive wrapped in `b0 b1 b2 b3` frames, so pick out the 0x81 frame by its checksum. |

Colours are sent as temporary, so the colour you set in the Magic Home app is still there to
restore afterwards.

Controller quirks (the root of every "my colour got overwritten" bug):

1. **It fades to a new colour over about 1 s.** If it's switched off or read mid-fade, it keeps or
   reports the halfway colour.
2. **It ignores commands that arrive straight after a burst of colours.**
3. **It drops the last command if the TCP connection is reset**, and closing the socket with replies
   still unread causes a reset.

So restoring goes: pause 400 ms, send your colour, wait 1.5 s, and only then switch off. Unread
replies are drained before the socket closes. Your colour is kept in prefs (`owner`). A new colour
on the strip is only taken as yours if the app hasn't touched the strip for 60 s, the colour isn't
one of ours, and two reads 1.2 s apart agree.

If the strip drops off the Wi-Fi, the dead connection swallows colours without any error. So
`Strip.healthy()` sends a status query every 5 s and reconnects if no reply comes within 3 s.

## Why `StripModel` exists

The controller turns all its LEDs on together at the start of each PWM cycle, and the power supply
sags when they're lit together. Full white comes out at only about 39% red, 28% green and 15% blue
of what each LED gives alone. Cyan turns green-teal and magenta turns pinkish red. `StripModel.java`
interpolates how much light each LED gives per unit of power, depending on which LEDs are lit and
the total power. It then works backwards to the power needed for a target colour (about 24 µs per
colour). The model comes from the camera's strip matching below. A bigger power supply would make
mixed colours brighter.

## The phone (`lightcam/`)

Any spare Android phone works. Ours is a CMF Phone 2 Pro powered from the TV's USB port. It sends:

```
lightcam r g b beat level groove        UDP 5578, phone -> TV
```

- `r g b`: the picture's colour at full brightness
- `beat`: 100 bass, 75 mids, 55 highs, 0 between, -1 with no sound
- `level`: 0–100, how bright the picture is
- `groove`: 0–100 or -1, for the gradual mode

Commands from the TV to the phone go to **UDP 5579**:

| Command | Answer (UDP 5578) | What it does |
|---|---|---|
| `calib white`, `calib black`, `calib done` | `lightcam calibrated` | finds the screen: whatever changed between white and black |
| `cpatch i rrggbb` … `colour done` | `lightcam colour` | matches the camera's colours to the TV's |
| `spatch i rrggbb` … `strip done` | `lightcam strip …` | matches the strip's own colours, filmed on the wall |
| `scal start`, `scal done` | `lightcam scal` | sound calibration |
| `snap` | `lightcam title <name \| S1 E3 \| episode>` or `NONE` | full-resolution photo of the screen, read by Gemini |
| `here` (every 5 s while colours arrive) | | tells the phone the TV's current address; when it stops, the phone sends `lightcam hello` to its /24 |
| `rec start`, `rec done` | | records band levels to `files/music.csv` for tuning |

### Lining it up

Open it from the light menu (the viewfinder icon) or with
`adb shell am start -n tv.dumbtv.home/.Calibrate`. Dismiss the screensaver first, or the test
patterns play hidden behind it. It takes about 65 s:

1. White, then black. Whatever changed between the two is the screen, so any angle works. The
   outline is saved to `files/mask.bin`. Exposure is set so white reads well below clipping.
2. Patches of pure colour (black, white, R, G, B, C, M, Y). The camera uses a straight contrast
   curve, because the phone's ISP already gamma-encodes. `colour_m` is the inverse of the measured
   primaries.
3. 17 strip colours, 2.2 s each because of the fade. The phone films the wall around the TV and sends back the data for `StripModel`.

The outline stays where lining up put it. If the phone gets nudged, line it up again from the light
menu. (An automatic follow was tried and removed: black bars on letterboxed video look just like
the TV's dark frame, so it kept drifting onto the picture.) Each pixel is weighted by how much of
the screen it covers, so pixels on the far side count about 5× more than those on the near side.

### Sound

The microphone runs unprocessed at 32 kHz.

- **Strobe:** three bands split by cascaded biquads (`Biquad.java`): bass below 250 Hz, mids
  300–2000 Hz, highs above 5 kHz. Flashes are bass 100%, mids 75%, highs 55%. Mids and highs
  only count within 1.5 s of a bass hit.
- **Gradual:** `Groove.java` measures the spectral flux across 8 octave bands (60 Hz – 8 kHz). That's
  each band's rise in dB over the last 60 ms, blended over a loudness base. The TV squares it,
  because that's closer to how the eye sees brightness.
- **Calibration:** use the light menu's speaker icon, or `am start -n tv.dumbtv.home/.SoundCalibrate`.
  The TV plays an 11 s rhythm: 2 s of silence, then four bars of kick, snare and hi-hat. The phone
  keeps the hit thresholds that catch it best and saves them in `lightcam.conf`.

### Staying up on its own

- **The TV's USB power drops for minutes at a time now and then.** The camera keeps running for
  3 min before sleeping. When power returns, `Power.java` wakes the screen and opens the app over
  the lock screen. A 2-minute watchdog alarm also reopens it whenever it isn't in front, and a
  crash handler restarts it 3 s after a crash.
- **If no frame arrives for 5 s, the camera is reopened.**
- **The router hands out new addresses now and then.** If the phone hears no `here` from the TV for
  15 s, it sends `lightcam hello` to every address on its /24, and saves the address the TV answers from.

### Troubleshooting

- **The stock camera app has taken the camera** (the log shows `camera error 2` every 10 s). Run
  `adb shell am force-stop <camera package>`.
- **The camera floods the phone's log buffer.** Read the app's log with
  `logcat --pid=$(pidof tv.dumbtv.lightcam)`, and run `logcat -G 8M` first.
- **`adb connect` says "No route to host" while the port is open.** The adb server is stale: run
  `adb kill-server`.
- **The Wireless debugging port changed.** It changes every time debugging restarts. Find the new
  one with `adb mdns services`, then update `PHONE_SERIAL` in `deploy.conf`.
