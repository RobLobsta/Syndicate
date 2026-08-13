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

**Verification.** 60 new unit tests (201 total, all green), `ruff` clean, `validateDocs` green
across 457 section ids. Nothing ran in Blender: this environment has no host, so the geometry half
of stages 7 and 8 is written and unexercised. PROG-024 says so in those words rather than implying
otherwise.

## Rationale / Context
The honest limit on this session is the missing Blender host. Every *decision* the new stages make
is tested against synthetic geometry, and the Blender code is deliberately thin and built out of
`syndicate_dissect.emit` and `syndicate_fracture.morphs`, which the fixture pipeline already
exercises — but the first run on a real car will be the first run, and it should be one of the two
shipped cars rather than a new one, because their numbers are known.

## Impact
- `blender-tool/syndicate_prepare` gains six modules and rewrites four; `syndicate_dissect.emit`
  gains `join_objects`; `:blender-tool:classifyVehicles` joins `prepareVehicles`.
- D15 gains S4.1.1 and S5.8, requirements R1a, R3a-c, R24a-b, R25a-d, R27a and R40-R46, four
  acceptance criteria and ten test cases. D08-S4.2 gains `articulation` and `yieldImpulseN`.
- DEC-063 to DEC-066, DISC-035, DISC-036, PROG-024 (superseding PROG-019).
