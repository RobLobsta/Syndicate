# DISC-066: every shipped part is an open mesh, and only glass was ever meant to fracture

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.7, docs/09_blender_destruction_tool.md#D09-S5.1, docs/00_master_index.md#D00-S5.2

**Status:** active

Supersedes: DISC-065

## Summary
All **53** shipped vehicle parts are non-watertight surface meshes — not one encloses a volume. That
is normal for downloaded car art, and it is why the fracture's solid path cannot touch them. It is
*not* why 44 have no fracture manifest: D15-S5.7 gives `fractureShards` to `glass` alone (D15-R32).
The 44 are the spec working as written.

## Details
Surveyed with the tool's own `boundary_edge_count` over every `part.json` in `assets/vehicles/`:

    watertight (solid path):   0 of 53
    open       (shell path):  53 of 53
    with a fracture manifest:  9  — every one of them glass

D15-S5.7's table is explicit. `sheet_metal` subdivides and gets damage shape keys; `glass` gets no
shape keys and cell-fractures; `structural` buckles; `rigid` "detach[es] whole, fracture only if
authored". `syndicate_prepare` implements exactly that — its caller gates on
`treatment_for(part.label).fracture_shards`, and only `GLASS` is non-zero (24).

So PROG-028's "a destroyed wheel detaches whole instead of breaking up" describes the design.
Extending fracture past glass is a **content decision** needing D15-S5.7 amended first.

**What was genuinely broken, found by taking the solid path over real parts:**

1. **Watertightness was never checked.** D09 has had exit 66 for "Mesh not watertight" from the
   start; nothing implemented it. `_validate_source` only rejected `volume <= 0`, and `mesh_volume`
   returns `abs()` of the divergence integral — so both cars' wheels, integrating to **−0.058 m³**,
   arrived at mass assignment as +0.058. The symptom was exit 72 blaming mass, three stages later.

2. **The check must weld by position.** Counting boundary edges on vertex *indices* measures glTF's
   per-normal vertex splitting, not holes: a closed cube imports as 24 unshared edges. Written that
   way it rejected all five of the tool's own fixtures. Quantised positions give 0 for the cube and
   1299 for the wheel.

3. **Re-fracturing an already-dented part duplicated its morphs.** `shape_key_add` does not
   overwrite — given an existing name it appends `.001` — so a door exported eight morph targets
   against a manifest promising four. TV-006 caught it and blamed the exporter.

## Rationale / Context
DISC-065 read "44 parts have no manifest" as a defect and hunted the blocker. Its measurements were
right and its conclusion was wrong, which is the expensive kind of entry: the next session would
have spent a day making sheet metal shatter against a spec saying it should dent. Reading D15-S5.7
before the fourth diagnostic would have cost nothing.

## Impact
- `geometry.py` gains `boundary_edge_count` / `is_watertight`; `pipeline.py` raises exit 66 naming
  the hole count and pointing at `--shell-thickness`.
- `morphs.py` removes its own `dmg_*` keys first, so a re-run is idempotent.
- Any future "make X fracture" starts by amending D15-S5.7, not by running the tool.
