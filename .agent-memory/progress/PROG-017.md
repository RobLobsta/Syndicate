# PROG-017: an assembled car drives, its wheels turn, and one of them comes off

**Date:** 2026-08-10
**Category:** progress
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.5, docs/14_test_environment.md#D14-S5.11

**Status:** superseded (by PROG-026)

## Summary
The shipped content is now a vehicle rather than a set of files that describe one. Both cars settle
on their own wheels to the millimetre, their tyres roll through the distance travelled, a wheel
destroyed at 30 m/s becomes debris while the car drives on, and there are captures of all of it.

## Details

**Scope:** `game-core` `system`/`vehicle`/`physics`/`asset`, `assets/`, `test-environment`.

**Status of Work:** (supersedes PROG-016 for the areas it names)

| Area | State | Notes |
|---|---|---|
| Chassis wheel slots | done | Was `not_started` in PROG-016. Authored at the suspension's equilibrium height over the art's axles; `RideHeightTest` holds both cars to a centimetre |
| Per-part centre of mass | done | DEC-043. `AssemblyLayout` and slot 15 now agree with D06-S5.7's own pseudocode |
| Ground shapes | done | DISC-017. Planes, not boxes — a convex ray test on an 800 m box is accurate to 14 cm |
| `TransformSystem` (slot 21) | done | World matrices down the tree; a wheel's comes from `getWheelTransformWS`, which is what makes it spin. **15 of 27 systems** |
| Wheel detachment on a live vehicle | done | DEV-015 supersedes DEV-008: the vacated native slot is disarmed, indices never renumber |
| `--vehicle` capture mode | done | `VehicleRun` + `VehicleScene`; `ModelRenderer` grew instances, per-instance transforms and a per-model texture cache |
| Fracture manifests | not_started | The six `fractureManifest` declarations are gone from `part.json` because the files never existed; a destroyed part detaches whole (D07-E5) until the tool runs |
| Damage morph targets | not_started | as PROG-016 |
| `schemas/`, weapon and armour content, transport, `game-client` | not_started | as PROG-016 |

**History (append-only):**
- 2026-08-10: wheel slots re-authored on both chassis; `PartType.centerOfMassLocal`;
  `ShapeCache.planeFor` and an `ArenaFactory` plane floor; `TransformSystem`;
  `EntitySystem.isPerFrame`; `PartDetachment.removeWheel` rewritten; `RideHeightTest`,
  `WheelSpinTest`, `WheelDetachTest`; `--vehicle` mode and eight captures in `docs/captures/`.

**What the next session should pick up:** fracture manifests per part, which is the last item
standing between a destroyed wheel and one that breaks up. After that the honest gap is content
rather than code — no shipped part is a weapon and none authors a `covers` list, so slots 8, 9 and
12 have still never met real content.

## Rationale / Context
Three sessions of calibration ran against a car whose body was 0.61 m in the air on wheels whose
ground contact moved 14 cm a tick, and every number came out inside tolerance. The reason nothing
caught it is that nothing rendered. Recorded because it is the clearest case this project has of a
test suite that measures the right quantities against a scene nobody has looked at.

## Impact
`game-core` (`asset`, `physics`, `system`, `vehicle`), `assets/parts/`, `assets/vehicles/`,
`test-environment` (`render`, `vehicle`), `docs/captures/`. Supersedes PROG-016.
