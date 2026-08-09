# PROG-006: All four detach triggers work, and what leaves a vehicle becomes debris

**Date:** 2026-08-08
**Category:** progress
**Related Docs:** docs/07_damage_destruction_model.md#D07-S5.7, docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.6

**Status:** superseded (by PROG-007)

## Summary
`DetachSystem` (slot 14) implements all four detachment triggers of D07-S5.7, wrecks a vehicle whose
chassis died, and turns every detached part into a debris body — including the subtree PROG-005 left
bodyless below a fractured part. Five of D04-S4.4's 27 systems now exist. Nothing yet spawns a
vehicle outside a test, and no hit normal exists for the detach kick.

## Details

**Status of Work:** (supersedes the corresponding rows of PROG-005)

| Area | State | Notes |
|---|---|---|
| `DetachSystem` (slot 14, D07-S5.7) | done | T1 (destroyed, with the hanging delay), T2 (broken constraint), T3 (inside `detach`), T4 (`wreckVehicle`). Authority only; no runtime mode check, per D04-R8 |
| Debris for non-fractured parts (D05-S5.5 step 4) | done | One body per detached part from its cached hull, at the placement `detach` recorded, then the part entity is retired (DEC-018) |
| Subtree below a fractured part | done | Closes PROG-005's gap. Slot 13 detaches it, slot 14 embodies it in the same tick, driven by the parts' own state |
| `wreckVehicle` (D07-S5.7 T4) | done | `VehicleDestroyedEvent` first, then every part in ascending slot path order, then the chassis as a `WRECK_LIFETIME_S` body, then the vehicle entity. One-way via entity liveness, not an `isWrecked` field (DEC-017) |
| Chassis that fractures | done | Slot 13 fractures it without detaching it; slot 14 reads its absence as the wreck trigger (DEC-017) |
| `PartDetachment` placement recording | done | World transform and `v + ω × r` written onto each leaving part; `PartPlacement` holds the recentring math slot 13, slot 14 and `detach` all need (DEC-018) |
| Constraints (D06-S5.6) | in_progress | `PhysicsWorld.attachBreakable` / `removeConstraint` / straggler teardown exist and T2 consumes them. `btGeneric6DofSpring2Constraint` for authored articulated parts is not built by anything, because no spawn path constructs a separate-body part |
| Hanging parts (D07-S5.7 T1, D06-R21) | in_progress | The `HANGING_TICKS` delay works and is what holds a compound-geometry part. The `btFixedConstraint` is only created for a part that already has a body (DEV-009) |
| Detach kick (`DETACH_KICK_MPS` = 3.0) | not_started | D07-S5.7 adds up to 3 m/s along the hit normal when a hit caused the detachment. Nothing records a hit normal: `CollisionEventSystem` (11) and `DamageSystem` (12) are unwritten. Belongs to the session that gives a part a recorded last hit |
| `game-core` asset layer (D08-S5.3) | in_progress | `AssetIndex` now declares `fractureManifest` and `partType`; `PartType` carries the collision mesh and `hangsBeforeFalling` only. Materials, assemblies and arenas are not modelled; no loader exists |
| Coverage map (D05-R13, D01-R11) | not_started | as PROG-005 |
| Wheel detach, native half (D05-S5.5 step 2) | blocked | as PROG-005 (DEV-008). The ECS half and the wheel's debris body both work |
| `LifetimeSystem` (slot 16) | not_started | Debris carries `LifetimeComponent` but nothing decrements it, so nothing despawns yet. `MAX_DEBRIS_BODIES` recycling is what bounds the count today |
| `game-core` system catalogue (D04-S4.4) | in_progress | 5 of 27: 10, 13, 14, 15, 27. `SystemSetFactory.forMode` not started |
| vehicle spawn, damage, net/ai/match, `game-client`, `game-server-headless`, `asset-pipeline` | not_started | as PROG-002/PROG-003 |

**History (append-only):**
- 2026-08-08 (k): `DetachSystem`, `PartPlacement`, `PartType`, `VehicleDestroyedEvent`, the constraint
  half of `PhysicsWorld`, placement recording in `PartDetachment`, and the chassis guard in
  `FractureSystem`. 125 `game-core` tests green (12 new, in `DetachSystemTest`) under a JDK 21
  toolchain override (DISC-007); `check`, `validateDocs` and `lintMemory` all pass.

**Acceptance criteria now covered:** AC-D07-15 (all four triggers, each producing its
`PartDetached`/`VehicleDestroyed` event), AC-D05-10, AC-D05-11 and AC-D05-14 for the detach path,
AC-D05-16's detached half, D05-E5 (detaching a part whose child already left is a no-op), and
D07-E5 (a part with no fracture manifest detaches as one body). T-D05-13 and T-D07-19 have direct
tests.

**What the next session should pick up:** `LifetimeSystem` (slot 16) is the cheapest real gap — every
debris body already carries the component it reads, and without it nothing ever despawns, so
`MAX_DEBRIS_BODIES` recycling is doing a job it was meant to backstop. After that, `SpawnSystem` (5)
and the assembly loader, which is what would let `DestructionTestScene` collapse into a call to the
real spawn path and would settle DEV-009 by making an articulated part constructible.

## Rationale / Context
PROG-005 named `DetachSystem` as the next work and listed three things it would have to bring with
it: the four triggers, the debris body for a non-fractured part, and `wreckVehicle`. All three are
done, and this entry records which of the surrounding gaps it deliberately did **not** close —
the detach kick, `LifetimeSystem`, the articulated constraint — so the next session reads them as
scoped boundaries rather than as oversights, and knows which are blocked on an unwritten system and
which on a contradiction in the blueprints (DEV-009).

## Impact
`game-core`. Supersedes PROG-005's rows for `DetachSystem`, the asset layer, the system catalogue,
and the subtree-below-a-fracture gap.
