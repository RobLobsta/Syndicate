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
The user's ask was the whole pipeline: drop in a model, get a ready asset, with every object
assigned to a named part that is individually editable while the vehicle stays one rigid body. The
last clause needed nothing — DEC-004 already makes a vehicle one body with a compound of per-part
hulls, so "one body, editable parts" is what exporting per-part meshes and a slot graph *is*.

Six new modules, each holding the decisions for one stage and none of them importing Blender:

- **`cleanup`** — the correction that puts a dropped-in model in the game's frame, derived and then
  written back to `import.json` as the composition of what was there with what was missing
  (DEC-065). Units come from a closed list of factors, never a continuous fit; a model on its side
  is reported and not repaired.
- **`roles`** — a `panel` becomes a *door*, a *bonnet*, a *bumper*; a `light` becomes a *headlamp*.
  Decided by the plane a panel lies in (its thinnest axis), then by where on the body it sits. Also
  holds the corner/capture/symmetry pass, which is where a caliper is separated from the wheel it
  is clamped to.
- **`hinges`** — stage 6. The interesting part is the *sign*: a left and a right door turn opposite
  ways about the same axis, so it is derived per part from where the free edge has to travel, and
  checked by swinging the panel and requiring it to finish outside the body (D15-E9).
- **`destruction`** — D15-S5.7's table as constants.
- **`manifest`** — every number in `part.json` and `assembly.json`.
- **`exporter`** — the thin Blender half: join, re-origin, solidify, subdivide, dent, hull, export,
  then the glass fracture through the D09 tool once every mesh is on disk.

Three findings worth the entries they got. `volume × density`, the mass rule D09 is built on, is
wrong for vehicle art by a factor of 25 in one direction or infinity in the other, because a car's
panels are shells rather than solids (DISC-035). D15-S4.1's "slot role" column named five things
that are not `SlotType`s, so no conforming exporter could have existed (DEC-063). And the
rotational-symmetry rule as written loses a rim's lug nuts whenever an artist gives the fasteners
their own material, because coverage measures a solid of revolution and a bolt pattern is not one
(DEC-066) — found by a unit test, then found again one layer down as a floating-point wrap at
bearing zero (DISC-036).

**Then it ran on the two shipped cars.** `pip install bpy==4.2.0` gives this sandbox a headless
Blender (DEV-002's second host), so the second half of the session was the pipeline meeting real
art — and real art found four defects that 201 unit tests had not:

- A flat bracket whose *material name* contains `wheel` seeded a corner, producing a 1.44 m
  "wheel" that captured 37% of the Eclipse and exported it as brake furniture (DISC-037).
- The mass rule made a brake hub weigh 214 kg, because 20 mm of wall is right for a rubber tyre
  and wrong by seven times for a steel casting (DEC-067, superseding DEC-064's formula).
- The chassis got no damage morphs at all: one sliver face in 181,000 triangles collapses under
  a 4 cm dent and D09's guard correctly refuses the whole morph (DISC-038).
- `build_collision_hull` crashed on geometry the dissector had never fed it — `geom_unused` and
  `geom_interior` are not disjoint and `bmesh.ops.delete` rejects a duplicate.

Fixed, the two cars come out as vehicles: four corners each with axles matching the
hand-authored slots to four decimals, wheels at 36-39 kg against the shipped 37.5, the Eclipse
at 1,619 kg in class `medium` with a power budget of exactly 74.0 — the same class and budget as
the car somebody authored by hand — 25 part types each, two hinged doors on the Eclipse, and
four damage morphs on both chassis, which is the first content slot 23 has ever had to drive.

**Verification.** 217 unit tests (76 new), `ruff` clean, `validateDocs` green across 457 section
ids, and a new `tools/verify_prepared.py` that opens every exported mesh and checks it against
its own manifest: 50 parts, 2 vehicles, **0 findings**.

## Rationale / Context
Every one of the four defects real art found shares a shape: **nothing failed.** Each run exited
0, each report was well-formed, each part validated, and each vehicle would have loaded — as a car
with a metre-and-a-half front wheel, or half its weight in brake hubs, or no deformation. The unit
tests could not have caught them because they test what the code was asked to do; what was missing
was a model nobody wrote to be tested against.

The one thing this session did **not** do is re-cut `assets/parts`. That overwrites committed
content the game currently loads, the glass does not fracture yet (DISC-039), and it is a content
decision rather than a tool one.

## Impact
- `blender-tool/syndicate_prepare` gains six modules and rewrites four; `syndicate_dissect.emit`
  gains `join_objects` and a duplicate-geometry fix; `tools/verify_prepared.py` is new.
- Three Gradle tasks: `classifyVehicles`, `prepareVehicles`, `verifyPreparedAssets`.
- D15 gains S4.1.1 and S5.8, requirements R1a, R3a-d, R23a-b, R24a-b, R25a-d, R27a, R40-R46 and
  R41a/R45a, four acceptance criteria and fifteen test cases. D08-S4.2 gains `articulation` and
  `yieldImpulseN`.
- DEC-063 to DEC-067, DISC-035 to DISC-039, PROG-024 (superseding PROG-019), and DEC-064's mass
  rule superseded by DEC-067.
