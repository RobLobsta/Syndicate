# PROG-014: two vehicles can now hurt each other; 14 of the 27 systems exist

**Date:** 2026-08-10
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/07_damage_destruction_model.md#D07-S5.2, docs/01_product_game_design.md#D01-S4.4

**Status:** superseded (by PROG-026)

## Summary
The five combat systems of Phase 5 exist: collisions become damage, damage drives the state machine
and propagates, weapons fire, projectiles fly, and kills score. Fourteen of D04-S4.4's 27 systems now
exist, and everything the destruction pipeline has been able to do since Phase 1 is finally triggered
by the game rather than by a test.

## Details

**Scope:** `game-core` — the ECS engine, physics, vehicle, damage, and the D04-S4.4 system catalogue
(DEC-023). The toolchain is PROG-008; content and the runtime shells are PROG-015.

**Status of Work:** (supersedes PROG-010 for the simulation subsystem)

System catalogue (D04-S4.4), 14 of 27:

| Area | State | Notes |
|---|---|---|
| 5 `SpawnSystem` | done | PROG-007 |
| 6 `VehicleStatsSystem` | done | PROG-010 |
| 7 `VehicleControlSystem` | done | PROG-010 |
| 8 `WeaponSystem` | done | All eight D01-S4.4 families over two delivery models; cooldown, ammunition, heat, the `MatchPhase.ACTIVE` gate of D01-E12, and seeded spread from the `DAMAGE_SPREAD` stream |
| 9 `ProjectileSystem` | done | D06-S5.9's explicit-Euler integration plus a swept ray per tick; no rigid body per bullet, so nothing tunnels |
| 10 `PhysicsSystem` | done | PROG-003 |
| 11 `CollisionEventSystem` | done | Manifolds read after the step and sorted by entity pair (G3); impulse above `COLLISION_DAMAGE_THRESHOLD` becomes `COLLISION` damage to both sides |
| 12 `DamageSystem` | done | D07-S5.2's five steps, D07-S5.3's state machine, D07-S5.4's bounded propagation, and the incendiary burn timer |
| 13 `FractureSystem` | done | PROG-005 |
| 14 `DetachSystem` | done | The detach kick of D07-S5.7 now works: slot 12 records the hit normal, closing the gap PROG-006 named |
| 15 `MassPropertySystem` | done | PROG-005, PROG-007 |
| 16 `LifetimeSystem` | done | PROG-007 |
| 17 `ScoreSystem` | done | D01-S5.4's six score events, kill attribution, team-kill and self-destruct penalties, assists from the damage ledger |
| 27 `EntityDestroySystem` | done | PROG-004 |
| 1-4, 18-26 | not_started | `MatchFlowSystem` (4) is the one that makes a match start and end; `BotDecisionSystem` (3) is the one that makes anything drive itself |

Other work in this subsystem:

| Area | State | Notes |
|---|---|---|
| Hit resolution (D07-S5.1) | done | Compound child index → slot path, the wheel special case of D07-R11, the centroid fallback of D07-E13, and the three positional modifiers |
| Coverage map (D05-S5.8) | done | Armour intercepts hits aimed at what it covers, and `EXPOSED` applies once it is gone. Closes the D05-R13 gap PROG-005 recorded |
| Damage propagation (D07-S5.4) | done | Bounded BFS, sorted frontier, visited set, and it crosses the chassis (D07-R16) — which took explicit handling, because the chassis is the root of the slot tree rather than an edge in it |
| Damage ledger (D01-S5.4) | done | Bucketed ten-second window plus a match total, on the match singleton (DEC-040) |
| Burn stacks (D07-R8) | done | Per-stack timers, capped at five, delivered as ordinary damage events so armour and scoring see them the same way |
| Explosive blast (D07-E3) | done | One event per live part in radius with its own `(1 − d/R)²` falloff; no part counted twice |
| Weapon content | not_started | `WeaponBlock` exists and `VehicleFactory` reads it; **no shipped part is a weapon**, so nothing in `assets/` can fire |
| Coverage content | not_started | Same shape: the map works, and no shipped part authors a `covers` list |
| Wheel detach, native half | blocked | as PROG-005 (DEV-008) |
| Hanging constraint for compound parts | blocked | as PROG-006 (DEV-009) |
| net, ai, match | not_started | Component and DTO scaffolding only |

**History (append-only):**
- 2026-08-10: `DamageEvent`, `DamageStateChangedEvent`, `PartDestroyedEvent`, `DamageApplication`,
  `HitResolution`, `CoverageMap`, `DamageLedger`, `ProjectileImpact`; `CollisionEventSystem`,
  `DamageSystem`, `WeaponSystem`, `ProjectileSystem`, `ScoreSystem`; `ProjectileComponent`,
  `BurnStackComponent`, `DamageLedgerComponent`; `WeaponBlock` and `WeaponFamily`;
  `EventBus.emitPipeline`; the detach kick in `DetachSystem`; `lastHitNormal` on `HealthComponent`.
  `DestructionTestScene` now registers all five, so every destruction test runs on the full schedule.
  262 `game-core` tests green (17 new) under the JDK 21 toolchain override (DISC-007); `check`,
  `validateDocs` and `lintMemory` pass.

**Acceptance criteria now covered:** AC-D07-2, AC-D07-3, AC-D07-4, AC-D07-5, AC-D07-6, AC-D07-7,
AC-D07-8, AC-D07-14 and AC-D07-16, plus AC-D01-8 for the eight families and AC-D04-3 for slots 8, 9,
11, 12 and 17. T-D07-1 to T-D07-4, T-D07-8, T-D07-9, T-D07-10, T-D07-11 and T-D07-17 have direct
tests.

**What the next session should pick up:** `MatchFlowSystem` (4) and `BotDecisionSystem` (3). Combat
works and nothing starts it: there is no match state machine to move a world out of `LOBBY`, and
nothing that drives a vehicle without a human. Those two, plus a spawn bootstrap that puts vehicles on
the arena's spawn points, are what turn "two vehicles can hurt each other" into "a match happens".

## Rationale / Context
PROG-010 named slots 11 and 12 as the pair that would make damage happen at all, and both exist along
with the three that make it worth having. This records the two gaps that are content rather than code
— no shipped part is a weapon, and no shipped part covers anything — because both systems look
untested from the outside and are not: the code paths are exercised by fixtures, and the shipped
vehicles simply have nothing to fire and nothing to strip.

It also records that the chassis needed explicit handling in the propagation walk, which is the kind
of thing that reads as an oversight in the diff and is the opposite.

## Impact
`game-core`. Supersedes PROG-010 for the simulation subsystem only.
