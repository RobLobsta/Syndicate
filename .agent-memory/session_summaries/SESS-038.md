# SESS-038: Blender installed, the fixture gate turned on, and the desert made drivable

**Date:** 2026-08-17
**Category:** session_summaries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.6, docs/06_physics_simulation.md#D06-S5.5, docs/16_procedural_arena_generation.md#D16-S5.6

**Status:** active

## Summary
Blender installs in the sandbox in ninety seconds, retiring an assumption three sessions built
around and exposing that the executable host had never run. Fixing it turned on the D14-S7.3 fixture
gate for the first time. Then the client was driven, which found worse than any test had: every car
in the desert starts several metres underground.

## Details

**Blender and the fixture gate.** `install-blender.sh` fetches headless 4.2.13 LTS. The executable
invocation could not import the tool and `--python-expr` exits 0 on an uncaught exception, so the
fixture task ran five fixtures, failed all five and reported success (DISC-064). Fixed;
`verifyFixtures` now passes 31/31 per fixture. Three fracture-tool bugs fell out of pointing it at
real parts (DISC-066), and DISC-065's claim that the 44 unfractured parts were a gap was wrong —
D15-S5.7 gives shards to `glass` alone.

**The two things the first drive had found.** DISC-063's airborne clamp, now horizontal-only while
no wheel is in contact (DEV-019); DISC-062's road canyon, now a load-time guard on the measured cut.
Both tested against a deliberately disabled fix.

**Then it was driven.** The desert at full throttle, photographed at six frames. Every car starts
buried (DISC-067): spawns are authored at `y = 1.0`, the pad levels the
ground to the landform's height up to 7.44 m above that, and the chassis is created at the authored
`y`. Bullet ejects it. The Eclipse lost 26 of 40 parts in 1.5 s and finished upside down and
immobile — alive, so never respawned — for the remaining three minutes. Fixed; driven again upright
and driving.

It also caught a regression in this session's own clamp fix: bounding only the horizontal term left
the vertical *unbounded* rather than left to gravity, and the ejection impulse rode it to 1167 km/h.
Bounded at 55 m/s. The test that let it through asserted `speed > MAX + 2`, which 44 and 324 satisfy
equally.

**Found and not fixed:** no `FractureManifest` is ever loaded at runtime, so every glass part
shatters without shards and the authored destruction path has never run. Now step 0 of the roadmap.

## Rationale / Context
Five things were believed rather than measured: that Blender was unavailable, that the executable
host worked, that the missing manifests were a gap, that the clamp fix was complete, and that the
desert was drivable. The last was only reachable by running the game — DISC-051's lesson, a fourth
time.

Two smaller traps: the default arena is the scrapyard, so "drive the desert" needs `--arena`; and a
drive without `--seed` cannot be compared with the one before it.

## Impact
- `blender-tool/` — installer, host invocation, watertight check, morph idempotence.
- `game-core` `vehicle` and `arena` — three fixes, each with a test that fails without it.
- DISC-064, DISC-066, DISC-067, DEV-019 written; DISC-065 superseded.
