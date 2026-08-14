# PROG-025: the ground is generated, collides, and can be driven on

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.1, docs/16_procedural_arena_generation.md#D16-S5.8, docs/08_asset_pipeline.md#D08-S4.7

**Status:** superseded (by PROG-030)

## Summary
Stage 1 of D16 is implemented: an arena with a `terrain` block generates a height field from its
seed, hands it to Bullet as one static body, and answers height, slope, surface and drivability
queries. A desert arena ships. Nothing draws it yet, no road is carved, and no structure exists.

## Details

**Scope:** `dev.syndicate.core.arena`, the height field entries in `ShapeCache`, and
`ArenaFactory`'s terrain path. Stage 1 of the four in `ROADMAP.md` §3.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| 1 Base relief (D16-S5.1) | done | Hashed gradient fBm, no state, no trigonometry |
| 2 Dune layer | done | Slip width solved from repose, not fixed (DISC-044); passes (DISC-045) |
| 3 Border rise | done | Replaces the four walls; containment checked, not assumed (R58a) |
| 4 Road carve | not_started | Stage 3 of the build |
| 5 Pads | done | Spawn clearances; structure footprints join the same pass at stage 4 |
| 6 Surface classify | done | 69% sand, 23% rock, 8% gravel on the shipped arena |
| 7 Drivability | done | 73.5% drivable, 94% of it one region |
| 8 Connectivity check | done | Hard failure at load, including a leaky rim |
| 9 Collision | done | One `btHeightfieldTerrainShape`; both Bullet traps verified |
| 10 Structure placement | not_started | Stage 4 of the build |
| Rendering (D16-S6) | not_started | Stage 2; untestable in this sandbox (DISC-024) |
| Per-surface grip (D16-S5.10) | not_started | Stage 3; the surface grid it needs exists |

`dev.syndicate.core.arena` (new, D02-S4.7 amended) holds four classes: `TerrainNoise` (hashed
gradient noise and fBm, no state and no trigonometry), `TerrainParams` (the arena block),
`TerrainGenerator` (the D16-S5.1 stages that stand alone) and `TerrainField` (the grids and every
query over them). `ShapeCache` gains a `HEIGHTFIELD` variant owning both the shape and the direct
buffer Bullet reads through; `ArenaFactory` builds either generated ground or the old floor and walls.

The shipped `arena_desert_01`, 601 × 601 samples over 600 m, generates in **187 ms**. Relief runs
−4.7 m to +50.6 m above `groundY`; 73.5% is drivable and 94% of that is one connected region;
surfaces come out 69% sand, 23% rock, 8% gravel; dune slip faces measure a mean of 32.5° and a 90th
percentile of 34.6° against a 33.0° target; and a downward ray finds the ground to within 6.8 µm over
130 casts.

**Both Bullet traps D16 predicted are real and are now verified rather than asserted.** The shape
borrows the caller's height buffer instead of copying it, and it centres itself on the midpoint of
its own height range rather than on either end — placing a body at `groundY` would put the collision
17 m from the drawn surface on this arena.

**The ray accuracy figure retires DISC-017 for the ground.** Against the flat arena's alternative — a
600 m convex box — the same cast came back up to 0.14 m off, differently every tick. A height field
measures 6.8 µm, four orders of magnitude better, because it is ray-tested per triangle rather than
by an iterative convex cast.

## Rationale / Context
Two gameplay properties nobody would have got right by reading were found by measuring, and both are
recorded: the dune profile as specified made every face a ramp (DISC-044), and fixing that partitioned
the arena into 42 corridors (DISC-045). Both were caught by checks written before the code they check.

## Impact
- 387 tests in `game-core`, 0 failures; 19 of them are new and 5 exercise real Bullet.
- `assets/arenas/arena_desert_01/` ships but is **not** the default: the client still draws the flat
  arena's floor and walls, which is stage 2.
- Not implemented from D16: road carving (S5.4), per-surface grip at the wheel (S5.10), all rendering
  (S6), all structures (S7). The generator's stage list has the two missing generation stages in
  exactly the place they will go.
