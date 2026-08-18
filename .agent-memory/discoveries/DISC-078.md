# DISC-078: a shard 465 km across, and damage emitted onto a queue nothing reads

**Date:** 2026-08-18
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S5.2, docs/14_test_environment.md#D14-S6.4, docs/04_entity_component_model.md#D04-R14

**Status:** active

## Summary
Two defects found while asking why a pane of glass weighed 99 tonnes. A fracture shard could lie
kilometres outside the part it was cut from and pass every self-verification check; and
`EventBus.emit` does not reach the damage pipeline at all.

## Details

**The shard.** The Voronoi cell on the outside of the hull is unbounded when it is not clipped to
the source. Nothing caught it:

| Check | Why it passed |
|---|---|
| TV-001, positive shard mass | the shard had mass — far too much of it |
| TV-007, mass conservation | held, because the same wrong volume was on both sides |
| TV-009, no NaN or Inf | every vertex was finite |
| TV-010, plausible extents | it measures the **source** extent, not the shards' |
| TV-008, mass distribution | warns by design, and warning is all it does |

`partMassKg` is volume x density, so one 2.5 km cell put 39.8 m3 of glass on a 77 m2 facade, and
`author_fracture` copied that into `part.json` — making the pane the toughest object in the
building. The worst measured case was a shard escaping its part by **465 km**.

**TV-013** now fails any shard whose AABB lies outside the part's own. Its tolerance must clear
`2 x shell_thickness`: shell fracture cuts the surface and *then* solidifies each patch, so a 150 mm
masonry wall's shards legitimately stand 0.2 m proud — correct output, not an escape. A hard failure
rather than an advisory, because a lopsided mass split is ugly content while a shard outside its own
part is arithmetic that has gone wrong, and the mass, inertia, hull and drawn mesh are all wrong
with it.

**The event bus.** `EventBus.emit` queues for delivery to *listeners* at the end of the tick.
`DamageSystem` reads `drainSameTick`. Damage raised with `emit` therefore fires, logs, and never
touches its target. `emitSameTick` is D04-R14's damage-pipeline exception and is what any producer
of a `DamageEvent` wants.

## Rationale / Context
Both cost real time to find because both *looked* like working code: the fracture reported success
with a green verification block, and the console's damage row logged a hit every time it was
pressed. Neither had any effect on the game.

## Impact
- Every masonry and glass manifest in `assets/structures/` was regenerated under TV-013.
- The tall city block will not fracture at all now: its bands drift 1-3 m in plan bottom to top
  (the source art is a *stylised leaning building*) and the surface cut makes unbounded cells on it.
  It ships unfractured with the reason in its notes, which is the honest outcome.
