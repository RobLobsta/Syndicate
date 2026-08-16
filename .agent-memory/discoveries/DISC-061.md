# DISC-061: the bore is normalised and the mount normal is not

**Date:** 2026-08-16
**Category:** discoveries
**Related Docs:** docs/17_weapon_system.md#D17-S5.2, docs/15_vehicle_preparation_pipeline.md#D15-S5.10

**Status:** active

## Summary
`syndicate_weapon` fixes a weapon's bore to +Z and its origin to the mount face, and stops there. It
never fixes **which way the mount face points**. That leaves the roll a hardpoint needs a property of
the weapon rather than of the slot, and it is why the flank machine gun took three attempts to seat:
0° buries it in the door, ±90° dangles its brackets in free air, and 180° is the one that seats it.

## Details
D17-R24 and R25 are precise about two of the three degrees of freedom. The third — rotation about the
bore — is unconstrained, so the mount normal comes out wherever the source model happened to put it.
For a pintle gun that is −Y, the gun sitting on top of its mount. For the shipped machine gun, whose
mount is a **side bracket**, it is −X.

The flank hardpoint rolls the weapon about the vehicle's longitudinal axis, which is the bore. So:

| Roll | What it does to a −X mount normal | How it looks |
|---|---|---|
| 0° | mount faces outboard | the gun is inside the door, barrel poking out |
| ±90° | mount faces up or down | brackets hang in the air, receiver half-buried |
| 180° | mount faces inboard | brackets against the door, gun proud of it |

`FLANK_ROLL_DEG` is therefore 180° for both flanks — correct for the one flank weapon that exists,
and wrong for the first pintle gun that arrives.

The fix is to normalise the mount normal to −Y during frame correction, at which point every weapon
bolts "downward" onto its slot and the flank roll is a plain ±90° for all of them. Not done: it
re-exports both shipped weapons and every seam position with them, and there is exactly one flank
weapon to validate it against. D17-R26b records it as an open gap rather than leaving a magic
constant unexplained.

## Rationale / Context
Two sessions running, the same failure mode: a rotation that reads plausibly on paper and is wrong on
screen. The first fix flipped ±90° to ∓90° from a correct derivation of `Rz(θ)·(+Y)` — correct
algebra, wrong premise, because the mount normal was never +Y. Reasoning about a frame nobody
normalised produces confident answers at a rate unrelated to how often they are right.

Rendering all four candidate angles and looking took four minutes and settled it with no algebra at
all.

## Impact
- `FLANK_ROLL_DEG` is 180° on both flanks; both shipped chassis updated to match.
- D17 gained R26b naming the gap and the fix, so the next weapon does not rediscover it.
- The general rule: when a placement constant is model-dependent, say so at the constant. A number
  that happens to work reads exactly like a number that is derived.
