# DISC-067: spawns are authored at y=1, the ground under them is not, so cars start buried

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.6, docs/06_physics_simulation.md#D06-S5.5, docs/03_runtime_modes.md#D03-S4.2

**Status:** active

## Summary
Both shipped arenas author every spawn point at `y = 1.0`. The spawn **pad** that flattens the ground
there levelled it to *the landform's own height*, which on the desert is up to **7.44 m higher**. The
vehicle is created at the authored `y`, so it starts several metres inside the terrain and Bullet
ejects it. Driven, the Eclipse shed 26 of its 40 parts in 1.5 s and finished upside down and
immobile with the throttle still open.

## Details
Measured on the desert at seed 12345, ground height at each authored spawn against `y = 1.0`:

    (-140, -110)  ground 7.44 m      (-140,  -70)  ground 4.56 m
    (-140,  -30)  ground 4.66 m      (-140,   10)  ground 6.31 m
    ( 140, -110)  ground 1.73 m      ( 140,   10)  ground 3.07 m

Six of eight buried. Nothing reconciles the two numbers: `ArenaFactory` builds a
`TerrainGenerator.Pad` from each spawn's `x`/`z` and clearance radius and **drops its `y`**, and
`SpawnSystem` places the chassis where it is told. The pad guarantees flat ground at a height
nobody told the spawner about.

**The fix is one number.** A `Pad` now carries the level it should cut to; a spawn pad passes its
spawn's authored `y`, a structure's pad passes NaN and keeps taking the landform's height. The
ramp-out logic already handles a large cut — its comment even discusses "a pad dug 9 m into a
crest" — so a buried spawn becomes a bowl with a drivable ramp rather than a burial.

**Why it hid.** Every symptom points elsewhere. The car is destroyed by *collision damage*, so it
reads as damage tuning; it ends up airborne, so it reads as DISC-063's dune launch; and it happens
during COUNTDOWN before anyone is looking. DISC-063 recorded the part count falling "from 37/39 to
8/12" in passing and blamed the speed clamp. That clamp was a real bug, but it was the second one,
and fixing it did not stop the car being thrown.

**Not the player's problem alone.** With the clamp fixed, a capture at 1.5 s shows the player *and
two bots* airborne at once. Every vehicle is launched.

## Rationale / Context
The most expensive thing the project has had wrong: it makes the flagship arena unplayable while
every automated check passes. The terrain is valid, the spawn is inside the playable region,
connectivity holds, mass properties are right. Nothing tested that a spawn is *on its ground*,
because until the seed is expanded there is no ground to test against.

## Impact
- `TerrainGenerator.Pad` gains `levelM`; `flattenPads` honours it; `ArenaFactory` passes the spawn's
  `y`.
- `SpawnGroundClearanceTest` asserts every desert spawn sits on its ground, and fails by 7.44 m
  without the change.
- Any future arena can reintroduce this by authoring a `y` and expecting the ground to find it.
