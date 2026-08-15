# DISC-053: `image.copy()` does not copy the pixels, and reports pure white

**Date:** 2026-08-15
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.9

**Status:** active

## Summary
Measuring a texture's mean brightness by copying the image, scaling the copy to a thumbnail and
reading its pixels returns **1.0 for every image on both shipped cars**. The copy is lazy; scaling
something with no pixel data fills it white. `foreach_get` on the original reports what is in the
file — and the real values span 0.006 to 0.96.

## Details
Three separate traps in one measurement, and all three produce a plausible-looking wrong number
rather than an error:

- **The importer does not connect the image to Base Color.** It inserts a `Mix` so the base-colour
  factor multiplies the texture, so looking one link upstream finds a `MIX` node and no image. The
  search has to walk upstream.
- **`has_data` is `False` on a freshly imported image**, even though the file is perfectly readable.
  Gating on it skips every texture on the vehicle.
- **`image.copy()` copies the datablock, not the pixels.** `copy()` then `scale(16,16)` then
  `pixels` yields uniform 1.0. Touching `len(original.pixels)` first — which does force the original
  to load — does not help the copy.

`numpy.empty(len(image.pixels)); image.pixels.foreach_get(buffer)` is the read that works, bounded
by a pixel cap so a 4K texture does not materialise a quarter of a gigabyte for one number.

## Rationale / Context
The wrong answer was self-consistent and survived a first look: every texture reading exactly 1.0
is *plausible* for a model that carries its colour in base-colour factors, which this one partly
does. What gave it away was that every single material read the same value, including a tyre.

## Impact
- The tone band (DEC-079) is measured on real texture brightness rather than on a constant.
- The Eclipse's textures measure 0.006 to 0.96; treating them all as 1.0 left 41 of 60 materials
  outside the band with nothing correcting them.
