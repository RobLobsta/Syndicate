# PROG-010: a vehicle can be driven; nine of the 27 systems exist

**Date:** 2026-08-09
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/05_vehicle_part_system.md#D05-S5.6, docs/06_physics_simulation.md#D06-S5.5

**Status:** active

## Summary
`VehicleStatsSystem` (6) and `VehicleControlSystem` (7) exist, with the degradation model of
D05-S5.4 underneath them. A spawned vehicle now aggregates its parts into engine force, brakes,
steering and a derived top speed, and drives on scripted input over a ground plane. Nine of
D04-S4.4's 27 systems exist. Damage still only happens when a test says so — slots 11 and 12 are
what change that.

## Details

**Scope:** `game-core` — the ECS engine, physics, vehicle, damage, and the D04-S4.4 system
catalogue (DEC-023). The toolchain is PROG-008; content and the runtime shells are PROG-011.

**Status of Work:** (supersedes PROG-007 for the simulation subsystem)

System catalogue (D04-S4.4), 9 of 27:

| Area | State | Notes |
|---|---|---|
| 5 `SpawnSystem` | done | PROG-007; every vehicle now also gets a `PlayerInputComponent` (DEC-026) |
| 6 `VehicleStatsSystem` | done | All four phases of D05-S5.6. Recomputes unconditionally rather than on a `dirty` flag (DEC-025) |
| 7 `VehicleControlSystem` | done | D06-S5.5's loop: rate-limited steering, per-driven-wheel engine force, brakes, per-wheel grip, downforce, the 40 m/s clamp |
| 10 `PhysicsSystem` | done | PROG-003 |
| 13 `FractureSystem` | done | PROG-005 |
| 14 `DetachSystem` | done | PROG-006; the detach kick still has no hit normal to read |
| 15 `MassPropertySystem` | done | PROG-005, PROG-007 |
| 16 `LifetimeSystem` | done | PROG-007 |
| 27 `EntityDestroySystem` | done | PROG-004, PROG-007 |
| 1-4, 8, 9, 11, 12, 17-26 | not_started | `CollisionEventSystem` (11) and `DamageSystem` (12) are the pair that make damage happen at all; they also hand slot 14 the hit normal it has waited for since PROG-006 |

Other work in this subsystem:

| Area | State | Notes |
|---|---|---|
| Degradation model (D05-S5.4) | done | Four curves, the per-category table, inverted stats, and per-part authored overrides read from `part.json`. The table is treated as exhaustive and multipliers fade toward identity (DEC-024) |
| Schedule assembly (D03-S5.2) | done | `SystemSlot` is the D04-S4.4 catalogue as data; `SystemSetFactory.forMode` filters it and a `SystemProvider` builds each slot, so `game-client`'s six can arrive without a dependency cycle (DEC-030) |
| World construction (D04-S5.4) | in_progress | `WorldFactory` creates the world and the match singleton at index 1. `ArenaFactory` does not exist, so a world has nothing to drive on but an implicit ground plane in a test |
| Ray-cast vehicle (D06-S5.5) | done | Wheel tuning corrected onto D06-S4.5's reference table, which DEC-022 had missed (DEC-029). A destroyed wheel is commanded to zero rather than skipped (DEC-028) |
| Collision filtering (D06-S4.4) | done | Every body now carries `DefaultFilter`, without which Bullet's own ray tests — the suspension ray above all — see nothing (DISC-011, DEV-012) |
| Coverage map (D05-R13, D01-R11) | not_started | as PROG-005 |
| Power budget (D05-S5.7) | in_progress | Summed at runtime over live parts; the build-time check of AC-D05-18 belongs to `asset-pipeline` and needs `assets/balance/classes.json` |
| Wheel detach, native half (D05-S5.5 step 2) | blocked | as PROG-005 (DEV-008) |
| Hanging constraint for compound parts | blocked | as PROG-006 (DEV-009) |
| net, ai, match | not_started | Component and DTO scaffolding only |

**History (append-only):**
- 2026-08-09: `Degradation`, `DegradationProfile`, `DegradationRule`, `VehicleStatsSystem`,
  `VehicleControlSystem`, `SystemSlot`, `SystemProvider`, `SystemSetFactory`, `CoreSystemProvider`,
  `WorldFactory`; `degradationOverrides` on `PartType` and in `AssetLoader`; `maxSteerRad` and
  `currentSteerRad` components; the `DefaultFilter` fix in `PhysicsWorld`; the wheel tuning
  correction in `VehicleFactory`. `DestructionTestScene` gained a ground plane and now registers
  slots 6 and 7, so every destruction test runs on the full schedule. 209 `game-core` tests green
  (65 new) under the JDK 21 toolchain override (DISC-007); `check`, `validateDocs`, `lintMemory` and
  the physics regression tag all pass.

**Acceptance criteria now covered:** AC-D05-6, AC-D05-7, AC-D05-8, AC-D05-9, AC-D05-15, AC-D05-16,
AC-D05-17, AC-D05-20 and the immobility half of AC-D05-1's edge case E1, plus AC-D04-3 for slots 6
and 7. T-D05-5, T-D05-7, T-D05-8, T-D05-15, T-D05-16, T-D05-19 and T-D05-20 have direct tests.

**What the next session should pick up:** `CollisionEventSystem` (11) and `DamageSystem` (12), in
that order. They are what turn a collision into damage, replace `DestructionTestScene.destroyPart`
with the real path, and give `DetachSystem` the hit normal its kick has been missing. After them,
`WeaponSystem` (8) and `ProjectileSystem` (9), which is the point at which two vehicles can fight.

## Rationale / Context
PROG-007 named slots 6 and 7 as the next work and both are done, along with the degradation model
neither could exist without and the schedule assembly that turns them into a process. This records
what each stopped short of — the unauthored steering and drag constants, a power budget with no
class targets to check against, a world with no arena — so the next session reads them as scoped
boundaries rather than as oversights. It also records that the wheel tuning constants were wrong
against D06-S4.5 for two sessions, which is the kind of thing a progress entry should not let repeat.

## Impact
`game-core`. Supersedes PROG-007 for the simulation subsystem only.
