# PROG-037: content — weapons that degrade as they are shot apart

**Date:** 2026-08-17
**Category:** progress
**Related Docs:** docs/17_weapon_system.md#D17-S5.13, docs/04_entity_component_model.md#D04-S4.4

**Status:** active

Supersedes: PROG-035

## Summary
D17-S5.13 is implemented. A weapon's sub-parts now **matter**: shoot the barrel off and accuracy
collapses and range halves, take the breech and the fire rate halves, take the receiver and the gun
stops. Everything else PROG-035 recorded is unchanged.

## Details

**Scope:** `game-core` `vehicle` (`WeaponSubPart`, `WeaponSubPartDegradation`), `system`
(`VehicleStatsSystem` slot 6, `WeaponSystem` slot 8), `component` (`WeaponControllerComponent`).

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Sub-part degradation table (D17-S5.13) | done | Nine labels, evaluated in slot 6, read in slot 8 |
| Weapon fracture manifests | not_started | Needs Blender, unavailable here; a dead gun still detaches whole |
| The other six families | not_started | Only AUTOCANNON and CANNON have content |
| Balance of the numbers | not_started | Every figure is a D01 default |

**The label is read off the slot id.** `syndicate_weapon` hangs each sub-part on a `SUBSLOT` named
`sub_<label>` (D17-S5.8), so the slot graph already carries the taxonomy and no new asset field was
needed. A trailing `_l`/`_r` marks a mirrored pair — the cannon's two cogs — and is stripped.

**A loss is an empty declared sub-slot, not a `DESTROYED` entity.** A destroyed sub-part usually
detaches in the same tick and leaves the graph (D07-S5.7), so a walk over present entities would see a
gun regain its accuracy the moment its barrel finished falling. The walk starts from what the mount's
`PartType` declares and treats an unoccupied slot as a loss — and does not descend past one, because a
receiver's children went with it.

**No new mechanism**, as D17-R61 requires: the penalties are `mul` terms folded onto the mount's
effective stats in slot 6, the same way a utility module already modifies another part's numbers.
Slot 8 reads `effectiveRangeM` and one boolean and knows nothing about the table.

Verified against the shipped Eclipse, not only synthetically: `WeaponSubPartLossTest` spawns the real
car, destroys `sub_barrel` beneath its flank machine gun, and asserts range halves, spread quadruples,
and the gun **keeps firing** (D17-R62).

## Rationale / Context
PROG-035 called this "the single highest-value thing left" and ROADMAP said it was what stood between
a loadout screen and a loadout *decision*: until it landed, every gun that fitted a mounting was
strictly better than leaving it empty, and a damaged gun was as good as a new one.

## Impact
- `WeaponControllerComponent` gains `baseRangeM`, `effectiveRangeM`, `ammoCapacity` and
  `disabledBySubPartLoss`.
- Projectile and hitscan range now come from the controller rather than from the weapon block, so a
  shortened range reaches both paths.
- DEC-085 records the one number D17-S5.13 leaves unauthored.
