# DISC-076: bmesh's bisect clears the side you meant to keep

**Date:** 2026-08-18
**Category:** discoveries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S7.1, docs/09_blender_destruction_tool.md#D09-S5.2

**Status:** active

## Summary
In `bmesh.ops.bisect_plane`, **`clear_inner` removes what is on the negative side of the plane
normal and `clear_outer` removes the positive side.** Reading them as "keep inner / keep outer", or
as inner-means-below, produces bands holding their neighbours' geometry and a middle band that is
empty — a failure that is silent, plausible and only visible if you print the bounds.

## Details
Cutting a building into floors at planes `y = e1, e2` means, for band *k*, keeping what is above
`e[k]` and below `e[k+1]`. With a `+Z` plane normal in Blender (game Y), that is:

```python
# keep what is above the plane
bmesh.ops.bisect_plane(..., plane_no=Vector((0, 0, 1)), clear_inner=True,  clear_outer=False)
# keep what is below it
bmesh.ops.bisect_plane(..., plane_no=Vector((0, 0, 1)), clear_inner=False, clear_outer=True)
```

Written the other way round, a 17.12 m block cut at 5.71 and 11.42 produced this:

```
Cube.012__b0   y  5.71..16.88     <- should have been 0.00..5.71
Cube.012__b1   (empty, dropped)
Cube.012__b2   y  0.00..11.42     <- should have been 11.42..17.12
```

Bands 0 and 2 held each other's geometry and band 1 was cut away from both sides into nothing. Every
document the tool wrote was well-formed; the masses were plausible; the part count was one short and
nothing said why.

The second half of the operation is not optional either: `bisect_plane` leaves the cut open, so
`bmesh.ops.holes_fill` on `result["geom_cut"]`'s edges has to cap it. An uncapped floor encloses no
volume, and the enclosure is what caps a part's mass (DEC-099) — so the missing cap does not crash,
it silently doubles what a floor weighs.

## Rationale / Context
The fix is one boolean and the diagnosis was twenty minutes of printing vertex bounds, which is
exactly the shape of thing this file exists for: the diff that fixed it is `clear_inner=keep_above`,
and nothing about that diff says what it was wrong about.

## Impact
- `syndicate_structure/split.py` carries the rule in a comment at the call site.
- A unit test asserts the band spans (`spans()`), which is the arithmetic half; the geometric half
  is only checkable inside Blender, so the comment is what protects it.
