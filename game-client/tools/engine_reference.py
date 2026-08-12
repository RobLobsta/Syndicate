#!/usr/bin/env python3
"""Measure real engine recordings and compare EngineSynth against them (D15-R38a4).

Two numbers carry most of an exhaust note's character, and both were badly wrong in this
synthesiser until somebody measured a real one (DISC-026):

  * **tilt** -- how fast the harmonics fall, in dB per octave between 100 Hz and 4 kHz.
  * **harmonic-to-floor** -- how far the harmonics stand above the noise between them, in dB.
    An engine with nothing between its harmonics reads as synthetic however correct its firing
    geometry is, and this is the number that says so.

Reference clips come from ESC-50 (https://github.com/karolpiczak/ESC-50), which is CC BY-NC.
They are **analysis inputs only**: this script downloads them outside the repository and nothing
derived from them is ever committed, so `assets/audio` stays free of third-party terms (D15-R39).

Usage
-----
    python3 game-client/tools/engine_reference.py --fetch            # download reference clips
    python3 game-client/tools/engine_reference.py --measure a.wav 26 # measure one file at f0=26 Hz

`--fetch` needs `raw.githubusercontent.com`; every other audio host tried was blocked by the
sandbox proxy, which is how ESC-50 came to be the source.

To measure the synthesiser, render a steady-state WAV from `EngineSynth` at a known rpm and pass
it with the firing frequency (`cylinders * rpm / 120`) as `f0`.
"""

from __future__ import annotations

import argparse
import csv
import pathlib
import sys
import urllib.request
import wave

import numpy as np

RAW = "https://raw.githubusercontent.com/karolpiczak/ESC-50/master"
CACHE = pathlib.Path(__file__).resolve().parent / ".reference-cache"

# Measured over the eight strongest ESC-50 engine clips. D15-R38a4 quotes these.
REAL_TILT_DB_PER_OCT = (-10.4, -4.4)
REAL_HARMONIC_TO_FLOOR_DB = (5.1, 16.6)


def load(path: pathlib.Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path)) as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
        if w.getnchannels() == 2:
            data = data.reshape(-1, 2).mean(axis=1)
        return data.astype(float) / 32768.0, w.getframerate()


def welch(x: np.ndarray, sr: int, nfft: int = 32768) -> tuple[np.ndarray, np.ndarray]:
    """Averaged power spectrum. Long window: the fundamentals here are 20-50 Hz."""
    if len(x) < nfft:
        x = np.pad(x, (0, nfft - len(x)))
    window = np.hanning(nfft)
    total, count = None, 0
    for start in range(0, len(x) - nfft + 1, nfft // 2):
        power = np.abs(np.fft.rfft(x[start : start + nfft] * window)) ** 2
        total = power if total is None else total + power
        count += 1
    return total / max(1, count), np.fft.rfftfreq(nfft, 1 / sr)


def estimate_f0(x: np.ndarray, sr: int, lo: float = 20.0, hi: float = 250.0) -> tuple[float, float]:
    """Harmonic product score over a plausible firing-frequency range."""
    power, freq = welch(x, sr)
    df = freq[1]
    background = np.median(power[int(lo / df) : int(2000 / df)])
    best = (0.0, 0.0)
    for f0 in np.arange(lo, hi, 0.25):
        bins = [int(round(f0 * k / df)) for k in range(1, 9)]
        bins = [b for b in bins if b < len(power)]
        if len(bins) < 5:
            continue
        score = float(np.mean([power[b] for b in bins]) / (background + 1e-12))
        if score > best[0]:
            best = (score, float(f0))
    return best


def measure(x: np.ndarray, sr: int, f0: float, lo: float = 100.0, hi: float = 4000.0):
    """Harmonic-only tilt and harmonic-to-floor ratio.

    Measured *at* harmonics rather than over the raw spectrum, because a field recording's
    broadband street noise otherwise flatters the tilt by several dB per octave.
    """
    power, freq = welch(x, sr)
    df = freq[1]
    width = max(2, int(round(0.35 * f0 / df)))
    peaks, floors, centres = [], [], []
    k = 1
    while f0 * k < 6000:
        centre = f0 * k
        i = int(round(centre / df))
        a, b = max(0, i - width), min(len(power), i + width + 1)
        if b - a < 3:
            break
        peaks.append(power[a:b].max())
        j = int(round((centre + f0 * 0.5) / df))
        c, d = max(0, j - width // 2), min(len(power), j + width // 2 + 1)
        floors.append(np.median(power[c:d]) if d > c else 1e-15)
        centres.append(centre)
        k += 1
    centres, peaks, floors = np.array(centres), np.array(peaks), np.array(floors)
    band = (centres >= lo) & (centres <= hi)
    if band.sum() < 4:
        return None, None
    tilt = float(np.polyfit(np.log2(centres[band]), 10 * np.log10(peaks[band]), 1)[0])
    ratio = float(10 * np.log10(peaks[band].sum() / max(1e-15, floors[band].sum())))
    return tilt, ratio


def fetch() -> list[pathlib.Path]:
    CACHE.mkdir(parents=True, exist_ok=True)
    meta = CACHE / "esc50.csv"
    if not meta.exists():
        urllib.request.urlretrieve(f"{RAW}/meta/esc50.csv", meta)
    names = [r["filename"] for r in csv.DictReader(meta.open()) if r["category"] == "engine"]
    out = []
    for name in names:
        target = CACHE / name
        if not target.exists():
            try:
                urllib.request.urlretrieve(f"{RAW}/audio/{name}", target)
            except OSError as exc:  # a single missing clip is not worth failing over
                print(f"  skipped {name}: {exc}", file=sys.stderr)
                continue
        out.append(target)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fetch", action="store_true", help="download and measure the reference clips")
    parser.add_argument("--measure", nargs=2, metavar=("WAV", "F0_HZ"), help="measure one file")
    args = parser.parse_args()

    if args.measure:
        path, f0 = pathlib.Path(args.measure[0]), float(args.measure[1])
        tilt, ratio = measure(*load(path), f0)
        print(f"{path.name}: tilt {tilt:+.1f} dB/oct   harmonic-to-floor {ratio:+.1f} dB")
        print(f"  real engines: tilt {REAL_TILT_DB_PER_OCT[0]:+.1f}..{REAL_TILT_DB_PER_OCT[1]:+.1f}, "
              f"ratio {REAL_HARMONIC_TO_FLOOR_DB[0]:+.1f}..{REAL_HARMONIC_TO_FLOOR_DB[1]:+.1f}")
        return 0

    if not args.fetch:
        parser.print_help()
        return 2

    scored = []
    for path in fetch():
        x, sr = load(path)
        if np.abs(x).max() < 1e-4:
            continue
        score, f0 = estimate_f0(x, sr)
        scored.append((score, f0, path))
    scored.sort(reverse=True)

    print(f"{'clip':<24}{'f0 Hz':>7}{'tilt':>9}{'H/floor':>9}")
    tilts, ratios = [], []
    for _, f0, path in scored[:8]:
        tilt, ratio = measure(*load(path), f0)
        if tilt is None:
            continue
        tilts.append(tilt)
        ratios.append(ratio)
        print(f"{path.name:<24}{f0:7.1f}{tilt:+9.1f}{ratio:+9.1f}")
    if tilts:
        print(f"{'median':<24}{'':>7}{np.median(tilts):+9.1f}{np.median(ratios):+9.1f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
