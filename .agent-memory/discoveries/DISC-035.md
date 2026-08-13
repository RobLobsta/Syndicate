# DISC-035: `volume × density` is the wrong mass rule for vehicle art, by 25×

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S6.2, docs/15_vehicle_preparation_pipeline.md#D15-S5.8

**Status:** active

## Summary
D09-R16 makes a part's mass `volume × density`, and G7's mass conservation across a fracture is a
consequence of it. Applied to a car's own panels it is not merely imprecise, it is wrong in kind: a
door skin either encloses a tenth of a cubic metre of air (785 kg of "steel") or encloses nothing at
all (0 kg). Neither number is within an order of magnitude of the 29 kg a door weighs.

## Details
The rule is right where D09 uses it. A fracture shard *is* its geometry: solid, closed, and made of
one material throughout, so its volume times its density is its mass and the shards of a part sum to
the part.

Vehicle art is not like that. Every panel on a downloaded car is a **surface**: a door is one skin
with no thickness, a windscreen is a curved sheet, a bonnet is a shell. Two failure modes follow and
they point in opposite directions, which is what makes the bug hard to spot from one measurement:

- An **open** surface has a signed volume of approximately zero. `volume × density` gives 0 kg, the
  part falls below `MIN_BODY_MASS_KG`, and the vehicle has no doors as far as the physics is
  concerned.
- A **closed but hollow** shell — the same door modelled as a box — encloses its own air. A
  1.1 × 0.1 × 1.0 m door reports 0.1 m³ and 785 kg of steel, which is half a car per door.

The rule that works is `area × wall thickness × density`, with the thickness a per-class constant
(D15-R33). It says what a panel physically is — a sheet of steel of some gauge — and it is stable
across both failure modes above, because surface area is well defined whether or not a mesh is
closed.

The two rules are made to agree rather than left to differ: a `glass` part is solidified to its
class's wall thickness before it is fractured, so the solid the D09 tool then weighs by volume comes
out at the same number this pipeline computed by area, and A202's cross-check between `part.json`
and the fracture manifest passes by construction rather than by tolerance.

## Rationale / Context
Recorded because the wrong rule is the *documented* one, and it is documented for a good reason in
the document next door. Anybody reading D09-R16 and applying it to a prepared part will produce a
manifest that passes every schema check and a vehicle that handles like it is made of lead. The
distinction to carry is: **volume is the mass of a solid; area × thickness is the mass of a shell,**
and vehicle art is shells all the way down.

## Impact
- `manifest.WALL_THICKNESS_M` and `manifest.geometric_mass_kg`; D15-R41 records the rule.
- `exporter.solidify` gives a glass part a wall before it is fractured, at the same thickness.
- Calibration against the shipped parts: a 1.5 m² door lands at 29 kg, a windscreen at 15 kg, a
  245/35 R20 wheel at 37.4 kg against the Eclipse's authored 37.5.
