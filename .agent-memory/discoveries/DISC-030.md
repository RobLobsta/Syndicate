# DISC-030: the rumble is an envelope ratio, and the sub-orders were never the problem

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R38a10, D15-R38a12

**Status:** active

## Summary
An engine's lope is the ratio of sub-order to firing-order *envelope* modulation. A real
cross-plane V8 idling measures 17.4 and 18.2 on two steady windows. This synthesiser measured
1.25 — and the reason was not weak sub-orders. Those were already right.

## Details
Per-order breakdown of a Mustang GT against ours at matched rpm:

| | sub-orders | firing order | ratio |
|---|---|---|---|
| real V8, 703 rpm | 27.0% | 1.6% | 17.4 |
| real V8, 791 rpm | 79.2% | 4.4% | 18.2 |
| ours, before | 28.1% | 20.3% | 1.4 |
| ours, after | 30.0% | 4.0% | 7.6 |

The sub-orders matched to within a percentage point from the start. **The whole defect was one
number**: the engine pumped once per cylinder, twelve times harder than the real one, and a pump at
60 Hz is a buzz where a lope is what the ear listens for. No spectral measurement finds this; the
firing-order magnitude, the tilt and the harmonic-to-floor ratio were all already inside their
measured ranges.

Two mechanisms fixed it, both physical and both previously absent:

**The exhaust event was half-modelled.** Only the blowdown existed — a third of a firing interval,
then nothing — so the gaps between firings were empty. The piston still sweeps the remainder out
over 180° of crank: four times longer, smooth, no transient. A quarter of any engine's cylinders are
always sweeping, and their overlap leaves the differences *between* cylinders as the only thing
still modulating, which repeats once per cycle.

**Pulses need dispersing, not filtering.** See DISC-031 for why the obvious mechanism is a trap.

## Rationale / Context
Two sessions were spent trying to raise the sub-orders — through cylinder scatter, bank imbalance
and pulse shaping — because "not enough rumble" was assumed to mean "not enough low-order content".
Every one of those levers is now known to be at its ceiling, pinned by the burble-contrast and
crank-chuff tests, and none of them was the problem. Measure both halves of a ratio before tuning
either.

## Impact
- D15-R38a10 and R38a12 added; T-D15-21 added.
- V8 rumble at the two matched reference speeds: 1.32/1.19 to 7.58/8.05. Through the mixer at the
  game's own listener distance, 9.9.
- `engine_reference.py` gains `--rumble` and `--compare`, which print the per-order table above.
