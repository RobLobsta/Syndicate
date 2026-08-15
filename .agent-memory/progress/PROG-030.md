# PROG-030: the arena — themed terrain, drawn, and the stages still to come

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.1, docs/16_procedural_arena_generation.md#D16-S6, docs/16_procedural_arena_generation.md#D16-S7

**Status:** active

Supersedes: PROG-025

## Summary
D16 is built in four stages. **Stage 1 is done and stage 2 is half done.** Both shipped arenas
generate from a theme and a match-derived seed, collide as one height field, answer every query, and
are **drawn** — as a single decimated mesh split by surface, which is enough to look at and drive on
but is not D16-S6. No road is carved and no structure exists.

## Details

**Scope:** `game-core` `arena`, `ShapeCache`'s height-field entries, `ArenaFactory`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Stage 1 — generator, collision, queries | done | Relief, dunes, border rise, pads, surfaces, drivability, connectivity |
| Themes (DEC-074) | done | `ArenaTheme` owns relief layer, surface palette, albedo and every number |
| Match-derived seeds (D16-R6b) | done | `seed: 0` means a new landscape per match; re-seeds if unplayable |
| Stage 2a — ground drawn | done | One mesh, decimated to a stride, one material per surface |
| Stage 2b — rendering proper (D16-S6) | not_started | Chunking, culling, LOD, generated textures, analytic sky, fog |
| Stage 3 — roads and per-surface grip (D16-S5.4, S5.10) | not_started | Surface grid exists and is populated; the carve and the wheel read do not |
| Stage 4 — structures (D16-S7) | not_started | Everything that breaks them exists (DEC-071); the factory and placement pass do not |
| Default arena | done | `arena_scrapyard_01` is a real scrapyard and is drawn |

The shipped `arena_desert_01` measures 601 × 601 samples over 600 m: relief −4.7 m to +50.6 m, 73.5%
drivable with 94% of that one connected region, surfaces 69% sand / 23% rock / 8% gravel, dune slip
faces at a 32.5° mean against a 33.0° target.

**Both Bullet traps D16 predicted are real and verified.** The height-field shape borrows the
caller's buffer rather than copying it, and it centres itself on the midpoint of its own height
range — placing a body at `groundY` would put the collision 17 m from the drawn surface here.

## Rationale / Context
This was the one part of the project that was real, tested and never once looked at — and looking at
it immediately produced two defects that every automated check had passed (DISC-047): heaps a twelfth
of their intended height, and a palette that rendered as a snowfield. Both were found by a capture.

The properties a theme claims are now swept across twelve seeds each rather than asserted at one,
because under D16-R6b the seed is different every match and a property that holds at one seed is not
a property of the theme.

## Impact
- What remains of stage 2 is performance work, not visibility: chunking, culling and LOD, which need
  a real GPU to measure against. The sandbox runs software GL at 4–10 fps and can say nothing about
  frame rate.
- The desert's dune slip faces read as jagged where the stride skips them; that is decimation, not
  classification, and chunking fixes it.
