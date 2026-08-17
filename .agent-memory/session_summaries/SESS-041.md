# SESS-041: the transform split — two tools, one rule, and something that enforces it

**Date:** 2026-08-17
**Category:** session_summaries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.3, docs/15_vehicle_preparation_pipeline.md#D15-S5.7, docs/08_asset_pipeline.md#D08-S5.4

**Status:** active

## Summary
The six defects SESS-040 found in the Blender suite are fixed. `syndicate_fracture` authors the
FRACTURE transform and nothing else, `syndicate_deform` is a new package for DEFORM, both refuse a
destruction class D15-S5.7 does not give their transform, every manifest declares what it is, and
the asset gate rejects one that disagrees with its part.

## Details

**The split (DEC-088).** Two packages rather than one tool with a mode flag: a flag leaves both code
paths in one pipeline, which is what let them run together for eleven sessions.
`--destruction-class` is required on both — a tool that assumes cannot refuse — and a class that
does not receive the transform is exit **77**. The old flags still parse and now exit 64 naming the
other tool.

**A correction.** The README had proposed renaming the package to `syndicate_shatter`. D00-S6
already defines *fracture* and *deformation* as exactly these two transforms — the package was
always named correctly and simply did a second thing its name did not cover. No renames.

**The declaration (DEC-089).** Both manifests carry `transform` and `destructionClass`, and a new
gate rule **A510** pairs them against `part.json`. The tools refuse at authoring time and the gate
at build time, independently: a manifest that arrived by hand never passed a tool's check, and the
runtime does not look.

**The two dead flags.** `--verify-only` promised to produce no new data and performed a destructive
overwrite; it now re-reads the manifest in `--out`, checks schema, G7, the shard set and the
declared transform, and never starts Blender. `--keep-blend` writes a `.blend`. Exit codes are one
table: 64-79 shared, 80-89 weapon, 90-99 vehicle.

**Verified by running it.** Both fixture gates run; `verifyFixtures` is 31/31 on all five, with
`ASSET-007` flipped to assert a fractured part declares *no* morphs. Both shipped cars re-prepared
into a scratch tree and diffed: 27 and 26 parts, **zero drift**, none with both transforms. The gate
runs strictly over `assets/` with no A510, both refusals were exercised at the command line, and
`--verify-only` was shown to catch a tampered manifest and leave the directory untouched.

**One real bug found by running it.** The deform pipeline read `obj` after the export's re-import,
which resets the scene (D09-R15) and raises `ReferenceError: StructRNA of type Object has been
removed`. Everything read off a live object now happens before the export.

## Rationale / Context
Regenerating the eight shipped manifests also moved every `topologyHash` — Blender 4.2.0 to 4.2.13,
not the split. DISC-069 records it so the next upgrade's diff is not read as a regression.

## Impact
`blender-tool/` (2 new packages, 1 stage removed, 43 new tests), `asset-pipeline/` (A510),
`test-environment/` (ASSET-007), `assets/` (8 manifests), D08 and D09 amended in the same commit.
`./gradlew check` green; 343 Python tests; both vehicles and the weapon tool run end to end.
