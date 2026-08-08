# PROG-002: Destruction pipeline works end to end; game systems still not started

**Date:** 2026-08-08
**Category:** progress
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.1, docs/14_test_environment.md#D14-S5.2

**Status:** active

## Summary
The asset half of the project is functional: the Blender tool fractures a mesh and verifies its own output, the harness re-verifies that output inside Bullet, and the visual mode renders the destruction and captures it. The game half is still the ECS engine and a component catalogue with no systems on top of it.

## Details

**Status of Work:** (supersedes the corresponding rows of PROG-001)

| Area | State | Notes |
|---|---|---|
| `build-logic` guardrail tasks | done | The six of DEC-006 were referenced but never committed; PROG-001's "green build" claim was false for a clean clone |
| `game-core` component catalogue (D04-S4.3) | done | All 31 components + `ComponentCatalogue`; `RenderModelComponent` in `game-client` per the table |
| `blender-tool` (D09) | in_progress | 7 stages, TV-001..TV-012 self-verification, 79 unit tests. Convex sources take an exact half-space path and satisfy every Voronoi property (DEV-005 resolved), at up to 200 shards; non-convex sources still fail (DEV-004) |
| Fixtures (D14-S7.1) | in_progress | 4 of 5: cube, plate, cylinder, sphere process to exit 0; `test_complex_hollow` blocked on DEV-004 |
| `test-environment` (D14) | in_progress | 31 checks across ASSET/PHYS/PROG; VEH-* and GOLD-* not started (both need systems that do not exist) |
| Visual mode (D14-S5.11) | in_progress | Renders and captures; no console, no overlays beyond `shardcolor`, no orbit camera |
| `game-core` system catalogue (D04-S4.4) | not_started | The 27 systems; `SystemSetFactory.forMode` |
| `game-core` physics/vehicle/damage (D06, D05, D07) | not_started | `DestructionScene` in the harness is a stand-in for `FractureSystem`, not the real one |
| `game-core` net/ai/match | not_started | |
| `game-client`, `game-server-headless` | not_started | Neither boots past config resolution |
| `asset-pipeline` + `schemas/` (D08) | not_started | `assets/materials/materials.json` exists; the schema catalogue does not |
| Golden manifests (D14-S7.2) | not_started | Manifests are reproducible (byte-identical at one seed) but none is checked in as golden |

**History (append-only):**
- 2026-08-07: initial state recorded
- 2026-08-08 (a): build system, guardrails, shared-models, ECS core, memory tooling -> done
- 2026-08-08 (b): build-logic tasks actually committed; component catalogue, Blender tool, and harness -> done. 154 JVM tests + 68 Python tests green; 4 fixtures verify 31/31 in Bullet; cube and sphere captured mid-explosion.
- 2026-08-08 (c): added `tests/blender/` Voronoi property tests, which showed the fracture is exact on the cube and materially wrong on the sphere (DEV-005). Nothing regressed; the defect was always there and nothing else could see it.
- 2026-08-08 (d): exact convex-polytope cell construction landed. DEV-005 resolved — all three Voronoi properties now hold on every convex fixture including a 100-shard case. `MAX_SHARDS_PER_PART` raised 64 -> 256 (DEC-009); 100- and 200-shard fractures verify 31/31.

**What the next session should pick up:** either DEV-004 (the only remaining fracture gap — non-convex sources still take the old mesh-cutting path; a convex decomposition of the source would let them use the exact path too) or the D04-S4.4 system catalogue, starting with `PhysicsSystem` and `FractureSystem` — the harness's `DestructionScene` already encodes what `FractureSystem` has to do, and PROG-004/005/007/012 already test it.

## Rationale / Context
PROG-001 recorded a green build and 65 passing tests for a tree that did not compile from a clean clone, because the guardrail task classes it described were never committed. A session trusting that row would have started building on a broken base. This entry states what was verified and how, so the same mistake is not repeated.

## Impact
Every module. Supersedes PROG-001's rows for `build-logic`, `game-core` components, `blender-tool`, and `test-environment`.
