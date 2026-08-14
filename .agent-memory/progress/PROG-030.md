# PROG-030: the arena — generated terrain, and the three stages still to come

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.1, docs/16_procedural_arena_generation.md#D16-S6, docs/16_procedural_arena_generation.md#D16-S7

**Status:** active

Supersedes: PROG-025

## Summary
D16 is built in four stages and **stage 1 of 4 is done**. A 600 m desert generates from a seed in
187 ms, collides as one height field, and answers height, slope, surface and drivability queries. It
is not drawn, has no road, and holds no structures — and it is not the arena the game loads by
default, which is still a flat box.

## Details

**Scope:** `game-core` `arena`, `ShapeCache`'s height-field entries, `ArenaFactory`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Stage 1 — generator, collision, queries | done | Relief, dunes, border rise, pads, surfaces, drivability, connectivity |
| Stage 2 — rendering (D16-S6) | not_started | Chunked mesh, LOD, generated textures, analytic sky, fog |
| Stage 3 — roads and per-surface grip (D16-S5.4, S5.10) | not_started | Surface grid exists and is populated; the carve and the wheel read do not |
| Stage 4 — structures (D16-S7) | not_started | Everything that breaks them exists (DEC-071); the factory and placement pass do not |
| Default arena | blocked | Blocked on stage 2: `arena_desert_01` cannot be the default until something draws it |

The shipped `arena_desert_01` measures 601 × 601 samples over 600 m: relief −4.7 m to +50.6 m, 73.5%
drivable with 94% of that one connected region, surfaces 69% sand / 23% rock / 8% gravel, dune slip
faces at a 32.5° mean against a 33.0° target.

**Both Bullet traps D16 predicted are real and verified.** The height-field shape borrows the
caller's buffer rather than copying it, and it centres itself on the midpoint of its own height
range — placing a body at `groundY` would put the collision 17 m from the drawn surface here.

## Rationale / Context
This is the one part of the project that is real, tested and **never once looked at**. Everything
this project has learned about measurement says that is when to be most careful, and the cheapest
insurance is a top-down debug image of the height, slope and surface grids before the renderer is
written.

## Impact
- Stage 2 is the next piece of work and is no longer blocked by the environment (DISC-046).
- Until stage 2 lands, `arena_scrapyard_01` — a flat box with a grid painted on it — is what players
  see, and its name promises scenery it does not have.
