# PROG-015: there is a world to fight in, and a gate on the content that fills it

**Date:** 2026-08-10
**Category:** progress
**Related Docs:** docs/08_asset_pipeline.md#D08-S5.2, docs/08_asset_pipeline.md#D08-S4.7, docs/03_runtime_modes.md#D03-S5.1

**Status:** active

## Summary
An arena ships and loads: `ServerRuntime` now ticks a world with a floor, walls, a kill plane and six
spawn points rather than an empty universe. `asset-pipeline` stops exiting 70 and became a real build
gate that walks `assets/`, cross-checks it against itself and writes `asset-index.json`. The split —
one whole-car model into five parts — is still what stands between the art and a car in the game.

## Details

**Scope:** `assets/`, `art-source/`, `schemas/`, `asset-pipeline`, `game-client`,
`game-server-headless`, and `game-core`'s `asset` package (DEC-023).

**Status of Work:** (supersedes PROG-013)

| Area | State | Notes |
|---|---|---|
| Headless glTF reader | done | PROG-013 |
| Collision geometry at load | done | PROG-013 |
| `assets/parts/` | in_progress | Six part types. None is a weapon, so nothing in the shipped content can fire; none authors a `covers` list, so nothing can be stripped |
| `assets/vehicles/` | in_progress | `vehicle_eclipse_01`, `vehicle_stampede_01` |
| `assets/arenas/` | done | `arena_scrapyard_01`: 300 × 300 m floor, walls, kill plane at −40 m, six spawn points, four modes. Deliberately empty — what goes in it is a design decision |
| `assets/balance/` | done | `classes.json` with light/medium/heavy targets. With one vehicle per class the A312 check confirms arithmetic rather than balance; it becomes real when a second lands |
| `ArenaFactory` | done | Floor and four walls generated from the arena's own bounds (DEV-014); same 0.9 friction the vehicles were calibrated against |
| Arena validation | done | A401, A402, A403 and A405 in `AssetLoader`; A105 on the directory name |
| `asset-pipeline` | done | `AssetIndexBuilder` + `PipelineMain`: identity (A101–A107), part semantics (A201–A214 subset), part↔manifest agreement (A202, A504–A506), assembly resolution (A301–A310 subset), arena (A402–A403), balance (A312, A314). Exit codes 0 / 64 / 66 / 67 |
| `:asset-pipeline:buildIndex` | done | Strict, and deliberately **not wired into `check`**: the parts declare `mesh.glb` files that do not exist, so a strict run is 12 × A107 until the split lands |
| `schemas/` | not_started | Empty. A102 is therefore never raised on either side; the hand-written structural checks stand in for it (DEC-041) |
| Collision meshes for parts | blocked | On the **split**, as PROG-013. `ShippedContent` still supplies box hulls in tests |
| `game-server-headless` | in_progress | Now loads and builds the arena at startup. Still no transport and no match bootstrap, so nothing spawns into it |
| Transport, match bootstrap, `game-client` | not_started | as PROG-013 |

**History (append-only):**
- 2026-08-10: `ArenaDef`, `AssetLoader.loadArena`, `InMemoryAssetIndex.arena`, `AssetIndex.arena`,
  `ArenaFactory`; `assets/arenas/arena_scrapyard_01/` and `assets/balance/classes.json`;
  `AssetIndexBuilder`, `Finding`, a real `PipelineMain` and `:asset-pipeline:buildIndex`;
  `ServerRuntime` loads the arena. `ShapeCache.hullFor` accepts the `PRIMITIVE` variant, which is
  what arena boxes are cached under. 262 `game-core` and 7 `asset-pipeline` tests green; `check`,
  `validateDocs` and `lintMemory` pass.

**What the next session should pick up:** still the **split** — one whole-car model into a chassis
part and four wheel parts, with a `<partTypeId>_col` hull node and the `dmg_25`…`dmg_100` morph
targets of D07-S5.5. Every measurement it needs is in each car's `SOURCE.md`. It is now the single
thing standing between the content and a vehicle that renders, and the pipeline says so out loud:
`buildIndex --strict` reports A107 for every declared-but-absent mesh, and will go quiet when the
split lands.

After that, and independently: `schemas/` so A102 becomes real, and a weapon part so the combat
systems have something in `assets/` to fire.

## Rationale / Context
PROG-013 said the blocker had moved from the reader to a Blender operation. It has not moved again —
this session went around it, building the parts of Phase 6 that do not need the split. Recording that
explicitly matters because the roadmap now shows Phase 6 as nearly complete, and a session reading
only the roadmap could conclude the art problem was solved. It is not; it is the same one, and it is
now the only one.

## Impact
`game-core` `asset` and `physics`, `asset-pipeline`, `assets/`, `game-server-headless`. Supersedes
PROG-013.
