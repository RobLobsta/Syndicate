# PROG-011: the dedicated server runs as a process; there is still nothing to put in it

**Date:** 2026-08-09
**Category:** progress
**Related Docs:** docs/03_runtime_modes.md#D03-S5.1, docs/03_runtime_modes.md#D03-S5.4, docs/08_asset_pipeline.md#D08-S4.6

**Status:** superseded (by PROG-012)

## Summary
`game-server-headless` boots: natives, assets, world, mode-filtered schedule, a 60 Hz tick loop with
D03-S5.4's overload resync, and the teardown of D03-S5.6. What it has no way to do is put anything
in that world — there is no arena, no authored vehicle, and no transport for a client to arrive on.

## Details

**Scope:** `assets/`, `schemas/`, `asset-pipeline`, `game-client`, `game-server-headless`.

**Status of Work:** (supersedes PROG-009)

| Area | State | Notes |
|---|---|---|
| `game-server-headless` | in_progress | `ServerRuntime` runs D03-S5.1 steps 3, 5 and 6 and the D03-S5.6 teardown; `HeadlessLoop` implements D03-S5.4 including the coarse-sleep-then-spin wait and the 30-tick overload resync. No libGDX Application is created (DEV-011). Steps 7 and 8 log that they are absent rather than passing over them |
| `SystemSetFactory.forMode` (D03-S5.2) | done | In `game-core` (DEC-030). A `DEDICATED_SERVER` schedule is nine systems today and names the eighteen it is missing in one log line |
| Transport / connections | not_started | Nothing binds a socket, so a dedicated server accepts no players. D10 is unstarted |
| Match bootstrap (D03-S5.1 step 8) | not_started | `MatchFactory` and `BotFactory` do not exist, so a world comes up empty even with content on disk |
| `assets/materials/materials.json` | done | Six materials, the single density authority the Blender tool reads too (D08-R7) |
| `assets/parts/`, `assets/vehicles/`, `assets/arenas/` | not_started | Empty. The loader fixtures in `game-core/src/test/resources/assets/` are what a real part directory should look like minus the meshes |
| `assets/balance/classes.json` | not_started | Blocks A312's power-budget check (D05-R32) and the build-time half of AC-D05-18 |
| `schemas/` | not_started | Empty; `AssetLoader` validates by hand-written A1xx-A3xx rules instead |
| `asset-pipeline` | not_started | `PipelineMain` logs and exits 70 |
| Real content end to end | blocked | On a headless glTF reader for `game-core` (DEV-010) |
| `game-client` | not_started | `ClientMain` plus one component. Systems 1 and 22-26 are all unwritten; the seam they plug into now exists (DEC-030) |
| `LaunchConfig` / mode selection | done | `shared-models` resolves the four D03 modes and validates their combinations (DEC-007) |

**History (append-only):**
- 2026-08-09: entry created, superseding PROG-009. `ServerRuntime`, `HeadlessLoop`, and a `ServerMain`
  that runs the startup sequence rather than logging that it is unimplemented. 11 tests in the module
  cover the loop's pacing, its tick bound, and four of D03-S4.4's exit codes.

**What the next session should pick up:** nothing here before the content path opens. The single
highest-value item in this entry's scope is the **headless glTF reader** (DEV-010) — `test-environment`
already has one (`GlbReader`, DEC-008), and until `game-core` has its equivalent, no authored part
can load, which blocks arenas, real vehicles, `asset-pipeline` and every screenshot of the game.

## Rationale / Context
PROG-009 recorded both runtime shells as `main` methods that do nothing, and that is no longer true
of the server: the difference between "the simulation runs in a test" and "the simulation runs as a
process" has been closed, and the next reader should not re-close it. It equally matters that the
process is empty — a session could reasonably read "the server boots" as "the server hosts a match",
and the four not-started rows above are why it does not.

## Impact
`assets/`, `schemas/`, `asset-pipeline`, `game-client`, `game-server-headless`. Supersedes PROG-009.
