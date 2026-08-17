# PROG-026: simulation core — physics, vehicles, damage, destruction

**Date:** 2026-08-14
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.5, docs/07_damage_destruction_model.md#D07-S5.2, docs/05_vehicle_part_system.md#D05-S5.6

**Status:** superseded (by PROG-038)

Supersedes: PROG-014
Supersedes: PROG-017

## Summary
Every simulation system D04-S4.4 names exists and runs headless. A vehicle spawns from an assembly,
drives on a ray-cast model, takes damage that degrades it, sheds parts that change its mass
properties in the same tick, and is wrecked. Nothing here is blocked; what is missing is tuning by a
person, and one content gap — there are no weapon parts to fire.

## Details

**Scope:** `game-core` — `physics`, `vehicle`, `damage`, `system`, `ecs`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Fixed-step world and system schedule | done | 27 slots, fixed order, deterministic iteration (G2, G3) |
| Bullet world, layers, shapes | done | One body per vehicle, compound children per part (DEC-004) |
| Ray-cast vehicle, steering, brakes | done | Tuning from D06-S4.5, not Bullet's demo (DEC-029) |
| Stat aggregation and degradation | done | Recomputed every tick over live parts (DEC-025) |
| Hit resolution, armour, propagation | done | Shared operation; slot 12 is only its schedule slot (DEC-038) |
| Fracture, detach, debris | done | All four detach triggers; mass, COM and inertia in-tick (G10) |
| Mass properties after structural change | done | Spawn establishes, slot 15 confirms (DEC-021) |
| Weapons and projectiles | done | The systems exist; **no weapon part exists in `assets/`** |
| Balance of the numbers | not_started | Every constant is a blueprint default nobody has been hit by |

The one thing a reader should not mistake: "weapons work" means the code path works. `assets/parts/`
holds six parts — two chassis and four wheels — and not one of D01's eight weapon families has
content. Combat in a shipped match is therefore collision damage only.

## Rationale / Context
The previous entries in this lineage counted systems ("14 of the 27 systems exist"), which stopped
being informative the moment all 27 did. What a session needs to know here is which behaviours are
real, which are untuned, and which have no content behind them — and the last of those was invisible
in a count.

## Impact
- A session tuning handling, damage or bots changes constants, not structure.
- A session adding a weapon writes content, not code.
- Determinism guarantees (G2, G3, G5) hold across this whole area and are checked by `-Ptags=physics`.
