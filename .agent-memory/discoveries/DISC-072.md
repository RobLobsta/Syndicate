# DISC-072: a ground probe cannot be a downward ray from a body that is already resting

**Date:** 2026-08-18
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S5.5, docs/06_physics_simulation.md#D06-S4.4

**Status:** active

## Summary
Both obvious ray origins for "how far is this body above the ground" fail for a body already sitting
on it, and each fails silently by reporting *airborne*. The working answer is the dispatcher's
contact manifolds, which is why `PhysicsWorld.isTouchingStatic` exists and no `groundClearanceM`
does.

## Details
Two attempts, both written and both measured before the third worked.

**From the hull's lowest point.** The world AABB's floor is below every point of the body, so the ray
can never hit the body itself — which is why it looks right. But a body at rest has its lowest point
*at* the contact surface, so the origin is on or just inside the ground, and Bullet's convex ray cast
returns **no hit from inside a shape**. The probe reported no ground under a helicopter standing on
it. The symptom was a fix that changed nothing: the same slide distance, to the last decimal, as the
run before it.

**From the centre of mass.** Now the origin is inside the body's own compound, and the first thing
the ray can find is a child of the shape it was cast from. A hull that does not contain the COM —
skids, a low fairing, a rotor blade at extreme cyclic — is hit at a few centimetres and reads as
ground, so an aircraft in level flight reports itself landed. Worse than the first failure, because
it is attitude-dependent and would appear in flight rather than at rest.

**Contact manifolds.** `btDispatcher.getNumManifolds()`, matched on `getCPointer()`, other side
`isStaticObject()`. Exact, no origin, no self-hit, no threshold, and free — the manifolds are already
built by the step. One tick stale for a caller in a slot before physics (10), which is the same
freshness the collision damage of slot 11 has.

## Rationale / Context
The obvious tool for "distance to the ground" in a physics engine is a ray, and both failures look
like tuning problems rather than category errors — the first presents as a threshold that needs
loosening, the second as a filter that needs tightening. A future session adding ground effect,
terrain-relative flight, or a landing gear state will reach for the same ray, and reading this is
much cheaper than measuring both dead ends again.

The narrower lesson: **a fix that produces a byte-identical measurement has not run.** That is what
identified the first failure, and it is a cheaper signal than any amount of reasoning about it.

## Impact
`game-core` `physics` (`PhysicsWorld.isTouchingStatic`), `vehicle` (`RotorControl.isFlying`). If a
continuous clearance is ever genuinely needed — ground effect scales with height — it has to come
from the terrain field (`PhysicsWorld.terrain().heightAt`) plus the hull's own AABB, not from a ray.
