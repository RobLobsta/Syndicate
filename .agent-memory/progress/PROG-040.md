# PROG-040: the Blender suite — one tool per transform, and a rule that is enforced

**Date:** 2026-08-17
**Category:** progress
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.1, docs/15_vehicle_preparation_pipeline.md#D15-S5.7, docs/08_asset_pipeline.md#D08-S5.4

**Status:** active

Supersedes: PROG-029

## Summary
Six Python packages under headless Blender 4.2. Each transform tool authors **one** transform and
refuses a destruction class D15-S5.7 does not give it; every manifest declares what it is; and the
asset gate rejects a manifest that disagrees with its part. Both shipped vehicles reproduce
byte-for-byte through the pipeline after the change.

## Details

**Scope:** `blender-tool/`, `asset-pipeline/`, `test-environment/`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| `syndicate_policy` — the class/transform and exit-code tables | done | Pure Python, no `bpy`; imported by everything (DEC-088) |
| `syndicate_fracture` — FRACTURE only | done | Voronoi solid and shell paths, mass, hulls, export, self-verify |
| `syndicate_deform` — DEFORM only | done | Subdivide, four morphs, export, self-verify, own manifest |
| Transform refusal at authoring time | done | `--destruction-class` required; exit 77 both ways |
| Transform declared in every manifest | done | `transform` + `destructionClass` (DEC-089) |
| Transform checked at the gate | done | A510, four branches, three tests |
| `--verify-only`, `--keep-blend` | done | Were parsed and ignored; `--verify-only` overwrote what it checked |
| Exit codes | done | One table: 64-79 shared, 80-89 weapon, 90-99 vehicle |
| `syndicate_prepare` — model in, ~27 parts out | done | Both shipped cars reproduce with zero drift |
| `syndicate_weapon` — model in, sub-parts out | done | Unchanged but for its exit codes |
| `syndicate_dissect` — shared model/mesh library | done | Legacy name, deliberately not renamed |
| Fixture gates | done | `processFixtures` (FRACTURE) and `processDeformFixtures` (DEFORM), separate outputs |

**What was wrong.** The fracture tool ran both stages unconditionally, by default, on any mesh, so
the documented D09 invocation produced a part that both dented and shattered — and the fixture gate
did exactly that to five steel fixtures while reporting success. Nothing downstream checks: a part
dents because its mesh has shape keys and shatters because it declares a manifest, and
`DestructionClass` had one runtime consumer, `AudioSystem`, picking a break sound (DISC-068). The
only reason no shipped part carried both was that `syndicate_prepare` remembered to pass
`damage_morphs=0`.

**Verified end to end, not inferred.** Blender 4.2.13 installed; both fixture gates run;
`:test-environment:verifyFixtures` passes 31/31 on all five fixtures with `ASSET-007` now asserting
that a fractured part declares *no* morphs; both shipped vehicles re-prepared into a scratch tree
and diffed against `assets/` — 27 and 26 parts, zero drift, no part with both transforms; the
asset gate run strictly over `assets/` with no A510; the refusals exercised at the command line in
both directions; `--verify-only` shown to catch a tampered manifest and to leave the directory
unchanged.

## Rationale / Context
The previous entry described one tool that "decomposes a mesh into seeded Voronoi shards **with
damage-state shape keys**" — an accurate description of a tool doing two jobs, written without
noticing that no destruction class wants both. What a session needs from this entry is which tool
authors what, and where the rule is enforced, because the answer used to be "nowhere".

## Impact
- A session adding a transform adds a package and a row in `syndicate_policy`, not a stage.
- A session changing D15-S5.7's table changes one dict, and both tools and the gate follow.
- `blender-tool/README.md` is the suite's design document: the transform model, the six findings and
  what was done about each, and what was deliberately left (DEC-088 §7).
- Still open: `syndicate_dissect` and `syndicate_prepare` keep misleading names, the low-level
  modules `syndicate_deform` borrows still live in `syndicate_fracture`, and the runtime still does
  not check a class before fracturing.
