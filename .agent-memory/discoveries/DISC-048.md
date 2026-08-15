# DISC-048: a base-colour socket behind a texture is a factor, not a colour

**Date:** 2026-08-15
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S4.5, docs/08_asset_pipeline.md#D08-S4.2

**Status:** active

## Summary
A Principled BSDF's Base Color socket means two different things. Unconnected it is the surface's
colour. Connected to an image it is the `baseColorFactor` the texture is **multiplied by** — and
writing a style band's value ceiling into it renders the whole vehicle at a fraction of its
brightness.

## Details
The first cut of the style pass restyled every material's base colour the same way. On the Eclipse,
60 of its 60 materials are textured, and 58 of them classify as `trim` — whose value ceiling is 0.20.
Applied as a colour that is a dark grey panel; applied as a *multiplier* it is 20% of the diffuse
map, which is a silhouette. The car would have shipped nearly black.

Caught by reading the run's own report rather than by a test: the style stage lists every material
with the surface it was given, and 58 rows of `trim` on a supercar is not a plausible answer to
"what is this made of" even before asking what the pass would do with it.

The fix is a separate operation for the textured case. `tint_for` computes what the style does to a
mid-grey and rescales it so it never darkens past `TINT_VALUE_MIN` (0.55). The hue shift, the
desaturation and the grime all survive — a textured car still goes warm and dusty — and its diffuse
detail survives with them.

## Rationale / Context
This is the failure mode this project's memory keeps recording: a plausible-looking wrong result. A
uniformly darkened car looks like a lighting choice, not a bug, and nothing in the pipeline
validates brightness. The only reason it was found before shipping is that the tool reports what it
decided and not merely that it succeeded.

## Impact
- `style.restyle` for an untextured material, `style.tint_for` for a textured one.
- D15-R47d records the distinction as a requirement rather than as a code comment.
