# Assets — where content lives, and where your models go

This is the game's shipped content. Everything here is data: JSON the simulation reads, and the
meshes those files point at. Nothing in this directory is compiled.

The full schemas are `docs/08_asset_pipeline.md#D08-S4.2` (parts), `#D08-S4.3` (materials) and
`#D08-S4.4` (assemblies). This file is the practical version.

---

## Layout

```
assets/
├── materials/
│   ├── materials.json                one table, the authority for density (D08-R7)
│   └── style.json                    the house style: palette, tone band (DEC-076, DEC-079)
├── parts/<partTypeId>/               the SHARED library — weapons, modules, accessories
│   ├── part.json                     mass, health, stats, handling, slots
│   ├── mesh.glb                      ← YOUR MODEL GOES HERE
│   ├── shards.glb                    the fractured version (Blender tool output)
│   ├── fracture_manifest.json        shard masses and placements (Blender tool output)
│   └── <weaponId>.weapon.json        a modular weapon's sub-part tree (D17-R16)
├── vehicles/<vehicleTypeId>/
│   ├── assembly.json                 which parts go in which slots
│   └── parts/                        this vehicle's OWN parts — chassis, doors, wheels
│       ├── manifest.json             every part it is made of, with mass and stats
│       └── <partTypeId>/             same four files as above
├── arenas/<arenaId>/arena.json       bounds, spawns, theme and seed (D08-S4.7, D16-S4)
├── audio/                            the synthesised sound bank (DEC-046, DEC-056)
├── fonts/                            Oswald, for menu type (DEC-072)
├── input/bindings.json               keyboard and gamepad tuning (DEC-048)
└── balance/                          class power budgets, bot difficulty
```

---

## Where a part lives

**One `.glb` per part directory, always named `mesh.glb`.** A vehicle is not one model — it is
twenty-odd parts, each with its own mass, health and damage state. That is the whole point of the
game: parts come off individually.

There are two places a part directory can be, and which one it is says what the part is for
(`docs/08_asset_pipeline.md#D08-R14b`):

| Where | What belongs there | Who may use it |
|---|---|---|
| `assets/vehicles/<vehicleTypeId>/parts/<partTypeId>/` | A chassis, door, wheel, bonnet — cut from that car's art | only that vehicle |
| `assets/parts/<partTypeId>/` | A weapon, a utility module, a universal accessory | any vehicle |

The two shipped vehicles' parts are produced by `./gradlew :blender-tool:prepareVehicles` from the
models in `art-source/vehicles/`, and each vehicle's `parts/manifest.json` lists everything it is
made of with the mass, stats and destruction behaviour of every piece. Nothing loads that file; it
is there to be read.

### What the file must contain

`part.json` names two things inside the `.glb`:

```json
"assets": {
  "visualMesh": "mesh.glb",
  "collisionSource": "mesh.glb#node=chassis_eclipse_01_col"
}
```

So one file holds **both** the visual mesh and a separate low-poly collision node named
`<partTypeId>_col`. Keep the collision node simple — it becomes a convex hull, and Bullet reduces
any hull to at most 42 vertices anyway (`DISC-009`), so detail beyond a rough box or a dozen planes
is thrown away.

### Conventions that matter

| Rule | Why |
|---|---|
| **Y is up, +Z is forward, +X is right**, metres | `D00-R16`. Blender is Z-up, so export with Y-up conversion. |
| **Model origin = the part's attachment point** | For a chassis, the origin sits at the wheel-centre plane on the centreline. For a wheel, the origin is the wheel centre, with the axle along X. |
| **Real-world scale** | The shipped cars are 4.7 m long. Mass comes from `part.json`, not from the mesh, but the collision hull has to match the art or the vehicle sits wrong. |
| **A wheel's radius comes from its mesh** | Half the larger of the Y and Z extents (`DEC-022`). Get the tyre diameter right and the ride height follows. |
| **Morph targets named `dmg_25`, `dmg_50`, `dmg_75`, `dmg_100`** | The four damage states (`D07-S5.5`). Zero or exactly four. |

### Wheel slots are where the wheels go

The chassis `part.json` declares four wheel slots at the wheel centres in chassis-local space:

```json
{ "slotId": "wheel_fl", "slotType": "WHEEL",
  "localPosition": { "x": -0.84, "y": 0.0, "z": 1.35 }, "maxMassKg": 60.0 }
```

Those numbers are the real car's track and wheelbase, halved. If your model's wheel arches are
somewhere else, move the slot positions rather than the model.

---

## Current limitation

**Meshes load; there are none to load.** `game-core` reads glTF now — `GltfReader` and
`GltfCollisionMeshSource`, which closed the gap `DEV-010` recorded — so a `.glb` dropped into one of
the directories above becomes that part's collision hull with no further wiring, and the headless
server reads it at startup. Until one is, each part reports `A503` and is skipped, and the vehicles
drive in tests against stand-in box hulls.

What is missing is the **split**. The two supplied car models are in
`art-source/vehicles/eclipse/` and `art-source/vehicles/stampede/`, and each is one mesh for the
whole vehicle — body and wheels together. A vehicle here is five parts, because parts come off
individually. Cutting one model into a chassis and four wheels, adding a `_col` hull node and the
four damage morph targets, is a Blender job; `art-source/README.md` records what each model measures
so the slot positions can come straight off it.

---

## Adding a vehicle

1. Add a profile to `VehicleProfiles` in `game-core` with its researched figures.
2. Drop the model into `art-source/vehicles/<name>/`, add a `profile.json` beside it with the
   researched figures, and run `./gradlew :blender-tool:prepareVehicles`. It writes
   `assets/vehicles/vehicle_<name>_01/` — the parts, their manifest and the assembly.
3. Run `./gradlew :game-core:test`. `VEHICLES.md` is regenerated and the test fails until you commit
   it; the content tests fail if the JSON disagrees with the profile; the calibration tests fail if
   the vehicle does not reproduce the performance you claimed for it.
