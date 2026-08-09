# Assets — where content lives, and where your models go

This is the game's shipped content. Everything here is data: JSON the simulation reads, and the
meshes those files point at. Nothing in this directory is compiled.

The full schemas are `docs/08_asset_pipeline.md#D08-S4.2` (parts), `#D08-S4.3` (materials) and
`#D08-S4.4` (assemblies). This file is the practical version.

---

## Layout

```
assets/
├── materials/materials.json          one table, the authority for density (D08-R7)
├── parts/<partTypeId>/               one directory per part type
│   ├── part.json                     mass, health, stats, handling, slots
│   ├── mesh.glb                      ← YOUR MODEL GOES HERE
│   ├── shards.glb                    the fractured version (Blender tool output)
│   └── fracture_manifest.json        shard masses and placements (Blender tool output)
├── vehicles/<vehicleTypeId>/
│   └── assembly.json                 which parts go in which slots
├── arenas/                           not implemented yet
└── balance/                          not implemented yet
```

---

## Where to put a car model

**One `.glb` per part directory, always named `mesh.glb`.** A vehicle is not one model — it is a
chassis and four wheels, each its own part with its own mass, health and damage state. That is the
whole point of the game: parts come off individually.

For the two shipped vehicles:

| Put this model here | For |
|---|---|
| `assets/parts/chassis_apex_gt_01/mesh.glb` | Apex GT body, no wheels |
| `assets/parts/wheel_apex_front_01/mesh.glb` | Apex GT front wheel, one wheel |
| `assets/parts/wheel_apex_rear_01/mesh.glb` | Apex GT rear wheel, one wheel |
| `assets/parts/chassis_stampede_gt3_01/mesh.glb` | Stampede GT3 body, no wheels |
| `assets/parts/wheel_stampede_front_01/mesh.glb` | Stampede GT3 front wheel |
| `assets/parts/wheel_stampede_rear_01/mesh.glb` | Stampede GT3 rear wheel |

The directories already exist with their `part.json` in place, so a model dropped in is picked up
with no further wiring.

### What the file must contain

`part.json` names two things inside the `.glb`:

```json
"assets": {
  "visualMesh": "mesh.glb",
  "collisionSource": "mesh.glb#node=chassis_apex_gt_01_col"
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

**Meshes do not load yet.** `game-core` has no headless glTF reader (`DEV-010` in
`.agent-memory/spec_deviations/`), so a part reports `A503` at load and is skipped. Everything else
in the chain works — the JSON is read, validated, and the vehicles drive correctly in tests against
stand-in box hulls. The reader is the first item in Phase 6 of `ROADMAP.md`, and it is the single
thing between a `.glb` in one of these directories and a car on screen.

So: put your models in now. They will be read the moment the reader lands, and nothing else has to
change.

---

## Adding a vehicle

1. Add a profile to `VehicleProfiles` in `game-core` with its researched figures.
2. Create `assets/parts/<chassis>/part.json` and the wheel parts, and
   `assets/vehicles/<id>/assembly.json`.
3. Run `./gradlew :game-core:test`. `VEHICLES.md` is regenerated and the test fails until you commit
   it; the content tests fail if the JSON disagrees with the profile; the calibration tests fail if
   the vehicle does not reproduce the performance you claimed for it.
