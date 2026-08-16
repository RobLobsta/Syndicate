# DISC-060: the cannon is a pedestal gun, and its cogs turn across the bore

**Date:** 2026-08-16
**Category:** discoveries
**Related Docs:** docs/17_weapon_system.md#D17-S5.6, docs/17_weapon_system.md#D17-S5.7

**Status:** active

## Summary
`steampunk_cannon.glb` was read as a field piece on a wheeled carriage, and D17-R27 was written to
discard the carriage as scenery. It is not a carriage. It is a **pedestal deck gun**: a square base
plate, a column, and two toothed cogs that elevate the barrel. The rule threw away the mount and both
gears — the parts most worth keeping — and no cue would have caught it, because the geometry that
identifies a cog reads as noise in the bore plane.

## Details
A Cycles render settled it in one frame, after several sessions of reasoning from bounding boxes.
The "road wheels" are a pair of cogs mounted **across** the bore, so:

- Their roundness *in the plane perpendicular to the bore* — the test the first gear cue used —
  measures 0.24. They are round about their own axle, which is at right angles to the bore. The disc
  test now runs on the shell's own extents and both cogs score above 0.80 (D17-R36b).
- The base is 50% of the gun's length off the bore, where the machine gun's side bracket is 4%. No
  absolute threshold classes both, so the mount cue became a *relative* share of the model's own
  mounting direction (`MOUNT_SHARE_MIN`, D17-R36a).
- `CARRIAGE_RADIUS = 0.40` became `STRAY_RADIUS = 1.60`, which discards detached scenery and keeps
  everything structurally attached.

Two further corrections came from the same look. The bore line was being fitted to a 174-triangle
conduit strapped along the barrel, so the fit is now weighted by triangle count and run through the
forward-most round section. And mirrored pairs harmonise their labels rather than voting separately,
which stopped one cog being a `gear` and the other `furniture`.

The machine gun needed the mirror image of this attention: its **side bracket** makes it a flank
weapon, and the model flips cleanly for the other side. `--mirror` reflects across the bore plane and
reverses winding, the flank slots are rolled ±90° about Z, and the Eclipse carries a right-hand gun
and its mirror on the left (D17-R26a).

## Rationale / Context
Every wrong turn in this file has the same shape: a decision about what a model *is*, made from
numbers rather than from looking at it. CLAUDE.md already says looking at it counts as a check, and
this is the third entry to prove it (DISC-051, DISC-057). A 40-second Cycles render is cheaper than
one wrong classification rule, and far cheaper than a rule that deletes geometry.

## Impact
- D17-R27's carriage rule is gone. The cannon ships with its base plate, column and both cogs; the
  cogs carry an `ELEVATE` articulation driven by `AIM`.
- The cannon is seven sub-parts and 178 kg, not six and 274 kg with the mount discarded.
- `--mirror` exists, and `weapon_machinegun_l_01` is the first asset produced by it.
