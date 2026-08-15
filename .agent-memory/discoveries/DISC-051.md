# DISC-051: the style pass painted 41 of 60 materials as trim, and erased the wheels

**Date:** 2026-08-15
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S4.5, docs/15_vehicle_preparation_pipeline.md#D15-S5.9

**Status:** active

## Summary
`classify` sent every material with no physical or nominal evidence to the **trim** row —
near-black, non-metallic, rough. On the shipped Eclipse that is 41 of its 60 materials, both alloy
wheels among them. The car rendered as grey mush with two black discs where its wheels are.

## Details
Found by looking at it. The pipeline's own report says which surface every material was given, and
41 rows of `trim` on a supercar is not a plausible answer to "what is this made of" — but the
decisive evidence was a capture from the real client beside one built with `--no-style`, where the
unstyled car has visible alloy spokes and the styled one has holes.

The fault is the choice of fallback rather than the trim row's numbers. Trim is *correct* for
unpainted plastic and it is a strong treatment: value clamped to 0.20, metallic driven to 0,
roughness to 0.78. Applied to an alloy wheel it removes every highlight that makes a spoke
readable; applied to two thirds of a vehicle it removes the vehicle.

Two changes. A **neutral** row is now the fallback — it clamps a cartoon's saturation, adds a
little dirt, and does nothing else. And `metallic` and `roughness` may be **null** in the style
table, meaning *preserve the artist's value*; neutral is the only row that uses it, and that is
what keeps a wheel a wheel.

## Rationale / Context
DISC-048 was the same mistake caught one layer earlier and fixed too narrowly: the tint floor
stopped the *texture* being multiplied to black, and nothing stopped the *material* being flattened
to matte. Both are the same root cause — a strong per-surface treatment applied to surfaces nothing
had actually identified.

## Impact
- `assets/materials/style.json` gains the `neutral` row; `trim`'s ceiling relaxes 0.20 → 0.30.
- Both cars re-exported: 41 neutral, 9 light, 4 chrome, 2 paint, 2 underbody, 2 interior.
- The lesson is procedural rather than numeric: a style pass is not verified by its tests. It is
  verified by rendering the car.
