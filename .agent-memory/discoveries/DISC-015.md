# DISC-015: a Bullet contact point carries the compound child index in getIndex0/getIndex1

**Date:** 2026-08-10
**Category:** discoveries
**Related Docs:** docs/07_damage_destruction_model.md#D07-S5.1, docs/06_physics_simulation.md#D06-S5.3

**Status:** active

## Summary
`btManifoldPoint.getIndex0()` and `getIndex1()` are the compound child indices of the two bodies in a
contact — the "which part was hit" that D07-S5.1's hit resolution is built on. Nothing in gdx-bullet's
API names them that; they are `m_index0`/`m_index1`, set by `btCompoundCollisionAlgorithm` through
`setShapeIdentifiersA/B`, and their meaning depends on what kind of shape the body has.

## Details

**Where the value comes from.** Bullet's compound algorithm calls
`m_resultOut->setShapeIdentifiersA(-1, childIndex)` when the compound is body 0 and
`setShapeIdentifiersB` when it is body 1, so the child index arrives on whichever half of the
manifold point corresponds to the compound. For a triangle-mesh shape the same fields hold a triangle
index instead, and for a plain convex shape they hold `-1`.

**Why this matters here.** A vehicle is one rigid body with a compound of its parts' hulls (DEC-004),
so the compound child index is the only thing a contact carries that identifies a part.
`VehicleCompound` maps it to a slot path (D06-S5.3), and without it every collision would have to
fall back to the nearest-part-centroid path of D07-E13 — which works, but resolves a hit on the front
bumper to whichever part's origin happens to be closest, and would have made the fallback the normal
case rather than the defensive one.

**What gdx-bullet does not expose.** A ray test's `LocalRayResult` carries the same information in
`m_localShapeInfo`, and gdx-bullet's `ClosestRayResultCallback` does not surface it. Projectile hits
therefore have no child index and go through the centroid fallback deliberately — which is why
`HitResolution` logs the "stale index map" error only when a caller supplied an index and it
addressed nothing, rather than on every shot.

**Also worth knowing:** the fields are only meaningful on freshly generated contact points. Reading
them after the step, from `getDispatcher().getManifoldByIndexInternal(i)`, is fine; caching them
across a structural change is not, because compound child indices shift on removal (D06-R14).

## Rationale / Context
D07-S5.1's pseudocode says `contact.childIndexOnThisObject` and Bullet has no such member. A session
that could not find it would reach for the centroid fallback of D07-E13 as the primary path, which
works well enough to look right and resolves a bumper hit to whichever part's origin is nearest —
the exact failure the compound index exists to prevent, and one no test would catch without a fixture
whose parts are deliberately offset from their geometry.

## Impact
`game-core` `system` (`CollisionEventSystem`), `damage` (`HitResolution`).
