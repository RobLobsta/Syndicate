# SESS-017: the car drives, the wheels turn, and one comes off

**Date:** 2026-08-10
**Category:** session_summaries
**Related Docs:** docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.5, docs/14_test_environment.md#D14-S5.11

**Status:** active

## Summary
Put the wheels on the ground, made them spin, made one detach at speed, and photographed all three.
`TransformSystem` fills slot 21 (15 of 27), and `syndicate-verify --vehicle` renders an assembled car
from component state.

## Details

**What was wrong.** The shipped chassis authored every wheel slot at `y: 0`, leaving both cars' bodies
0.61 m in the air with the wheels dangling. Nothing noticed: ride height does not affect acceleration
or braking, and nothing rendered a vehicle. Fixing it exposed two more:

- `AssemblyLayout` put each part's mass at its *attachment point*. The chassis mesh's origin is on
  the road, so the whole car's centre of mass sat 6 cm up and Bullet was rotating the vehicle about a
  point under the tarmac (DEC-043). D06-S5.7's own pseudocode already said `part.type.comLocal`.
- The test road was a 120 × 2 × 800 m convex box, and Bullet's ray test on a shape that size is
  accurate to about 14 cm. Each wheel's ground contact moved at random every tick while the body
  stood still — it reads exactly like under-damped suspension (DISC-017). Ground surfaces are planes
  now, here and in `ArenaFactory`.

With both fixed, a settled car parks its chassis origin within a millimetre of the road and stays
there, each axle at its own tyre's radius.

**Slot 21.** `TransformSystem` composes world matrices down the tree, with two exceptions that are
the point: a ray-cast wheel's matrix comes from `getWheelTransformWS`, since its travel, steering and
spin live inside Bullet; and the chassis part hangs off its vehicle at `-comLocal`, rewritten each
tick because the COM moves on a detach. It is the one `PRESENT` system that runs per tick (D04-R7),
so `EntitySystem` gained an overridable `isPerFrame()`.

**Detachment.** DEV-008 had responded to `btRaycastVehicle` having no `removeWheel` by re-densifying
the ECS wheel indices — which is backwards, since those indices address the native array and it does
not densify. Survivors were pointed at each other's suspension. DEV-015 supersedes it: the vacated
slot is disarmed in place and indices are stable for the life of a vehicle.

**Content.** All six parts declared a `fracture_manifest.json` that never existed, so a destroyed
wheel vanished instead of detaching. Removed until the fracture tool produces one.

**Captures.** `--vehicle <id>` drives a shipped assembly and writes six frames. `ModelRenderer` grew
model/instance separation, a per-instance `u_model`, and a texture cache keyed by model as well as
image — without that last one a wheel wore the chassis's paint.

## Rationale / Context
Three sessions calibrated a car nobody had looked at, and every figure passed. The lesson worth
carrying is the shape of the failure rather than either bug: tests that measure the right quantities
against a scene that was never rendered will agree with each other indefinitely.

## Impact
`game-core` (`asset`, `ecs`, `physics`, `system`, `vehicle`), `assets/parts/`, `assets/vehicles/`,
`test-environment` (`render`, `vehicle`, `VerifyMain`, `VerifyOptions`), `docs/captures/`.
