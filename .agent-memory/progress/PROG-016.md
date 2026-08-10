# PROG-016: the art is split; every part has its own mesh and its own collision hull

**Date:** 2026-08-10
**Category:** progress
**Related Docs:** docs/08_asset_pipeline.md#D08-S4.1, docs/08_asset_pipeline.md#D08-S5.2, docs/03_runtime_modes.md#D03-S5.1

**Status:** superseded (by PROG-017)

## Summary
The blocker PROG-013 and PROG-015 both named is gone. `syndicate_dissect` cuts each whole-vehicle
model into a chassis and four wheels by geometry (DEC-042), and both shipped cars now have six part
meshes with `_col` hulls that the game's own reader loads and measures to the figures the source art
was independently measured to two sessions ago.

## Details

**Scope:** `assets/`, `art-source/`, `schemas/`, `asset-pipeline`, `blender-tool`, `game-client`,
`game-server-headless`, and `game-core`'s `asset` package (DEC-023).

**Status of Work:** (supersedes PROG-015)

| Area | State | Notes |
|---|---|---|
| Headless glTF reader | done | PROG-013. **Wrong for skinned meshes** — see DISC-016; nothing shipped depends on it, because the dissected parts carry no skins |
| Vehicle dissection | done | `blender-tool/syndicate_dissect`: pose, correct, separate, classify, emit. Two cars, eight seconds, no hand editing. `:blender-tool:dissectVehicles` |
| `assets/parts/*/mesh.glb` | done | Six parts. Wheels centred on their axles, hulls ≤ 64 vertices, diameters within 0.1 mm of the recorded tyres |
| Collision meshes for parts | done | Was `blocked` in PROG-013 and PROG-015. `ShippedContent`'s box stand-ins can go |
| `assets/arenas/`, `assets/balance/` | done | PROG-015 |
| `asset-pipeline` | done | PROG-015. Blocking findings on the shipped content fell from 18 to 6 when the meshes landed; the remaining 6 are the missing fracture manifests |
| Chassis wheel slots | not_started | `part.json` still places the wheels at `y 0.0, z ±1.35`; the art has them at `y 0.356, z +1.4565 / −1.2441`. Re-authoring moves the wheelbase 10 cm and the ride height 36 cm, so it needs the calibration re-run attached |
| Damage morph targets | not_started | `dmg_25`…`dmg_100` (D07-S5.5). The dissection exports them if the source has them; neither source does |
| Fracture manifests | not_started | The six remaining A107s. `syndicate_fracture` can now be run per part — Blender works in this environment (bpy 4.2 from PyPI) |
| `schemas/` | not_started | as PROG-015 |
| Weapon and armour content | not_started | as PROG-014: the systems exist, nothing in `assets/` is a weapon or covers anything |
| Transport, match bootstrap, `game-client` | not_started | as PROG-015 |

**History (append-only):**
- 2026-08-10: `syndicate_dissect` (`dissect.py`, `emit.py`, `__main__.py`), 12 pure-Python
  classifier tests, `:blender-tool:dissectVehicles`, six `mesh.glb` files,
  `SplitVehiclePartsTest` reading them back through `GltfCollisionMeshSource`. `SOURCE.md`
  corrected for both cars. 102 Python unit tests and 269 `game-core` tests green.

**What the next session should pick up:** the wheel slots. The art now says exactly where each axle
is and `part.json` disagrees with it by 10 cm longitudinally and 36 cm vertically. Moving them is
half an hour; re-validating `VehicleProfileCalibrationTest` against the new wheelbase and ride height
is the rest of it, and doing the first without the second silently invalidates every figure in
`VEHICLES.md`.

After that: fracture manifests per part, which is now a matter of running the tool that already
exists rather than of installing anything.

## Rationale / Context
Three consecutive progress entries named the split as the one thing standing between the content and
a car in the game, and each assumed it needed a person in Blender. It needed Blender headless, which
`pip install bpy==4.2.0` provides — the same version D02-R12 pins. That is worth recording as plainly
as the split itself, because the assumption "no Blender here" had been carried forward unexamined
since SESS-007 and shaped the plan for four sessions.

## Impact
`blender-tool`, `assets/parts/`, `art-source/vehicles/*/SOURCE.md`, `game-core` `asset` tests.
Supersedes PROG-015.
