# SESS-015: combat, and a world to have it in

**Date:** 2026-08-10
**Category:** session_summaries
**Related Docs:** docs/07_damage_destruction_model.md#D07-S5.2, docs/04_entity_component_model.md#D04-S4.4, docs/08_asset_pipeline.md#D08-S5.2

**Status:** active

## Summary
Implemented Phase 5 in full — the five systems that turn a contact or a trigger pull into a part
coming off a vehicle — and the parts of Phase 6 that do not depend on the Blender split: an arena
that ships and loads, and an asset pipeline that stops being a stub. The system catalogue goes from
9 of 27 to 14 of 27.

## Details

**Phase 5 (D04-S4.4 slots 8, 9, 11, 12, 17).** `CollisionEventSystem` reads Bullet's manifolds after
the step and turns applied impulse into `COLLISION` damage; `DamageSystem` applies every event of the
tick in a sorted order, drives the state machine and propagates; `WeaponSystem` and
`ProjectileSystem` implement D01-S4.4's eight families over D06-S5.9's two delivery models;
`ScoreSystem` awards D01-S5.4's values including assists.

The arithmetic lives in a shared `DamageApplication` rather than inside slot 12, because a client
runs the same state machine on replicated health (DEC-038). `HitResolution` implements D07-S5.1,
`CoverageMap` implements D05-S5.8, and the detach kick of D07-S5.7 stopped being a stub: slot 12
records the hit normal, which PROG-006 recorded as missing since it was written.

**Phase 6, the half that was not blocked.** `ArenaDef`, the A4xx rules, `ArenaFactory` and a shipped
`arena_scrapyard_01` — so `syndicate-server` ticks a world with ground in it. The arena's collision is
generated from its own bounds rather than loaded from a mesh (DEV-014), which needs a concave shape
type nothing owns yet.

`asset-pipeline` became real: it cross-checks parts against their fracture manifests and assemblies
against their parts, checks the D05-R30 power budget against a new `assets/balance/classes.json`, and
writes `asset-index.json`. It is a second implementation rather than a call into the runtime loader,
for a reason worth writing down (DEC-041).

**Three findings worth keeping.** A Bullet contact point carries the compound child index in
`getIndex0`/`getIndex1` (DISC-015). Propagation crossing the chassis needed explicit handling,
because the chassis is the root of the slot tree rather than an edge in it. And resolving a weapon
stat against a non-zero default adds that default to every part that authored a value — one test
caught it, and the pattern is named in DEC-039.

**What is still blocked, and it is the same thing.** The split: one whole-car model into five parts.
The pipeline now says so out loud — `buildIndex --strict` reports A107 for every declared-but-absent
`mesh.glb` — which is why it is not wired into `check` yet.

## Rationale / Context
Two phases in one session, because Phase 6's remaining work turned out to be two independent halves:
content plumbing, which was ordinary coding, and the art split, which needs Blender and is not a
coding job at all. Recording the split as the single remaining blocker matters because the roadmap
will now show Phase 6 as nearly done.

## Impact
`game-core` (`damage`, `system`, `component`, `asset`, `physics`, `ecs`, `vehicle`),
`shared-models`, `asset-pipeline`, `assets/arenas/`, `assets/balance/`, `game-server-headless`,
`docs/04_entity_component_model.md`, `docs/08_asset_pipeline.md`, `ROADMAP.md`.
