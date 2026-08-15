# SESS-032: parts belong to their vehicles, and the roster looks like one game

**Date:** 2026-08-15
**Category:** session_summaries
**Related Docs:** docs/08_asset_pipeline.md#D08-S4.6, docs/15_vehicle_preparation_pipeline.md#D15-S4.5, docs/15_vehicle_preparation_pipeline.md#D15-S5.10

**Status:** active

## Summary
Four asks, all delivered: vehicle parts move under the vehicle that owns them, the five-part
chassis-and-wheels models are retired for the pipeline's real output, every source material is
normalised into a house style before anything measures it, and the vehicle system is ready for
weapons — built-in ones derived from a model's own geometry, modular ones fitted to hardpoints every
vehicle now offers.

## Details
`assets/parts/` becomes the shared library and `assets/vehicles/<id>/parts/` holds what one car is
made of, with a `manifest.json` beside it describing every part and its stats (DEC-075). The Eclipse
and the Stampede ship as 27 and 26 real parts instead of five each.

Style normalisation is stage 1b of the preparation pipeline: a nine-role table in
`assets/materials/style.json`, applied per material, before the geometry is corrected — because
every later stage destroys the materials it needs (DEC-076). A cartoon source is reskinned outright;
a photoscan is pulled toward the palette and keeps its textures.

Weapons: the `weapon` block is finally *read* by the loader, modules get the same treatment through
a new `ModuleFamily`/`ModuleBlock` pair, and every prepared vehicle offers a turret mount and four
hardpoints derived from its own body box (DEC-077).

Shipping the pipeline's output is what made it testable, and it turned up four defects that the
hand-cut content had been hiding: base colour behind a texture is a multiplier and the cars would
have shipped nearly black (DISC-048); wheel slots were placed at full droop so both cars sat 8 cm
into the road (DISC-049); a wheel measured from the shells that voted for it is not where the wheel
is, which cost the Stampede a phantom fifth wheel and a crooked stance (DISC-050); and the loader
read an empty `stats: {}` as a decorative part declaring stats.

## Rationale / Context
The user's framing was the design: car parts are not interchangeable, weapons are, and the layout
should say so. Everything else followed from taking that literally.

## Impact
- `./gradlew check validateDocs` green; 53 parts, 2 vehicles, 0 blocking findings in the index.
- D08 gains R14b/R14c and five validation codes; D15 gains S4.5, S4.6, S5.9, S5.10 and R45b–R50b.
- `syndicate_dissect`'s CLI is gone — the five-part output it existed to produce is retired.
