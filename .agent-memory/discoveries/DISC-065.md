# DISC-065: the shipped parts fail fracture at two gates, and the second is mass

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.2, docs/08_asset_pipeline.md#D08-S4.3, docs/00_master_index.md#D00-S5.2

**Status:** active

## Summary
44 of the 53 shipped vehicle parts have no fracture manifest, and with Blender now available
(DISC-064) the reason is measurable. There are **two** gates, and the first hides the second.
Getting past materials is trivial; mass conservation on real part geometry is not.

## Details

**Gate one — the mesh carries the art's material name.** Fracture over a real part exits 67
`MATERIAL_UNRESOLVED`:

    wheel_eclipse_front_01   material 'wheel1a_d.001' is not in the material table
    panel_eclipse_door_l_01  material 'bw00.002'      is not in the material table

Those are the **downloaded art's** texture-material names, still on the exported mesh. The matching
`part.json` already holds the right answer — `materialId: rubber`, `materialId: steel` — because
`syndicate_prepare` resolved it during classification and never wrote it back onto the exported
mesh's material slot. Glass is the one category with shards today precisely because the glass path
does assign a real `glass` material.

D09-R19 is right to hard-fail rather than default to steel. `syndicate_fracture` already has
`--material-override`, so nothing needs adding to the tool to get past this — but a mesh whose
material slot lies is still wrong for the runtime, so the export is where it should be fixed.

**Gate two — mass conservation, and this is the actual work.** With the material supplied, both
parts reach the fracture and fail G7 (`MASS_TOLERANCE_FRAC` = 0.02) with exit 72:

    wheel_eclipse_front_01   shard sum  81.16 kg vs part  64.21 kg   +26.4%
    panel_eclipse_door_l_01  shard sum 233.01 kg vs part 225.48 kg    +3.3%

Both **over**-count, the signature of an open or self-intersecting source. A car door is a *sheet*,
not a solid. The fixtures the fracture was built and tuned against — `test_cube_1m`,
`test_sphere_r0.5`, `test_cylinder_r0.5_h1` — are all closed solids.

`--shell-thickness` exists for exactly this and does not currently rescue it: 0.002 and 0.006 both
move the door to a *different* self-verification failure (exit 74) rather than to a pass. So either
the shipped part geometry needs closing or the shell path needs work, and which is not yet
established.

## Rationale / Context
The tempting reading of "44 parts have no manifest" is that somebody forgot to run a batch command.
It is not: it is a two-gate content problem, and the second gate is a real question about whether
this fracture handles sheet geometry at all. Recording it stops the next session budgeting an hour
for what is at least a session.

DISC-041's lesson applies with force — every threshold in this tool was measured against five closed
convex-ish fixtures, and the shipped content is neither closed nor convex.

## Impact
- `syndicate_prepare` should write the resolved `materialId` onto each exported part's material slot.
- Until gate two is answered, only glass can be fractured, so a destroyed wheel or door detaches
  whole (the gap PROG-028 names).
- A sheet-geometry fixture belongs in `fixtures/meshes/`; its absence is why this was never seen.
