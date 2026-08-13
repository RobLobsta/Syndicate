#!/usr/bin/env python3
"""Measure real engine recordings and compare EngineSynth against them (D15-R38a4, D15-R38a10).

Three numbers carry most of an exhaust note's character. Each was badly wrong in this synthesiser
until a real engine was measured, and each was found by ear first:

  * **tilt** -- how fast the harmonics fall, in dB per octave between 100 Hz and 4 kHz.
    Too steep and the engine sounds muffled rather than loud (DISC-026).
  * **harmonic-to-floor** -- how far the harmonics stand above the noise between them, in dB.
    An engine with nothing between its harmonics reads as synthetic however correct its firing
    geometry is, and this is the number that says so (DISC-026).
  * **rumble** -- the ratio of sub-order to firing-order *envelope* modulation. This is the one a
    spectrum cannot see, and it is the difference between a lope and a buzz. A real cross-plane V8
    varies its loudness by tens of percent at rates below its firing order and by only 1.6 to 4.4%
    at the firing order itself; a synthesiser that pulses evenly once per cylinder does the exact
    opposite and sounds like a machine (DISC-030).

Reference clips come from ESC-50 (https://github.com/karolpiczak/ESC-50), which is CC BY-NC.
They are **analysis inputs only**: this script downloads them outside the repository and nothing
derived from them is ever committed, so `assets/audio` stays free of third-party terms (D15-R39).
The same applies to any recording handed to `--rumble` or `--compare`.

Usage
-----
    python3 game-client/tools/engine_reference.py --fetch
    python3 game-client/tools/engine_reference.py --measure a.wav 26
    python3 game-client/tools/engine_reference.py --identify real.wav --window 2.1 3.0
    python3 game-client/tools/engine_reference.py --rumble a.wav --cylinders 8 --rpm 790
    python3 game-client/tools/engine_reference.py --compare real.wav mine.wav \
        --cylinders 8 --rpm 790 --window 2.1 3.0

`--identify` is the one to reach for with an unfamiliar recording: it reports the strongest
envelope-modulation peaks and whether they form a coherent series. **Check that before trusting a
clip as a reference.** A clip of several cars starting at once was used as a four-cylinder
reference for most of a session before this said, correctly, that its peaks are not one engine's
orders and the number taken from it meant nothing.

`--fetch` needs `raw.githubusercontent.com`; every other audio host tried was blocked by the
sandbox proxy, which is how ESC-50 came to be the source.

To measure the synthesiser, render a steady-state WAV from `EngineSynth` at a known rpm and pass
it with the firing frequency (`cylinders * rpm / 120`) as `f0`, or give `--cylinders` and `--rpm`
and let the tool work the orders out.
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

# Measured over two steady windows of a Ford Mustang GT idling. D15-R38a10 quotes these.
REAL_RUMBLE_RATIO = (17.4, 18.2)


def load(path: pathlib.Path, window: tuple[float, float] | None = None) -> tuple[np.ndarray, int]:
    """A WAV, or an MP3 if `miniaudio` is installed. Optionally one window of it, in seconds."""
    if path.suffix.lower() == ".mp3":
        import miniaudio  # optional: only needed for compressed references

        decoded = miniaudio.decode_file(str(path), nchannels=1, dither=miniaudio.DitherMode.NONE)
        data, rate = np.asarray(decoded.samples, dtype=float) / 32768.0, decoded.sample_rate
    else:
        with wave.open(str(path)) as w:
            raw = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
            if w.getnchannels() == 2:
                raw = raw.reshape(-1, 2).mean(axis=1)
            data, rate = raw.astype(float) / 32768.0, w.getframerate()
    if window:
        data = data[int(window[0] * rate) : int(window[1] * rate)]
    return data, rate


def envelope(x: np.ndarray, sr: int, smooth_s: float = 0.003) -> tuple[np.ndarray, float]:
    """Rectified, lightly smoothed loudness with its mean removed.

    Rectification is the point: the rumble is an *amplitude* pattern, and looking for it in the
    spectrum proper finds the exhaust pulses instead.
    """
    e = np.abs(x)
    n = max(1, int(smooth_s * sr))
    e = np.convolve(e, np.ones(n) / n, "same")
    mean = float(e.mean())
    return e - mean, max(mean, 1e-12)


def modulation(e: np.ndarray, mean: float, sr: int, hz: float, tol: float = 0.7) -> float:
    """Strongest loudness modulation within `tol` Hz of a rate, as a fraction of mean level."""
    t = np.arange(len(e))
    grid = np.arange(max(0.5, hz - tol), hz + tol + 1e-9, 0.05)
    return max(float(abs(np.dot(e, np.exp(-2j * np.pi * f * t / sr))) / len(e) / mean) for f in grid)


def rumble(x: np.ndarray, sr: int, cylinders: int, rpm: float):
    """Per-order envelope modulation, and the sub-order-to-firing-order ratio.

    Orders are counted against the *cycle* rate (`rpm / 120`), so the firing order is the cylinder
    count. Each order is reported next to the modulation halfway to the next one: if the two are
    comparable the recording is drifting rather than loping, and the ratio means nothing.
    """
    e, mean = envelope(x, sr)
    cycle = rpm / 120.0
    rows, subs = [], 0.0
    for k in range(1, cylinders + 1):
        at = modulation(e, mean, sr, cycle * k)
        between = modulation(e, mean, sr, cycle * (k + 0.5), tol=cycle * 0.1)
        rows.append((k, cycle * k, at, between))
        if k < cylinders:
            subs += at
    firing = rows[-1][2]
    return rows, subs, firing, subs / max(firing, 1e-9)


def peaks(x: np.ndarray, sr: int, lo: float = 2.0, hi: float = 120.0):
    """The strongest envelope-modulation peaks, for identifying an unfamiliar recording."""
    e, mean = envelope(x, sr)
    t = np.arange(len(e))
    grid = np.arange(lo, hi, 0.1)
    mag = np.array([abs(np.dot(e, np.exp(-2j * np.pi * f * t / sr))) / len(e) / mean for f in grid])
    found = [
        (grid[i], mag[i])
        for i in range(1, len(grid) - 1)
        if mag[i] > mag[i - 1] and mag[i] > mag[i + 1]
    ]
    found.sort(key=lambda p: -p[1])
    return found


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
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--fetch", action="store_true", help="download and measure the reference clips")
    parser.add_argument("--measure", nargs=2, metavar=("WAV", "F0_HZ"), help="tilt and floor of one file")
    parser.add_argument("--identify", metavar="WAV", help="find the engine orders in an unfamiliar clip")
    parser.add_argument("--rumble", metavar="WAV", help="per-order envelope modulation of one file")
    parser.add_argument("--compare", nargs=2, metavar=("REFERENCE", "SYNTH"), help="both, side by side")
    parser.add_argument("--cylinders", type=int, default=8, help="cylinder count (default 8)")
    parser.add_argument("--rpm", type=float, help="engine speed of the window being measured")
    parser.add_argument("--window", nargs=2, type=float, metavar=("FROM_S", "TO_S"),
                        help="analyse only this window; pick a steady one")
    args = parser.parse_args()
    window = tuple(args.window) if args.window else None

    if args.measure:
        path, f0 = pathlib.Path(args.measure[0]), float(args.measure[1])
        tilt, ratio = measure(*load(path, window), f0)
        print(f"{path.name}: tilt {tilt:+.1f} dB/oct   harmonic-to-floor {ratio:+.1f} dB")
        print(f"  real engines: tilt {REAL_TILT_DB_PER_OCT[0]:+.1f}..{REAL_TILT_DB_PER_OCT[1]:+.1f}, "
              f"ratio {REAL_HARMONIC_TO_FLOOR_DB[0]:+.1f}..{REAL_HARMONIC_TO_FLOOR_DB[1]:+.1f}")
        return 0

    if args.identify:
        path = pathlib.Path(args.identify)
        found = peaks(*load(path, window))
        print(f"{path.name}: strongest envelope-modulation peaks")
        for hz, depth in found[:10]:
            print(f"   {hz:7.2f} Hz  {depth * 100:6.2f}%")
        if not found:
            return 1
        base = found[0][0]
        series = sum(1 for hz, _ in found[:10] if abs(hz / base - round(hz / base)) < 0.06)
        print(f"  {series} of the top 10 are near-multiples of {base:.2f} Hz.")
        print("  A single engine puts most of them on one series. Fewer than about half means"
              " several sources, and no order taken from this clip is one engine's.")
        return 0

    if args.rumble or args.compare:
        if not args.rpm:
            parser.error("--rumble and --compare need --rpm")
        targets = [pathlib.Path(args.rumble)] if args.rumble else [pathlib.Path(p) for p in args.compare]
        labels = ["measured"] if args.rumble else ["reference", "synth"]
        for label, path in zip(labels, targets):
            # Only the reference gets the window; a rendered synth file is steady throughout.
            x, sr = load(path, window if label != "synth" else None)
            rows, subs, firing, ratio = rumble(x, sr, args.cylinders, args.rpm)
            print(f"\n{label}: {path.name}   {args.cylinders} cyl at {args.rpm:.0f} rpm")
            for k, hz, at, between in rows:
                tag = "  <- firing order" if k == args.cylinders else ""
                print(f"   order {k:2d} = {hz:6.2f} Hz  {at * 100:6.2f}%   between {between * 100:6.2f}%{tag}")
            print(f"   sub-order total {subs * 100:5.1f}%   firing {firing * 100:5.1f}%   "
                  f"rumble ratio {ratio:6.2f}")
            tilt, floor = measure(x, sr, args.cylinders * args.rpm / 120.0)
            if tilt is not None:
                print(f"   tilt {tilt:+.1f} dB/oct   harmonic-to-floor {floor:+.1f} dB")
        print(f"\nreal cross-plane V8: rumble {REAL_RUMBLE_RATIO[0]:.1f}..{REAL_RUMBLE_RATIO[1]:.1f}, "
              f"tilt {REAL_TILT_DB_PER_OCT[0]:+.1f}..{REAL_TILT_DB_PER_OCT[1]:+.1f}, "
              f"floor {REAL_HARMONIC_TO_FLOOR_DB[0]:+.1f}..{REAL_HARMONIC_TO_FLOOR_DB[1]:+.1f}")
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
