# `assets/parts/` — the shared part library

Parts in here are **modular**: authored once, fitted to any vehicle that offers a slot of the
right type. Weapons, utility modules, and universal accessories.

Everything a *particular* vehicle is made of lives under that vehicle instead, in
`assets/vehicles/<vehicleTypeId>/parts/` (`docs/08_asset_pipeline.md#D08-S4.6`). A chassis, a
door, a wheel and a bonnet are cut from one car's art, fitted to that car's slot graph, and
meaningless on any other; a door from a supercar bolted to a pickup is the wrong size, in the
wrong place, with a mass derived from a body it is not on. The asset gate reports that as A315
rather than letting it happen.

A part in here has no such restriction, and that is what makes it modular. Any assembly may
reference it.

## What goes here

| Category | Slot type it needs | Example |
|---|---|---|
| `WEAPON` | `HARDPOINT` or `TURRET_MOUNT` | an autocannon, a rocket pod, a mortar |
| `UTILITY` | `HARDPOINT` | a radar, a cloak, an ammo feed, a radiator |
| `DECORATIVE` | `ACCESSORY` | a roof light bar, a bull bar |

A weapon carries a `weapon` block naming its family and muzzle; a module carries a `module` block
naming its family and charges (`docs/08_asset_pipeline.md#D08-S4.2`). Both are read by the
runtime loader, and a block on a part of the wrong category is a validation error.

Every prepared vehicle offers five mounting points — `turret_main`, `hardpoint_bonnet`,
`hardpoint_rear`, `hardpoint_flank_l` and `hardpoint_flank_r` — derived from its own body box and
rated at 8% of its kerb mass (`docs/15_vehicle_preparation_pipeline.md#D15-S10`). Each vehicle's
`parts/manifest.json` lists them with their positions and capacities.

## Built-in weapons are not shared

A tank's main gun is geometry that came with the tank's model. It is prepared like any other part
of that vehicle, lives under it, and gets its `weapon` block derived from its own shape
(`D15-R41`). Only the parts a player can move between vehicles belong here.

## The directory is empty

Deliberately, for now. Weapon and module content is authored separately; this is the shape it
arrives into.
