# SESS-038: Blender installed, the executable host repaired, and two driving bugs closed

**Date:** 2026-08-17
**Category:** session_summaries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.6, docs/06_physics_simulation.md#D06-S5.5, docs/16_procedural_arena_generation.md#D16-S5.4

**Status:** active

## Summary
Blender 4.2 installs in the sandbox in ninety seconds, retiring an assumption three sessions had
built around. That exposed that the `blender` executable host had never run. Fixing it turned on the
D14-S7.3 fixture gate for the first time and found three bugs in the fracture tool. Separately,
DISC-062 and DISC-063 are fixed and tested.

## Details

**The host.** `blender-tool/tools/install-blender.sh` fetches headless 4.2.13 LTS. The executable
invocation could not import the tool — Blender's bundled Python builds `sys.path` from `PYTHONHOME`
and reads neither `PYTHONPATH` nor the working directory — and `--python-expr` exits 0 on an
uncaught exception, so the fixture task ran five fixtures, failed all five and reported success
(DISC-064). Both fixed. `:test-environment:verifyFixtures` now passes 31/31 per fixture, the first
time that gate has executed.

**The fracture tool.** Three defects, fixed with unit tests: watertightness was never checked
despite D09 having exit 66 for it; the check must weld by position or glTF's per-normal vertex
splitting rejects the tool's own fixtures; and re-fracturing an already-dented part duplicated its
`dmg_*` shape keys (DISC-066).

**The correction that matters.** DISC-065 read "44 of 53 parts have no fracture manifest" as a gap.
It is not: D15-S5.7 gives shards to `glass` alone and the pipeline implements that faithfully.
Superseded by DISC-066 rather than edited, because the wrong reading is the part worth leaving.

**The two driving bugs.**

- *DISC-063, the airborne clamp.* D06-S5.5 rescales the whole velocity vector, which on a car at the
  horizontal limit removes whatever gravity adds. Now horizontal-only while no wheel is in contact;
  grounded behaviour is unchanged so the seed-locked regressions do not move (DEV-019). The obvious
  test does not discriminate — the old clamp *rotates* the vector rather than freezing it, so a
  launched car descends either way. The signature is total speed pinned at exactly 40. All three
  tests were checked against a deliberately disabled fix.
- *DISC-062, the road canyon.* A guard on the carve's measured cut, calibrated against DISC-062's
  table (2.7 and 3.3 m inside the playable area, 30.8 m into the rim). A measurement rather than a
  rule about spline length, because the two arenas have different cell sizes and rim positions. A
  first attempt as a geometric pre-check was abandoned: it rejected the existing ±250 m test road.

## Rationale / Context
The through-line is that three things were believed rather than measured — that Blender was
unavailable, that the executable host worked, and that the missing manifests were a gap. Each was
cheap to check; none had been.

## Impact
- `blender-tool/` — installer, host invocation, watertight check, morph idempotence.
- `game-core` `vehicle` and `arena` — the two driving fixes, with tests.
- `test-environment/build.gradle.kts` — a failing harness reports its output, not just its code.
- DISC-064, DISC-066, DEV-019 written; DISC-065 superseded.
