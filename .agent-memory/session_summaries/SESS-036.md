# SESS-036: the garage arms the car

**Date:** 2026-08-16
**Category:** session_summaries
**Related Docs:** docs/01_product_game_design.md#D01-S2.2, docs/17_weapon_system.md#D17-S4.5

**Status:** active

## Summary
Three corrections and one feature. The cannon was already HEAVY and needed nothing. The machine gun's
flank mounting was wrong for the third time and is now right. And the player can choose which weapon
goes on which hardpoint, before a match, in the garage.

## Details
**The mounting.** Rendering all four candidate rolls settled in four minutes what two sessions of
algebra got wrong: 0° sinks the gun into the door, ±90° dangles its brackets in free air, 180° seats
it. No derivation was going to find that, because `syndicate_weapon` normalises the bore and the
origin and never normalises **which way the mount face points** — so the roll is a property of the
weapon, not of the slot (DISC-061). D17-R26b says so and names the fix.

**The garage.** D01-NG1 ruled out a part-by-part editor and still does; D01-NG1a opens hardpoints
only. The boundary is what makes it cheap: a mounting already has a position, a size class and a mass
ceiling, so a loadout is checked against rules that exist. A chosen loadout becomes a real
`AssemblyDef` under a `_fitted` id, registered in a second map the catalogue does not see — bots draw
from the same index, and registering a player's vehicle in the roster would have armed every bot that
picked that car (DEC-084).

`*.weapon.json` is now a runtime input for the tree: fitting a weapon that is on no vehicle means
constructing its subtree, and a slot says where a child attaches without saying which child (D17-R16,
amended). And a mounting is only offered if it is free or already holds a weapon — `HARDPOINT` takes
utility parts too, and without that test the garage offered to bolt a machine gun to each brake hub,
which the 22 kg ceiling would have allowed.

**Looking at it** caught four things no test would have: the hub slots, an em-dash the menu font
renders as tofu, two-line rows overlapping, and a stat panel reporting the shipped part count beside
a car with ten more parts on it.

## Rationale / Context
`--garage-row` and `--fit` exist because a headless capture has no keyboard, and without them the
half of the garage that moves is the half nobody can check. That is the same reason `--vehicle`
exists, and the same lesson as DISC-051 and DISC-060: on this project, the check that finds the
defect is a screenshot.

## Impact
- `WeaponDef`, `WeaponLoadout`, the `*.weapon.json` loader, `A222`, a second index map.
- D01-NG1a, D08-A222, D17-R16 and D17-R26b amended; `RUNNING_THE_CLIENT.md` documents both flags.
- `WeaponLoadoutTest` — eleven cases against the shipped tree, not a fixture.
- Not done: the loadout is not persisted between runs, and no bot ever varies its own.
