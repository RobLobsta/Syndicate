# PROG-036: the arena — themed terrain, roads, and what the ground is made of

**Date:** 2026-08-17
**Category:** progress
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.4, docs/16_procedural_arena_generation.md#D16-S5.10, docs/16_procedural_arena_generation.md#D16-S7

**Status:** active

Supersedes: PROG-030

## Summary
**Stage 3 of D16's four is done.** A road is carved from a spline, cut and fill fall out of the
falloff, and the surface under each wheel now changes that wheel's grip — so leaving the road is a
decision rather than a cosmetic difference. The desert ships a 612 m highway. Structures (stage 4)
and rendering proper (stage 2b) remain.

## Details

**Scope:** `game-core` `arena` (`RoadSpec`, `RoadCarver`, `GroundStyle`), `vehicle` (`VehicleControl`),
`physics` (`PhysicsWorld`, `ArenaFactory`), `asset` (`ArenaDef`, `AssetLoader`).

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Stage 1 — generator, collision, queries | done | Unchanged from PROG-030 |
| Stage 2a — ground drawn | done | Unchanged from PROG-030 |
| Stage 2b — rendering proper (D16-S6) | not_started | Chunking, culling, LOD, textures, analytic sky. Needs a real GPU |
| Stage 3 — road carving (D16-S5.4) | done | Catmull-Rom, Gaussian profile, two-pass grade limiter, falloff |
| Stage 3 — per-surface grip (D16-S5.10) | done | Read at the suspension ray's contact point, in the shared control operation |
| Stage 4 — structures (D16-S7) | not_started | Needs Blender for fracture manifests; unavailable in this sandbox |
| Ground in the house style | done | `GroundStyle`, DEC-079's two mechanisms applied to theme albedo |
| Shipped roads | done | Both arenas ship one. The scrapyard's 235 m haul road landed with DEC-097's guard |
| Road extent guard (DISC-062's ask) | done | `validateExtent`, seed-independent, measured against each arena's own rim |

**The desert highway** measures 612 m, 8,692 carriageway cells and 3,872 of verge, cutting about 4–5 m
and filling 2–5 m depending on the match's seed, holding its 6% grade. That cut and fill is the whole
point (D16-R36): it arrives as banks to be pushed into and ridges to be pushed off, and nothing
authored it.

**Per-surface grip** multiplies the wheel's friction slip by `Surface.gripMultiplier` — tarmac 1.00,
rock 0.88, gravel 0.72, sand 0.55 — looked up per wheel per tick at the suspension ray's contact
point, so a car straddling a road edge has two wheels on tarmac and two on sand. It is applied in
`VehicleControl` and therefore replays identically under reconciliation (DEC-061). The surface is also
recorded on `WheelControllerComponent` so slot 25 selects its tyre loop from the same lookup the
physics used, which D16-R56 requires and two independent lookups would eventually violate.

**The ground now goes through the vehicles' style table.** The desert's authored albedo measured 0.561
Rec. 709 luma against a 0.409 ceiling for ground, and read as noticeably lighter than the cars on it.
`GroundStyleTest` fails if the constants and `style.json` drift apart.

**Not done, and now measured:** DISC-062 (a road reaching the border rise digs a 31 m canyon) and
DISC-063 (a car at 40 m/s launches off a dune and the speed clamp holds it airborne). Both were found
by driving, not by testing.

**Update, 2026-08-18.** DISC-062's ask is built (DEC-097): `validateExtent` rejects a road before
the carve, measuring the rim height under its centreline. The scrapyard's withdrawn haul road now
ships — 235 m, cutting 3.6 m, across twelve seeds.

## Rationale / Context
PROG-030 recorded stage 3 as "the surface grid exists and is populated; the carve and the wheel read
do not". Both now exist. What that entry could not have said, because nobody had driven the arena, is
that the two most interesting facts about this terrain are only visible from a car.

## Impact
- `arena.json` gains an optional `roads` array (D16-S4.3); every arena without one is unchanged.
- `PhysicsWorld` now owns the `TerrainField`, which is the seam the wheel read needs.
- A road's cut and fill are logged per load, because they are the numbers that go wrong silently.
