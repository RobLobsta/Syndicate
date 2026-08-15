# SESS-034: a palette, a tone band, and a car that stops bottoming out

**Date:** 2026-08-15
**Category:** session_summaries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S4.5, docs/15_vehicle_preparation_pipeline.md#D15-S5.8

**Status:** active

## Summary
The house style is retargeted at Crossout's wasteland and gains the two rules that make a roster
cohere rather than merely making each surface plausible: a six-hue palette snap and a global
luminance band, both applied to every surface and neither scaled by strength (DEC-079). The garage's
wheels sat a rest length too high and now do not (DISC-054). And the client's runbook is written
down.

## Details
**Style.** `style_syndicate_wasteland`: rust, sand, sodium, faction red, gunmetal and Ravager cyan,
with everything clamped into [0.03, 0.62] luminance and 0.55 saturation. Lamps are the only exempt
surface, and only from the ceiling and the palette. The band is measured on what a surface will
actually render as — texture mean times factor — which needed the texture measuring, which needed
three Blender traps working around (DISC-053). The Stampede finishes with 0 of 24 materials outside
the band; the Eclipse with 2 of 60, both textures too dark for any multiplier to lift.

**Ride height.** The user spotted the Eclipse's wheels sitting in its arches from a screenshot. The
garage places parts at their slot transforms and a wheel's slot is the suspension connection point,
so with no Bullet world every wheel was drawn 22 cm high. `VehicleFactory.staticSagM` is now the one
place that arithmetic lives, and a content test asserts the whole chain lands the tyre on the ground.

**The runbook.** `RUNNING_THE_CLIENT.md` at the repository root, with the JitPack workaround, the
xvfb invocation, the capture options and the `--assets` trick for comparing two builds of the
content. CLAUDE.md now points at it and says that looking at the game counts as a check.

## Rationale / Context
Both defects this session were found by a person looking at a picture, one of them by the user. The
style pass and the garage each had passing tests over them.

## Impact
- `check validateDocs` green including `game-client`; both vehicles re-exported.
- The generated terrain is still not style-normalised and reads lighter than the vehicles — flagged
  for the user rather than changed, because that palette belongs to D16.
