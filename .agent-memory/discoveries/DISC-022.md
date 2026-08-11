# DISC-022: a same-tick event is consumed before presentation runs, so every hit was silent

**Date:** 2026-08-11
**Category:** discoveries
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/07_damage_destruction_model.md#D07-S5.1, docs/07_damage_destruction_model.md#D07-S5.9

**Status:** active

## Summary
`DamageEvent` was published with `emitSameTick`, which `DamageSystem` (12) drains within the tick. PRESENT systems run after the tick, on the deferred bus, so slots 24 and 25 never saw a single hit: cars took damage with no spark and no noise, and the unit tests for both systems passed because they emitted their own events.

## Details
`EventBus` has three publishers. `emitSameTick` puts an event in a per-type queue that a later system in the *same* tick drains and consumes; `emit` queues it for `dispatchQueued()` at the end of the tick, where subscribers see it; `emitPipeline` does both.

`CollisionEventSystem` (11) and `ProjectileImpact` used `emitSameTick`, which is correct for slot 12 and invisible to everything else. The symptom was a client that ran a full match, took a car from full health to 78%, and drew zero particles — with green tests for `EffectSystem` and `AudioSystem`, because a test that emits its own event goes through `emit` and reaches the listener.

Both call sites now use `emitPipeline`. That is not a widening of D04-R14: `emitPipeline`'s own contract already describes this case — "replication and presentation subscribe in the ordinary way and must not have the event consumed out from under them by whichever system drained it first". `DetachSystem` and `DamageApplication` were already using it for their destruction events, which is why detachment and part destruction were the two families that would have worked.

The evidence that closed it is a counter the renderer keeps: `ParticleRenderer.peakQuadCount()` is the most quads any frame has drawn since the process started, logged with every capture. A single capture is one instant and a spark burst lasts under half a second, so "0 quads in this frame" says nothing; peak went from 0 to 98 over a 9,572-tick run on the same content.

## Rationale / Context
The next system that subscribes to a simulation event will hit this, and the failure mode is the worst kind: nothing throws, nothing logs, the tests are green, and the only symptom is an absence. Before subscribing to an event type, check how it is published.

## Impact
- `game-core` `CollisionEventSystem`, `ProjectileImpact`: `emitSameTick` -> `emitPipeline` for `DamageEvent`.
- `game-client` slots 24 and 25 receive collision and projectile damage.
- `ParticleRenderer.peakQuadCount()` and the capture log line exist to make this class of absence visible.
