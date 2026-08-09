# DISC-010: Bullet freezes a body's deactivation clock the moment it falls asleep

**Date:** 2026-08-09
**Category:** discoveries
**Related Docs:** docs/07_damage_destruction_model.md#D07-S5.8, docs/06_physics_simulation.md#D06-S5.10

**Status:** active

## Summary
`btCollisionObject::getDeactivationTime()` accumulates while a body is below the sleeping thresholds
and stops the instant the body reaches `ISLAND_SLEEPING`, so it plateaus at Bullet's own
`gDeactivationTime` of 2 s and can never reach the 3 s `SLEEP_DESPAWN_S` asks for. `LifetimeSystem`
advances it itself for sleeping bodies.

## Details
D07-S5.8 words the early-despawn test as:

```
slept = e.hasRigidBody and e.body.isDeactivated()
        and ticksSince(e.body.deactivationTick) > SLEEP_DESPAWN_S (3.0) * TICK_RATE_HZ
```

There is no `deactivationTick`. The nearest thing Bullet has is `m_deactivationTime`, and
`btRigidBody::updateDeactivation` handles it like this:

```cpp
void btRigidBody::updateDeactivation(btScalar timeStep) {
    if ((getActivationState() == ISLAND_SLEEPING) || (getActivationState() == DISABLE_DEACTIVATION))
        return;                                     // <-- stops counting exactly when it matters
    if (velocities are below the sleeping thresholds) m_deactivationTime += timeStep;
    else { m_deactivationTime = 0; setActivationState(ACTIVE_TAG); }
}
```

A body goes `ACTIVE_TAG` → (2 s of stillness) → `WANTS_DEACTIVATION` → `ISLAND_SLEEPING`, and from
that point the clock is frozen at roughly 2. Reading it and comparing against 3 therefore *never*
fires — the sleep path would be dead code, and every shard would serve out its full
`DEBRIS_LIFETIME_S` while looking like the budget was working.

**The fix.** `LifetimeSystem.hasSleptLongEnough` reads the state, and when it is `ISLAND_SLEEPING`
adds `dtSeconds` to the deactivation time and writes it back. This is safe and has no simulation side
effect: Bullet reads the value only to decide when to deactivate a body that is already deactivated,
and `btCollisionObject::activate()` zeroes it on reactivation regardless of what is stored. That
zeroing is the reason to keep the clock on the body rather than in the system — it gives "continuous
sleep duration, reset on wake" for free, it is per-body, and it survives a client rewind across a
landing, which a remembered value in a stateless system (D04-R3) would not.

## Reproduction
Spawn a debris body, force `setActivationState(ISLAND_SLEEPING)` and `setDeactivationTime(0)`, then
step. Without the write-back, `getDeactivationTime()` reads 0 forever and the body is never retired
early; with it, the body is destroyed on the first tick at or past `SLEEP_DESPAWN_S`.
`LifetimeSystemTest.aSleepingBody_isRetiredEarlyUnderSleepThenDestroy` is that test.

## Rationale / Context
Without this, the write-back looks like gratuitous mutation of Bullet state and the next session
removes it — silently disabling D06-R29 and leaving `MAX_DEBRIS_BODIES` recycling to do the job
again, which is exactly the situation slot 16 was written to end. The symptom would be a slow drift
in steady-state body count that nobody attributes to a one-line deletion.

## Impact
`game-core` `system` (`LifetimeSystem`). Affects D07-S5.8's `slept` predicate and D06-R29.
