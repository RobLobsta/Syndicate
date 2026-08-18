# PROG-042: destructible structures — the last stage of D16

**Date:** 2026-08-18
**Category:** progress
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S7, docs/16_procedural_arena_generation.md#D16-S4.7, docs/08_asset_pipeline.md#D08-S4.1

**Status:** active

Supersedes: PROG-036

## Summary
**Stage 4 of D16's four is done.** Five structures ship, both arenas place them from rules, and
shooting the bottom out of one drops what stood on it — through `DetachSystem` alone, with no new
system, no new schedule slot and one new component that no system reads (D16-R80, D16-R81).

## Details

**Scope:** a fifth Blender package (`syndicate_structure`), `game-core` `structure`
(`StructureFactory`), `arena` (`StructurePlacer`), `asset` (`StructureDef`,
`StructurePlacementRule`, the loader and index), `physics` (`ArenaFactory`), `system`
(`DetachSystem`), and `asset-pipeline` (the index's `structures` array).

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Stage 1 — generator, collision, queries | done | Unchanged from PROG-036 |
| Stage 2a — ground drawn | done | Unchanged |
| Stage 2b — rendering proper (D16-S6) | not_started | Chunking, culling, LOD, textures, analytic sky. Needs a real GPU |
| Stage 3 — roads, per-surface grip | done | Unchanged |
| Stage 4 — structures (D16-S7) | done | Five shipped, both arenas place them, collapse works |
| `syndicate_structure` (the cut) | done | Bands + components + bisection (DEC-098), 366 Python unit tests green |
| `StructureFactory` (D16-R77) | done | One static body per part, placed through `AssemblyLayout` |
| `StructurePlacer` (D16-S5.7) | done | Four placements, D16-R23's four conditions, pads flattened during the pass |
| Collapse (D16-R78/R79/R80) | done | T1 covers every part but the root; `collapseTrigger` covers the root |
| Structures drawn by the client | not_started | The client renders vehicles and terrain; structures are not in its render pass yet |
| Structures replicated (D16-S7.3) | not_started | D10-S4.3 says structures join the replicated set; nothing sends them |

**The five structures**, from two `.blend` files that no longer exist (see
`art-source/structures/README.md` for the full mapping):

- `str_rocket_turret_01` — a four-legged rocket emplacement, 31 m tall, with a built-in `ROCKET`
  weapon on its pods. 5 parts, 411 t.
- `str_city_block_low_01` — a six-storey concrete block, 17 m. 3 parts, 336 t.
- `str_city_block_tall_01` — a seven-storey stylised block, 22 m. 4 parts, 370 t.
- `str_street_tree_01` — a street tree. 1 part, 130 kg.
- `str_street_bench_01` — a park bench. 1 part, 77 kg.

**What "no new system" cost, exactly.** One addition inside slot 14 and one alongside it. A structure
part's body is zero-mass and `STATIC` while its chain holds, so `embodyDetachedParts` had to learn to
*retire* such a body rather than treat it as an already-dynamic articulated part — that is D16-R79's
"the layer transition", eighteen lines. And the **root** part is the one part with no
`SlotAttachmentComponent`, so T1 never sees it; `collapseTrigger` is D07-S5.7's T4 with the vehicle
taken out of it. Everything else — damage, fracture, debris, lifetime, teardown — was untouched.

**Three vehicle-only validation rules do not apply to a structure** and are now skipped by kind
rather than by a flag: A301 (the root must be a `CHASSIS`), A309 (three wheels or a rotor) and A311
(the centre of mass must match what the file asserts). D16-R19c records the reasoning in the spec.

## Rationale / Context
PROG-036 recorded stage 4 as blocked on "needs Blender for fracture manifests; unavailable in this
sandbox". That was already retired by SESS-038 and the entry had not caught up; what actually
blocked it was that nobody had tried, and what it needed was content.

The one thing this could not have found without art was the bisection (DEC-098): the turret arrived
as 138 objects and cut correctly with no bisection at all, and the buildings arrived as one object
each and did not.

## Impact
- `arena.json` gains an optional `structures` array; an arena without one is unchanged.
- `assets/structures/` is a third bucket the asset gate walks, and its parts are owned by their
  structure exactly as a vehicle's are (D16-R19a, DEC-075).
- Nothing in `assets/structures/` may be distributed yet — DEV-021.
