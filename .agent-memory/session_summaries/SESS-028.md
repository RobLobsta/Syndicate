# SESS-028: the ground gets a specification

**Date:** 2026-08-14
**Category:** session_summaries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.1, docs/16_procedural_arena_generation.md#D16-S7, docs/08_asset_pipeline.md#D08-S4.7

**Status:** active

## Summary
The user asked whether ground and sky could be generated at runtime — textured, sloped, hilly, natural
enough that driving lines and positioning matter — starting with desert and tarmac, destructible
structures to follow. The answer is yes, and this session wrote the contract:
`docs/16_procedural_arena_generation.md`, a seventeenth blueprint. No implementation.

## Details
The design is a nine-stage pipeline run once at load from a seed, producing two grids — heights and
surfaces — from which everything else derives: collision, the drivability grid bots navigate on, the
render mesh, and the grip and sound under each wheel. Three choices carry the weight:

- **A height field, derived on every peer rather than replicated** (DEC-069). The only representation
  keeping the suspension ray accurate at arena scale — DISC-017 is the trap a mesh arena walks back
  into — and it costs 8 bytes on the wire, not 1.4 MB.
- **Per-surface grip at the wheel, not through Bullet's material callback** (DEC-070). A ray-cast tyre
  generates no contact point, so both obvious Bullet mechanisms are code that never runs. Also closes
  the gap in which every arena is tarmac and the gravel loop cannot play.
- **A structure is an assembly** (DEC-071), so damage, fracture, detach and debris expiry break one
  with no new system and no new slot.

The lever the desert biome hangs on is a gap between two constants: `MAX_DRIVABLE_SLOPE_DEG` at 25°
sits below `SAND_REPOSE_DEG` at 33°, so a dune's windward face is a ramp and its slip face is a wall,
authoring neither. The highway is a spline carved with a limited grade, and cut and fill fall out of
the falloff for free: cover both sides where the land was high, an embankment to be pushed off where
it was low.

## Rationale / Context
Bullet's height field holds a raw pointer to the caller's data and centres the shape on its own AABB.
Both are recorded as requirements (D16-R47, R48) with the failure each produces: a crash with no Java
frame, and "a rendering bug" that is not one.

Those two traps, and the existence of the binding in gdx-bullet 1.14.2 at all, are asserted from
knowledge rather than measured — this sandbox has no dependency cache to check the jar against.
Flagged to the user; the first implementation session should verify the binding first.

## Impact
- `docs/` goes from 16 documents to 17. D00-S4.2, S5.4, S7.16, S8, S9, the topic map, `CLAUDE.md` and
  `JULES.md` were amended in the same commit (D00-E3).
- `ValidateDocsTask` now enforces the nine required sections through D16; capped at D14, it had never
  checked D15 either. Both pass: 500 ids across 17 documents, all citations resolve.
- D08 gains the `structures/` bucket (R14a), four arena blocks (R15b), six `A4xx` codes.
- Nothing is implemented. The arena in the game is still a flat plane and four boxes.
