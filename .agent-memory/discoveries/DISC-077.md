# DISC-077: no process ever spawned a structure, and the diagnosis was wrong too

**Date:** 2026-08-18
**Category:** discoveries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S7, docs/16_procedural_arena_generation.md#D16-R77, docs/03_runtime_modes.md#D03-S5.3

**Status:** active

## Summary
`ArenaFactory.load` has a four-argument overload and a five-argument one, and the asset index is the
difference. **All three runtime callers used the four-argument form**, so the D16-R77 placement pass
returned an empty list in every process. Structures existed only in the tests that called the other
overload directly.

## Details
Without an index there is nothing to resolve a `structureId` against, so `placeStructures` returns
`List.of()` before doing anything. `ClientRuntime`, `ServerRuntime` and `OfflineMatchRunner` each
had an index in scope and each dropped it at the call. The fix was one argument at three sites, and
23 structures appeared in the first capture afterwards.

**The more useful half is the misdiagnosis.** PROG-042 recorded the gap as "the client renders
vehicles and terrain; structures are not in its render pass yet", and that was inferred from reading
the code rather than from looking at a frame. It was wrong in both directions:

- The render pass was **already generic**. `RenderSystem`'s `undrawn` family matches any
  `PartRefComponent`, `InterpolationSystem` gives any `TransformComponent` a render transform, and
  `AssetPaths.partDirectory` already resolved structure-owned parts. Zero renderer changes were
  needed.
- The actual symptom was not "invisible walls you collide with". It was an arena with **no
  structures in it at all** — no collision, no damage, no collapse — which a single capture would
  have shown at once.

## Rationale / Context
This project's rule is that a claim about how the game looks should be a capture rather than an
inference. The rule applies just as much to *why* something is broken: a wrong diagnosis in a
progress entry is more expensive than no entry, because the next session starts from it and looks in
the wrong subsystem.

## Impact
- Structures now spawn in the client, the dedicated server and the offline match runner alike.
- Anything else in `.agent-memory/` claiming a subsystem is "built but not rendered" deserves one
  capture before it is believed.
