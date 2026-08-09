# DISC-011: Bullet ray tests are locked to DefaultFilter, so a wheel finds no ground

**Date:** 2026-08-09
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S4.4, docs/06_physics_simulation.md#D06-S5.5

**Status:** active

## Summary
`btDefaultVehicleRaycaster` builds its `ClosestRayResultCallback` internally, on filter group
`btBroadphaseProxy::DefaultFilter` (`1<<0`), and offers no way to change it. Bullet's filter test is
bidirectional, so a body whose mask omits that bit is invisible to the suspension ray — and
D06-S4.4's `STATIC` mask omits it.

## Details
**Symptom.** A vehicle spawns correctly, has the right mass, centre of mass and wheel indices, and
its controller reports the engine force the control system applied. It then sinks through its own
suspension and rests on the chassis hull, and full throttle moves it nowhere.
`btWheelInfo.getRaycastInfo().getIsInContact()` is false on every wheel, on every tick, over solid
ground. Nothing in the vehicle code is wrong.

**Cause.** `btCollisionWorld::rayTest` consults the callback:

```cpp
bool needsCollision(btBroadphaseProxy* proxy0) const {
    bool collides = (proxy0->m_collisionFilterGroup & m_collisionFilterMask) != 0;
    collides = collides && (m_collisionFilterGroup & proxy0->m_collisionFilterMask);
    return collides;
}
```

Both directions must hold. The callback is group `DefaultFilter` (1), mask `AllFilter` (−1). The
ground is added on `CollisionLayer.STATIC`: group `1<<0`, mask `VEHICLE|PROJECTILE|DEBRIS|PROP` =
`0b11110`. The first line passes; the second is `1 & 0b11110 == 0`, and the ray misses.

The inverse case is what makes this hard to spot: a `VEHICLE`-layer body *is* found, because
`VEHICLE`'s mask contains `STATIC.bit`, which is numerically the same bit as `DefaultFilter`. So the
suspension ray sees other vehicles and not the floor.

**Fix.** `PhysicsWorld.addBody(body, layer)` ORs `DefaultFilter` into every mask (DEV-012). No pair
outcome changes: the bit is `STATIC`'s, so the only pairing newly admitted is static against static,
which `btCollisionDispatcher` rejects before a manifold exists.

**This will recur.** D11's bot sensors are specified on `LAYER_SENSOR_RAY` (`1<<6`). A
`ClosestRayResultCallback` configured with that group hits the same wall from the other side, because
no layer's mask contains `SENSOR_RAY.bit`. A sensor ray must be issued with group `AllFilter` and
mask `SENSOR_RAY.mask()` — the ray is not a body, so it has no layer others need to opt into.

## Rationale / Context
Every plausible first suspicion is wrong here: the wheel radius, the suspension rest length, the
compound's centre of mass, the connection points after recentring, whether the chassis body is
asleep, whether the controller was added as an action. All of them are checkable and all of them
check out. Without this entry the next session to add a ray — bot sensors, hitscan weapons, a ground
check — spends the same hours, and may "fix" it by moving a body onto a different layer, which
changes pair collision to work around a ray-filtering problem.

## Impact
`game-core` `physics` (`PhysicsWorld`). Affects D06-S4.4's masks (see DEV-012, and the amendment made
to D06-S4.4 in the same commit), D06-S5.5's vehicle model, and every future ray test — D06-S5.9's
hitscan projectiles and D11-S5.2's sensor model above all.
