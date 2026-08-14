# PROG-012: two calibrated vehicles ship; only their meshes are missing

**Date:** 2026-08-09
**Category:** progress
**Related Docs:** docs/08_asset_pipeline.md#D08-S4.2, docs/05_vehicle_part_system.md#D05-S5.6, docs/03_runtime_modes.md#D03-S5.1

**Status:** superseded (by PROG-028)

## Summary
`assets/` holds two complete vehicles — six part types and two assemblies — whose mass, power,
braking and aerodynamics are derived from a published road supercar and a published GT3 racer. They
load, validate, spawn and drive to their reference cars' figures in tests. The one thing they cannot
do is render, because no `mesh.glb` can be read yet (DEV-010).

## Details

**Scope:** `assets/`, `schemas/`, `asset-pipeline`, `game-client`, `game-server-headless`.

**Status of Work:** (supersedes PROG-011)

| Area | State | Notes |
|---|---|---|
| `assets/parts/` | in_progress | Two vehicles' worth: `chassis_apex_gt_01`, `chassis_stampede_gt3_01`, and front/rear wheel types for each. Every one carries a `handling` block (DEC-031) |
| `assets/vehicles/` | in_progress | Two of them: `vehicle_apex_gt_01`, `vehicle_stampede_gt3_01` |
| `assets/README.md` | done | The layout, the naming and conventions a model must follow, and where a `.glb` goes |
| `VEHICLES.md` | done | Generated from `VehicleProfiles` by `VehicleTableTest`, which rewrites it and fails when the committed copy is stale — the discipline `INDEX.md` gets |
| Vehicle profiles | done | `VehicleProfile` records each real car's published figures and the derivation into simulation parameters; `VehicleProfileContentTest` holds the JSON to it and `VehicleProfileCalibrationTest` holds the simulation to the real car |
| Collision meshes | blocked | DEV-010. `ShippedContent` supplies stand-in box hulls sized from the published dimensions, in tests only |
| `assets/materials/materials.json` | done | Eight materials (D08-R7) |
| `assets/arenas/`, `assets/balance/` | not_started | No `ArenaFactory`, no class targets for AC-D05-18 |
| `schemas/` | not_started | Empty; `AssetLoader` validates by hand-written A1xx-A3xx rules |
| `asset-pipeline` | not_started | `PipelineMain` logs and exits 70 |
| `game-server-headless` | in_progress | as PROG-011: boots, ticks, tears down. It now loads the two vehicles at startup and reports A503 per part for the missing meshes |
| Transport, match bootstrap | not_started | as PROG-011 |
| `game-client` | not_started | as PROG-011 |

**History (append-only):**
- 2026-08-09: entry created, superseding PROG-011. The two vehicles, `assets/README.md`, `VEHICLES.md`
  and its generator, and the profile/content/calibration test trio. 285 tests green across the JVM
  modules.

**What the next session should pick up:** the **headless glTF reader** (DEV-010), unchanged from
PROG-011 and now the only thing between a `.glb` in a part directory and a car on screen. Everything
either side of it works: the JSON loads and validates, the vehicles spawn, and they drive to their
reference figures. `test-environment` already has a reader (`GlbReader`, DEC-008) that is the shape
of the answer.

## Rationale / Context
PROG-011 said there was no shipped content and named the mesh reader as the first work in this scope.
Half of that is now wrong — there is content, and it is calibrated — and the half that is right has
gone from "one of several blockers" to "the only one". A session reading PROG-011 would author a
third vehicle before noticing that none of them can be seen.

## Impact
`assets/`, `VEHICLES.md`, `game-core` `vehicle`. Supersedes PROG-011.
