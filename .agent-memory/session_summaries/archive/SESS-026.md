# SESS-026: the preparation pipeline finishes — a model in, a vehicle out

**Date:** 2026-08-13
**Category:** session_summaries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.1, docs/15_vehicle_preparation_pipeline.md#D15-S5.8, docs/08_asset_pipeline.md#D08-S4.2

**Status:** active

## Summary
Implemented D15-S5.1's stages 2 (applied rather than reported), 6, 7 and 8, and completed stage 4
with roles and the wheel/hub symmetry pass. `syndicate-prepare` now takes a directory holding a
downloaded model and writes a directory of parts and an assembly the game loads.

## Details
The ask was the whole pipeline: drop in a model, get a ready asset, every object a named part
that is individually editable while the vehicle stays one rigid body. The last clause needed
nothing — DEC-004 already makes a vehicle one body with a compound of per-part hulls.

Six new modules, each holding one stage's decisions and none importing Blender:

| Module | What it decides |
|---|---|
| `cleanup` | The correction putting a dropped-in model in the game's frame, written back to `import.json` as a composition (DEC-065). Units from a closed list, never a continuous fit; a model on its side is reported, not repaired. |
| `roles` | A `panel` becomes a door, bonnet or bumper — by the plane it lies in, then by where it sits. Also the corner/capture/symmetry pass that separates a caliper from its wheel. |
| `hinges` | Stage 6. The interesting part is the *sign*: left and right doors turn opposite ways about the same axis, so it is derived per part and checked by swinging the panel (D15-E9). |
| `destruction` | D15-S5.7's table as constants. |
| `manifest` | Every number in `part.json` and `assembly.json`. |
| `exporter` | The thin Blender half: join, re-origin, subdivide, dent, hull, export, then glass. |

Three findings worth their entries. `volume × density`, the mass rule D09 is built on, is wrong
for vehicle art by a factor of 25 one way or infinity the other, because a car's panels are shells
(DISC-035). D15-S4.1's "slot role" column named five things that are not `SlotType`s, so no
conforming exporter could have existed (DEC-063). And the rotational-symmetry rule loses a rim's
lug nuts once the fasteners get their own material, because coverage measures a solid of
revolution and a bolt pattern is not one (DEC-066, then DISC-036 one layer down).

**Then it ran on the two shipped cars.** `pip install bpy==4.2.0` gives this sandbox a headless
Blender (DEV-002's second host), and real art found four defects that 201 unit tests had not:

| Defect | Cause |
|---|---|
| A 1.44 m "wheel" capturing 37% of the Eclipse | a flat bracket whose *material name* contains `wheel` seeded a corner (DISC-037) |
| A 214 kg brake hub | 20 mm of wall is right for a tyre and seven times wrong for a steel casting (DEC-067, superseding DEC-064) |
| No chassis damage morphs at all | one sliver face in 181,000 triangles collapses under a 4 cm dent, and D09's guard correctly refuses the whole morph (DISC-038) |
| `build_collision_hull` crash | `geom_unused` and `geom_interior` are not disjoint and `bmesh.ops.delete` rejects a duplicate |

Fixed, both cars come out as vehicles: axles matching the hand-authored slots to four decimals,
wheels at 36-39 kg against the shipped 37.5, the Eclipse at 1,619 kg in class `medium` with a
budget of exactly 74.0 — the authored car's class and budget — 25 part types each, two hinged
doors on the Eclipse, and four damage morphs on both chassis, the first content slot 23 has had.

**Verification.** 217 unit tests (76 new), `ruff` clean, `validateDocs` green across 457 section
ids, and a new `tools/verify_prepared.py`: 50 parts, 2 vehicles, **0 findings**.

**Not done:** re-cutting `assets/parts`, which overwrites content the game loads.

## Rationale / Context
All four defects share a shape: **nothing failed.** Each run exited 0, each report was
well-formed, each part validated, and each vehicle would have loaded — as a car with a
metre-and-a-half front wheel, or half its weight in brake hubs, or no deformation. Unit tests
could not have caught them, because they test what the code was asked to do.

## Impact
- `syndicate_prepare` gains six modules and rewrites four; `syndicate_dissect.emit` gains
  `join_objects` and a duplicate-geometry fix; `tools/verify_prepared.py` is new.
- Three Gradle tasks: `classifyVehicles`, `prepareVehicles`, `verifyPreparedAssets`.
- D15 gains S4.1.1 and S5.8, R1a, R3a-d, R23a-b, R24a-b, R25a-d, R27a, R40-R46, R41a/R45a, four
  acceptance criteria and fifteen test cases. D08-S4.2 gains `articulation` and `yieldImpulseN`.
- DEC-063 to DEC-067, DISC-035 to DISC-039, PROG-024 superseding PROG-019.
