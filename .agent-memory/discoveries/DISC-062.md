# DISC-062: a road that reaches the border rise digs a canyon through the arena wall

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.4, docs/16_procedural_arena_generation.md#D16-S5.5

**Status:** active

## Summary
The first desert highway spline ran corner to corner, ±290 m in a 600 m arena. The carve cut **31 m
deep**, straight through the border rise that exists to contain the arena, and every one of the eight
candidate seeds was then rejected. Pulled inside the playable area at ±235 m, the same road on the
same landform cuts **4 m** and fills **3 m** — which is exactly the cutting and embankment D16-R36
promises.

## Details
The border rise (D16-S5.5) is a rim that climbs to ~36 m at the arena edge. A road's profile is
sampled from the ground, smoothed over a 25 m sigma and then grade-limited, so a spline whose ends sit
on the rim produces a profile that climbs 36 m over its last stretch, gets flattened by the limiter,
and then blends the rim down to meet it. The falloff does the rest.

**Measured, on seed 12345 with a 6% grade budget:**

| Spline extent | Max cut | Max fill |
|---|---|---|
| ±290 m (into the rim) | 30.8 m | 23.2 m |
| ±240 m | 3.3 m | 3.4 m |
| ±200 m | 2.7 m | 3.3 m |

Note the cliff between the first row and the second. This is not a gradient that a slightly-too-long
spline degrades along; it is a threshold at the foot of the rim.

**What made it expensive to find** is that no check names the cause. Two different guards fire
depending on the seed, and neither mentions roads:

- `D16-R58 / A411` — "spawn point cannot be reached from … over drivable ground", because the canyon
  partitions the arena. This is DISC-045's failure mode reached by a new route.
- `D16-R38` — "drivable ground reaches the arena edge; the border rim does not contain the playable
  region", because the cut *is* a drivable corridor through the rim.

Both are correct and both are three stages downstream of a road whose spline was 50 m too long.

The two shipped arenas also differ in cell size — the desert is 601 samples at 1 m, the scrapyard 301
at 2 m — so a spline extent verified on one says nothing about the other. The scrapyard's road was
withdrawn rather than shipped broken; it needs an extent measured against its own rim.

## Rationale / Context
`RoadCarver.Report` now carries `maxCutM` and `maxFillM`, and `TerrainGenerator` logs both per road at
load. A carve reporting 20 m of cut is visible in one line, before the connectivity check produces a
message about spawn points that points nowhere near the road.

## Impact
`game-core` `arena` (`RoadCarver`, `TerrainGenerator`), `assets/arenas/*/arena.json`. The real fix a
future session should make is a guard: a road whose corridor overlaps the border rise band should be
an authoring error at load, not a landscape the seed loop silently rejects eight times.
