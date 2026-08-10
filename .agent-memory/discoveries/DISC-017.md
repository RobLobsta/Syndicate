# DISC-017: Bullet ray tests a big convex box to about 14 cm

**Date:** 2026-08-10
**Category:** discoveries
**Related Docs:** docs/06_physics_simulation.md#D06-S4.3, docs/06_physics_simulation.md#D06-S5.5, docs/06_physics_simulation.md#D06-S5.2

**Status:** active

## Summary
`btCollisionWorld::rayTest` finds a convex shape with an iterative subsimplex cast, not an analytic
intersection, and its accuracy degrades with the shape's size. Against the test scene's 120 × 2 × 800 m
ground box each suspension ray came back with a contact point up to **0.14 m** above the surface,
differently every tick. A `btStaticPlaneShape` at the same place is exact.

## Details

**The symptom.** A settled car's body origin was stable to a centimetre while its four wheel
transforms jumped at random through 0.35–0.57 m — the full suspension travel, sixty times a second,
with all four wheels reporting contact throughout. It reads as a spring that will not damp.

**What it actually was.** `btWheelInfo.m_raycastInfo.m_contactPointWS.y` should be 0.000 on flat
ground at y=0. Logged per tick it was 0.001, 0.099, 0.143, 0.030, 0.136 — always on the road body
(the ground-object pointer never changed, so this was not the vehicle hitting its own compound, which
was the first hypothesis and the wrong one). The suspension length follows the contact, so the wheel
is placed wherever the noise put it.

**The proof.** Shrinking the same box to 12 × 1 × 40 m made every contact 0.000 and every suspension
length constant. Replacing it with a plane at full size did the same. Size is the variable.

**Why it matters here more than elsewhere.** Contact generation between bodies does not go through
this path — box-vs-box is exact. Only ray tests do, and the ray-cast vehicle of D06-S5.5 is built
entirely on one ray per wheel per tick. So the one shape in the world a vehicle depends on most is
the one this affects.

**The fix.** Ground surfaces are `btStaticPlaneShape` (`ShapeCache.planeFor`): `ArenaFactory`'s floor
and `ShippedContentScene`'s road. Walls stay convex hulls — nothing ray-casts at them. D06-S4.3
already specifies `btBvhTriangleMeshShape` for arena static geometry, which has the same exact ray
test; a plane is the placeholder-arena form of the same property, not a departure from it.

## Rationale / Context
Four sessions of vehicle tuning happened on top of this. Every calibration figure in `VEHICLES.md`
was measured against a ground that moved under the wheels by up to 14 cm, and they still came out
within tolerance, which is why nothing found it: the noise averages out over a 0-100 run and only
shows in a static pose. The next person to see wheels jitter will reach for the spring constants, and
DEC-034 will tell them those numbers are calibrated and not to be touched.

## Impact
`game-core` `physics` (`ShapeCache.planeFor`, `ArenaFactory.floor`), `ShippedContentScene`, and
`RideHeightTest`, which is the test that made the symptom visible. Anything that later gives an arena
a real collision mesh inherits the exact ray test for free.
