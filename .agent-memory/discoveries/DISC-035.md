# DISC-035: `volume × density` is the wrong mass rule for vehicle art, by 25×

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S6.2, docs/15_vehicle_preparation_pipeline.md#D15-S5.8

**Status:** active

## Summary
D09-R16 makes a part's mass `volume × density`, and G7's conservation across a fracture follows
from it. Applied to a car's panels it is not imprecise but wrong in kind: a door skin either
encloses a tenth of a cubic metre of air (785 kg of "steel") or encloses nothing (0 kg). Neither is
within an order of magnitude of the 29 kg a door weighs.

## Details
The rule is right where D09 uses it. A fracture shard *is* its geometry: solid, closed, one material
throughout, so volume times density is its mass and the shards of a part sum to the part.

Vehicle art is not like that. Every panel on a downloaded car is a **surface**: a door is one skin,
a windscreen a curved sheet, a bonnet a shell. Two failure modes follow, pointing in opposite
directions, which is what makes the bug hard to spot from one measurement:

- An **open** surface has a signed volume of about zero, so `volume × density` gives 0 kg, the part
  falls below `MIN_BODY_MASS_KG`, and the vehicle has no doors as far as physics is concerned.
- A **closed but hollow** shell — the same door as a box — encloses its own air: 0.1 m³ and 785 kg
  of "steel", half a car per door.

The rule that works is area-based, with a per-class constant (D15-R33; DEC-067 later made that
constant an areal density rather than a thickness). It says what a panel physically is — a sheet of
some gauge — and is stable across both failure modes, because surface area is well defined whether
or not a mesh is closed.

The two rules are made to agree rather than left to differ. A `glass` part is fractured by the shell
path (D09-S5.2.1), which is handed *this pipeline's own* wall thickness and gives each shard that
wall, so the mass D09 computes by volume and the mass computed here by area are the same number, and
A202's cross-check passes by construction rather than by tolerance.

## Rationale / Context
Recorded because the wrong rule is the *documented* one, and documented for a good reason in the
document next door. Anybody applying D09-R16 to a prepared part gets a manifest that passes every
schema check and a vehicle that handles like lead. The distinction to carry: **volume is the mass
of a solid; area × thickness is the mass of a shell,** and vehicle art is shells all the way down.

## Impact
- `manifest.WALL_THICKNESS_M` and the area-based mass; D15-R41 records the rule.
- `exporter.fracture_glass` passes that same thickness to the shell fracture as `--shell-thickness`.
- Calibration against the shipped parts: a 1.5 m² door lands at 29 kg, a windscreen at 15 kg, a
  245/35 R20 wheel at 37.4 kg against the Eclipse's authored 37.5.
