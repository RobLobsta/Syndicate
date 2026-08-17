# DISC-065: the shipped parts fail fracture at two gates, and the second is mass

> **Superseded by DISC-066.** The measurements below are correct; the framing is not. Only
> `glass` is *meant* to fracture (D15-S5.7), so the 44 parts without manifests are the spec
> working as written, not a gap. Kept as the record of how the wrong conclusion was reached.

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.2, docs/08_asset_pipeline.md#D08-S4.3, docs/00_master_index.md#D00-S5.2

**Status:** superseded (by DISC-066)

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

D09-R19 is right to hard-fail rather than default to steel; `--material-override` already gets past
it. A mesh whose material slot lies is still wrong for the runtime, so the export is where it
should be fixed.

**Gate two — mass conservation, and this is the actual work.** With the material supplied, both
parts reach the fracture and fail G7 (`MASS_TOLERANCE_FRAC` = 0.02) with exit 72:

    wheel_eclipse_front_01   shard sum  81.16 kg vs part  64.21 kg   +26.4%
    panel_eclipse_door_l_01  shard sum 233.01 kg vs part 225.48 kg    +3.3%

Both **over**-count, the signature of an open or self-intersecting source. A car door is a *sheet*,
not a solid. The fixtures the fracture was built and tuned against — `test_cube_1m`,
`test_sphere_r0.5`, `test_cylinder_r0.5_h1` — are all closed solids.

`--shell-thickness` exists for exactly this. (DISC-066: it does work; the exit 74 seen here was the
duplicate-morph bug, not the shell path.)

## Rationale / Context
The measurements here are sound and worth keeping. The conclusion drawn from them was not: see
DISC-066.

## Impact
- `syndicate_prepare` should still write the resolved `materialId` onto each exported mesh's slot.
- A sheet-geometry fixture belongs in `fixtures/meshes/`; its absence is why this was never seen.
