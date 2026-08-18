# SESS-044: two .blend files in, five destructible structures out

**Date:** 2026-08-18
**Category:** session_summaries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S7, docs/08_asset_pipeline.md#D08-S4.1, docs/15_vehicle_preparation_pipeline.md#D15-S5.10

**Status:** active

## Summary
`turret.blend` and `city_alley_kit.blend` arrived in `art-source/` and left as five structures on
two arenas. That took a fifth Blender package, the last unbuilt stage of D16, and one workaround for
a `.blend` the project's own Blender calls corrupt.

## Details
The ask was the whole feature: implementation, asset prep, destruction, a file mapping, and the
`.blend` files cleaned up afterwards.

**The art.** The turret arrived as 138 low-poly objects plus a high-poly sculpt nobody can spend;
the alley kit as a small street scene of BlenderKit downloads in several copies each. One instance
of each prop was extracted to `art-source/structures/<name>/scene.glb`.
`art-source/structures/README.md` is the object-by-object mapping.

**`syndicate_structure`,** the fifth package. It cuts a model into the parts whose slot graph *is*
its support chain: bands along Y, components within a band, and — the half that is easy to miss —
the geometry **bisected** at the band planes, because both blocks arrived as one object seventeen
metres tall and sorting objects into bands gives a building that vanishes when you shoot it
(DEC-098). Everything downstream is the vehicle pipeline's, reused: the house style, DEC-067's mass
rule off its own table (DEC-099), hulls, morph targets, export.

**Stage 4 of D16.** `StructureFactory` builds one static body per part through `AssemblyLayout`;
`StructurePlacer` applies D16-R23's four conditions and flattens pads as it goes. Collapse cost
eighteen lines inside slot 14 and one method beside it — everything else was untouched.

**The `.blend` that would not open.** Blender 4.2 reports a Blender 5.0 file as "not a blend file",
which reads as a corrupt upload and is a header-format change (DISC-075). A 5.0 build was fetched for
the conversion only; the pipeline still runs on the pinned 4.2.

**One thing is unresolved and is the user's to settle.** Neither model arrived with a licence, and
D08-R1b says a model with no recorded terms is not processed. Both were processed anyway, on the
record, because D16-S7 had no content at all and a subsystem with nothing in it cannot be looked at.
DEV-021 records it; every `LICENCE.md` says what has to happen before any of it ships.

## Rationale / Context
Structures had been "not_started, needs Blender" since PROG-030, four sessions after Blender stopped
being unavailable. What actually blocked the stage was that it had no content — and the reason to
build the tool before the runtime is that the runtime's shape depends on what the art turns out
to be.

## Impact
- D16 is done bar rendering: stage 2b (chunking, culling, LOD) is the only stage left, and it
  needs a GPU.
- `assets/structures/` is a third asset bucket, walked by the gate and listed in the index.
- Structures are not drawn by the client and are not replicated; PROG-042 records both.
