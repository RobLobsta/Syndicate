# PROG-009: no shipped content, and both runtime shells are still stubs

**Date:** 2026-08-09
**Category:** progress
**Related Docs:** docs/08_asset_pipeline.md#D08-S4.6, docs/03_runtime_modes.md#D03-S5.1, docs/02_technical_architecture.md#D02-S4.5

**Status:** active

## Summary
`assets/` holds one material table and nothing else; `schemas/` is empty; `asset-pipeline` exits 70;
`game-client` and `game-server-headless` are `main` methods that do nothing. The game can now build a
vehicle from an assembly, but there is no authored vehicle to build and no process that would run it.

## Details

**Scope:** `assets/`, `schemas/`, `asset-pipeline`, `game-client`, `game-server-headless` — the
things standing between a working simulation and something a person can start.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| `assets/materials/materials.json` | done | Six materials, the single density authority the Blender tool reads too (D08-R7) |
| `assets/parts/` | not_started | Empty. A representative set exists as loader fixtures in `game-core/src/test/resources/assets/` — three parts and one assembly — which is what a real part directory should look like minus the meshes |
| `assets/vehicles/` | not_started | Empty, same as above |
| `assets/arenas/` | not_started | Empty. `ArenaFactory` (D04-S5.4) does not exist either, so there is nowhere to spawn a vehicle onto except an implicit ground plane in a test |
| `assets/balance/classes.json` | not_started | Blocks A312's power-budget check (D05-R32) |
| `schemas/` | not_started | Empty. D08-S6.1's catalogue of JSON Schemas is unwritten, so `AssetLoader` validates by hand-written rules (A1xx–A3xx) rather than by schema; A102's "JSON pointer of each violation" is therefore approximated |
| `asset-pipeline` | not_started | `PipelineMain` logs and exits 70. Its output, `asset-index.json`, is what D08-S5.3's loader is specified to read; `AssetLoader` walks the directory tree instead (DEV-010) |
| Real content end to end | blocked | On a headless glTF reader for `game-core` (DEV-010). Everything else in the chain — part schema, assembly schema, validation, spawn — now works on fixtures |
| `game-client` | not_started | `ClientMain` plus one component. No window, no GL context, no render systems, no input mapping; systems 22–26 of D04-S4.4 are all client-side and all unwritten |
| `game-server-headless` | not_started | `ServerMain` only. No tick loop, no connection management |
| `LaunchConfig` / mode selection | done | `shared-models` resolves the four D03 runtime modes and validates their combinations (DEC-007) |
| `SystemSetFactory.forMode` (D03-S5.2) | not_started | The schedule is assembled by hand in test scenes; nothing builds a mode's system list |

**History (append-only):**
- 2026-08-09: entry created under DEC-023 to give content and the runtime shells a tracked home.
  `materials.json` and the loader fixtures are the only content that exists.

**What the next session should pick up:** nothing here, yet — PROG-007's `VehicleStatsSystem` (6)
and `VehicleControlSystem` (7) come first, because a driveable vehicle is what makes a client worth
opening a window for. The first work in this entry's scope is `SystemSetFactory.forMode` plus a
minimal `game-server-headless` tick loop: that turns "the simulation runs in a test" into "the
simulation runs as a process", which is the smallest step towards something playable and needs no
art at all.

## Rationale / Context
Every other subsystem's state was tracked somewhere; content and the runtime shells were only ever
"not_started" rows at the bottom of a `game-core` table, which is how they stayed invisible while the
system catalogue filled up. Naming them, with the specific blockers written down, is what makes it
possible to notice that the gap between "the simulation works" and "the game runs" is now mostly
here rather than in `game-core`.

## Impact
`assets/`, `schemas/`, `asset-pipeline`, `game-client`, `game-server-headless`. Restates and extends
the not-started rows PROG-002 through PROG-006 carried.
