#!/usr/bin/env python3
"""Write the tests' made-up hum as a WAV file, for trying the app without singing.

The same tune, voice and timing as app/src/test/.../Hum.kt: a scoop up into each note,
vibrato, a few harmonics, a little breath noise, the whole voice a fifth of a semitone sharp.

    tools/make-test-hum.py out.wav [tempo] [lead-seconds]
"""
import math
import random
import struct
import sys
import wave

TUNE = [
    (60, 1.0), (62, 0.5), (64, 0.5), (65, 1.0), (67, 1.0),
    (67, 0.5), (67, 0.5), (69, 1.0), (0, 1.0),
    (67, 0.5), (65, 0.5), (64, 1.0), (62, 1.0), (60, 2.0),
]


def render(rate=44100, tempo=100.0, lead=0.4, detune=0.2, seed=1):
    beat = 60.0 / tempo
    total = lead + sum(b for _, b in TUNE) * beat + 0.5
    out = [0.0] * int(total * rate)
    rnd = random.Random(seed)
    t0 = lead
    phase = 0.0
    for pitch, beats in TUNE:
        length = beats * beat
        if pitch:
            a = int(t0 * rate)
            b = int((t0 + length - 0.04) * rate)
            for i in range(a, b):
                t = (i - a) / rate
                scoop = -1.0 * (1 - t / 0.05) if t < 0.05 else 0.0
                vibrato = 0.3 * math.sin(2 * math.pi * 5.5 * t) if t > 0.15 else 0.0
                m = pitch + detune + scoop + vibrato
                f = 440.0 * 2 ** ((m - 69) / 12)
                phase += 2 * math.pi * f / rate
                env = min(1.0, t / 0.02, (b - i) / rate / 0.03)
                v = math.sin(phase) + 0.5 * math.sin(2 * phase) + 0.3 * math.sin(3 * phase) + 0.1 * math.sin(4 * phase)
                out[i] = 0.25 * env * v
        t0 += length
    return [s + (rnd.random() - 0.5) * 0.01 for s in out]


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "hum.wav"
    tempo = float(sys.argv[2]) if len(sys.argv) > 2 else 100.0
    lead = float(sys.argv[3]) if len(sys.argv) > 3 else 0.4
    rate = 44100
    samples = render(rate, tempo, lead)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))
    print(f"{path}: {len(samples) / rate:.2f} s at {tempo:g} a minute, tune starts at {lead:g} s")


if __name__ == "__main__":
    main()
