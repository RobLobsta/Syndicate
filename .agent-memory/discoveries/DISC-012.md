# DISC-012: Bullet reads setBrake as an impulse and applyEngineForce as a force

**Date:** 2026-08-09
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.5, docs/06_physics_simulation.md#D06-S5.6

**Status:** active

## Summary
`btRaycastVehicle::updateFriction` multiplies `m_engineForce` by the timestep and uses `m_brake`
directly as a maximum impulse. Passing a force to `setBrake` therefore brakes `1/TICK_DT` — sixty
times — too hard.

## Details
**The asymmetry**, in `btRaycastVehicle::updateFriction`:

```cpp
if (engineForce != 0.f) {
    rollingFriction = engineForce * timeStep;          // a FORCE, integrated here
} else {
    btScalar maxImpulse = wheelInfo.m_brake ? wheelInfo.m_brake : 0.f;   // an IMPULSE, used as-is
    rollingFriction = calcRollingFriction(contactPt, numWheelsOnGround);
}
```

Note also that the brake is only consulted when the engine force is exactly zero, so a vehicle
coasting with a trace of throttle does not brake at all.

**The symptom.** A vehicle stopped dead from any speed, in roughly a car length. It reads as brakes
that are too strong, so the instinct is to lower `brakeForceN` — which produces a number that is
wrong by a factor of the tick rate and silently changes meaning if the tick rate ever does.

**The fix.** Multiply by the step at the call site: `setBrake(force · dtSeconds, index)`. With that,
a brake force derived from a car's published 100-0 distance produces roughly that distance.

D06-R22 warns about exactly this confusion for a different field — "the breaking threshold is an
IMPULSE (N·s), not a force… confusing the two by a factor of `TICK_DT` (60x) is the single most
likely bug here". It is the most likely bug in the brake too, and D06-S5.5's control loop reads
`ctrl.setBrake(input.brake * stats.brakeForceN / vehicle.wheels.size, w.wheelIndex)`, which is the
wrong units as written.

**A second finding, from the same investigation.** A wheel can report `m_raycastInfo.m_isInContact`
while carrying no load at all — its suspension sitting at full extension, just touching. Such a wheel
generates no traction and no braking, so a vehicle whose geometry leaves two wheels unloaded drives
and brakes on two wheels while every contact check says four. That is what a test fixture in
`VehicleControlSystemTest` turned out to be doing, and it is why counting wheels in contact is not a
measure of whether a vehicle is properly on its wheels; summing `getWheelsSuspensionForce()` is.

## Rationale / Context
The units are undocumented in Bullet, asymmetric between two adjacent setters on the same object, and
wrong in the blueprint's own pseudocode. Any of the three would cost an afternoon; together they cost
one twice, because fixing the brake without knowing about the load quirk makes a fixture that used to
"work" start failing for a reason that has nothing to do with the change.

## Impact
`game-core` `system` (`VehicleControlSystem`). Affects D06-S5.5's control loop, and the same
force-versus-impulse question will arise for `attachBreakable`'s break threshold (D06-R22) and for
any future handbrake or drivetrain braking.
