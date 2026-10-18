# 鼻歌 hanauta — Hum

Hum or sing a tune into the phone and it is written down as notes: a piano roll to fix them
on, and a MIDI file or sheet music to send to a computer. Built for the
[Mudita Kompakt](https://mudita.com/products/kompakt/), and it will install on any Android 12
device.

*Hanauta* is 鼻歌 — humming a tune to yourself.

Written in Kotlin and Jetpack Compose, using Mudita's own [MMD](https://github.com/mudita/MMD)
design system so it looks like the apps the phone already ships with. The pitch detection is
from Michael Moessner's [Tuner](https://codeberg.org/thetwom/Tuner).

## Recording

- **Press Record, hum or sing, press Stop.** A take can be up to ten minutes.
- **With the click on**, a bar of clicks counts you in and a click marks every beat, at the
  tempo you set (40 to 200 a minute) and with two, three or four beats to the bar. The notes
  then land on the beats you sang them to. Headphones keep the click out of the recording.
- **With the click off**, sing in your own time; the tempo is worked out from the notes
  afterwards, and can be set by hand if it is wrong.
- **Read a recording** makes a take from a sound file already on the phone, a voice memo
  for one.
- Recording carries on with the screen off. Back while recording stops the take and keeps it.

## From voice to notes

Each hundredth of a second the pitch is read from the sound. A note is a stretch where the
voice stays on one pitch, and its pitch is the middle of that stretch, so a scoop into a note,
vibrato on it and a slide out of it all count as the one note. The same note sung twice is
two notes where the voice dips between them. Then:

- **Tuning**: a voice that sits a little sharp or flat throughout is taken as it is meant.
- **Key**: guessed from the notes, and can be changed. A note sung between two semitones is
  put on the one in the key; one sung squarely on a semitone outside the key keeps it. **Any
  note** puts every note on the nearest semitone.
- **Grid**: starts and ends go to the nearest quarter, eighth or sixteenth note, or
  eighth-note triplet.

Changing the key, grid or tempo places the notes again from the voice.

## Fixing

The roll shows two bars at a time, a row to a semitone; a swipe or the arrows turn the page.
Behind the notes, a dotted line shows the pitch the voice actually took. Press a note to choose
it, then **Up** and **Down** move it a semitone, **Shorter** and **Longer** change it by a step
of the grid, **Join next** makes it one with the note after it, **Split** cuts it in two, and
**Delete** asks for a second press. **Undo** steps back through every change.

**Play** plays the notes from the page on screen, with the recording quieter underneath if
that is switched on in Settings.

## Sending

**Send** (the share button) gives three files, named after the take:

- **MIDI file**: one track of notes with the tempo, metre and key. In LMMS it is
  *Project → Import*; Ardour, Reaper, Bitwig and every other DAW open it the same way.
- **Sheet music**: MusicXML, for MuseScore or any other notation program.
- **The recording**, as WAV.

They go through the phone's share sheet, so they can be sent to Files and on to a computer,
or to anything else that takes a file.

## What it does not do

- **One line of melody.** Humming is one voice; chords are not read.
- **Nothing leaves the phone** unless you send it. The app has no internet permission. See
  [PRIVACY.md](PRIVACY.md).

## Building

```
./gradlew assembleDebug
```

A release build needs a keystore at `signing/signing.keystore` with a matching
`signing/signing.properties`. There is no fallback key in this repository: without one, a
release build comes out unsigned rather than wrongly signed.

`./gradlew testDebugUnitTest` runs the tests: a made-up voice humming a known tune (scoops,
vibrato, a voice a fifth of a semitone sharp, the same note sung twice) transcribed with and
without the click, the key and grid snapping, every edit, and the MIDI and MusicXML files read
back the way a sequencer and a notation program read them.

`tools/make-test-hum.py` writes that same tune as a WAV file, for trying the app without
singing.

## Licence

GPL-3.0-only. See [LICENSE](LICENSE).

Copyright (C) 2026 wander wildwood, and (C) 2020–2024 Michael Moessner for the pitch detection
in `app/src/main/kotlin/com/wanderwildwood/hanauta/pitch/`, from
[Tuner](https://codeberg.org/thetwom/Tuner) (GPL-3.0-or-later), by way of this shop's own Tuner (onsa).

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, version 3.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If
not, see <https://www.gnu.org/licenses/>.

Icons are from Material Symbols, Apache 2.0.
