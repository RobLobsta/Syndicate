<!-- D08-S0 --># 08 — Asset Pipeline

**Document ID:** D08
**Owns:** Source art conventions, part/assembly/material/arena file formats, export format choice, asset index, runtime import and validation, error codes.

---

<!-- D08-S1 -->## 1. Purpose

This document specifies how art becomes gameplay data: how `.blend` sources are structured and named, what metadata files describe a part and an assembly, which export format the game consumes and why, how damage morphs and shard meshes are stored and referenced, how the game loads and validates assets at startup, and what every validation failure reports.

It is the contract between the Blender destruction tool (D09), the verification harness (D14), and the runtime (`game-core`).

Requirements are numbered `R1..Rn`, cited as `D08-R7`.

---

<!-- D08-S2 -->## 2. Scope

<!-- D08-S2.1 -->### 2.1 In Scope

- Source `.blend` structure, naming, units, orientation, material conventions.
- Part definition file schema.
- Material definition file schema.
- Assembly manifest schema.
- Arena definition schema (summary; arena authoring detail is content documentation).
- Export format choice and justification.
- How damage morphs and shard meshes are stored and referenced.
- Asset index generation and runtime loading.
- Validation rule catalogue with stable error codes.
- Fallback behaviour and strict mode.

<!-- D08-S2.2 -->### 2.2 Non-Goals

- **NG1.** Blender tool internals — `docs/09_blender_destruction_tool.md#D09-S5`.
- **NG2.** The fracture manifest schema itself — owned by `docs/09_blender_destruction_tool.md#D09-S4.4`; this document specifies how the game *references and validates* it.
- **NG3.** Texture authoring, PBR conventions, LOD budgets for rendering. Rendering assets are validated for existence only.
- **NG4.** Runtime streaming or hot reload. v1 loads everything at startup.
- **NG5.** Localisation and UI assets.
- **NG6.** Audio asset conventions.

---

<!-- D08-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S4.3`/`#D00-S4.4` | Units, coordinate system, axis conversion (R16 of D00) |
| `docs/00_master_index.md#D00-S4.5` | Asset ID grammar |
| `docs/05_vehicle_part_system.md#D05-S4.4` | Part properties that these files carry |
| `docs/06_physics_simulation.md#D06-S4.3` | Shape rules the collision data must satisfy |
| `docs/09_blender_destruction_tool.md#D09-S4.4` | Fracture manifest produced per part |
| External: glTF 2.0, Jackson, JSON Schema draft 2020-12 | Formats and tooling |

---

<!-- D08-S4 -->## 4. Data Contracts

<!-- D08-S4.1 -->### 4.1 Source Art Conventions

**R1.** `.blend` sources live in `art-source/` and are never loaded at runtime.

```
art-source/
├── parts/
│   ├── armor/armor_plate_medium_01.blend
│   ├── wheels/wheel_road_01.blend
│   ├── weapons/weapon_autocannon_01.blend
│   └── chassis/chassis_medium_01.blend
├── arenas/arena_scrapyard_01.blend
└── shared/materials.blend            # material library, linked by parts
```

**R2.** Scene conventions, all mandatory:

| Convention | Rule | Why |
|---|---|---|
| Scale | 1 Blender unit = 1 metre; scene unit system Metric, unit scale 1.0 | D00-R11 |
| Orientation | Blender Z-up (native). Part **forward** is −Y in Blender, which becomes −Z in-game after export conversion | D00-R16: conversion happens once, at export |
| Origin | At the part's attachment point, not its centroid | Makes slot transforms authorable and readable |
| Transforms | All object transforms applied (location 0, rotation 0, scale 1) before export | Non-identity object transforms silently double-apply through glTF |
| Modifiers | All applied. No live modifiers at export | Determinism (G11) |
| Mesh | Watertight (manifold, closed), no loose geometry, no n-gons > 4 sides, consistent outward normals | Volume must be computable (D09-S6.2) |
| Materials | Exactly one material per part mesh; material name is the `materialId` | Density lookup (D09-S6.3) |
| Naming | Object name equals `partTypeId`; collision object named `<partTypeId>_col`; slots are empties named `slot_<slotId>` | Machine-parseable |
| Slots | Blender Empties (Plain Axes), parented to the mesh, at the attachment position and orientation | Slots are authored, not computed |
| Vertex count | Visual mesh ≤ 8000 tris; collision mesh ≤ 200 tris | Budget |
| UVs | One UV layer named `UVMap` | |

**R3.** If a part's collision shape should differ from its visual mesh, a separate `<partTypeId>_col` object provides it. If absent, the visual mesh is used as the hull source. A `_col` object may consist of multiple convex pieces, each becoming a compound child (D06-R7).

**R4.** Slot empties encode `slotId` in their name and `slotType` in a custom property `slot_type`. Optional custom properties: `max_mass_kg`, `covers` (comma-separated slot ids), `articulated` (bool).

<!-- D08-S4.2 -->### 4.2 Part Definition Schema

**R5.** `assets/parts/<partTypeId>/part.json`, validated against `schemas/part_definition.schema.json`.

```json
{
  "schemaVersion": "1.0.0",
  "partTypeId": "armor_plate_medium_01",
  "displayName": "Medium Armour Plate",
  "category": "armor",
  "massKg": 160.0,
  "maxHp": 900.0,
  "armorValue": 45.0,
  "materialId": "steel_hardened",
  "slotTypeRequired": "ARMOR_PANEL",
  "powerCost": 12.5,
  "breakImpulseN": 4000.0,
  "hangsBeforeFalling": true,
  "degradationOverrides": {
    "armorValue": { "profile": "LINEAR", "floor": 0.10 }
  },
  "stats": {
    "armorValue": { "add": 45.0 }
  },
  "handling": {
    "suspensionCompression": 2.4,
    "suspensionDamping": 2.3,
    "rollInfluence": 0.15
  },
  "weapon": {
    "family": "AUTOCANNON",
    "damageType": null,
    "ammoCapacity": -1,
    "blastRadiusM": 0.0,
    "rangeM": 0.0,
    "muzzleLocal": { "x": 0.0, "y": 0.2, "z": 0.9 }
  },
  "slots": [
    {
      "slotId": "deco_mount_01",
      "slotType": "ACCESSORY",
      "localPosition": { "x": 0.0, "y": 0.35, "z": 0.06 },
      "localRotationDeg": { "x": 0.0, "y": 0.0, "z": 0.0, "order": "XYZ" },
      "maxMassKg": 40.0,
      "covers": [],
      "isDetachable": true
    }
  ],
  "assets": {
    "visualMesh": "mesh.glb",
    "shardMesh": "shards.glb",
    "fractureManifest": "fracture_manifest.json",
    "collisionSource": "mesh.glb#node=armor_plate_medium_01_col",
    "morphTargets": ["dmg_25", "dmg_50", "dmg_75", "dmg_100"]
  },
  "tags": ["starter", "medium"]
}
```

**R6.** Field constraints (enforced by schema + semantic validation):

| Field | Type | Constraint |
|---|---|---|
| `schemaVersion` | string | semver; major must match the loader's |
| `partTypeId` | string | `^[a-z][a-z0-9_]{2,63}$` and equal to its directory name |
| `category` | enum | one of the six (D05-S4.2) |
| `massKg` | number | `> 0.01`; must match `fracture_manifest.partMassKg` within `MASS_DELTA_FRAC` |
| `maxHp` | number | `> 0` |
| `armorValue` | number | `>= 0`; must be 0 for `decorative` |
| `materialId` | string | must resolve in the material table |
| `slotTypeRequired` | enum | `SlotType` (D05-S4.3) |
| `powerCost` | number | `>= 0`; warned if >15% from the reference formula (D05-S5.7) |
| `breakImpulseN` | number | `> 0`; **unit is N·s** (D06-R22) |
| `stats` | object | keys must be known stat names (D05-S4.5); `decorative` may declare none |
| `weapon` | object | required on a `weapon` part, absent on every other. `family` is one of the eight of D01-S4.4 and fixes delivery and primary damage type; `damageType` overrides that type or is null; `ammoCapacity` is rounds at spawn or `-1` for unlimited; `blastRadiusM` is 0 for a point hit; `rangeM` is 0 to take the family's D01-S4.4 range; `muzzleLocal` is where shots leave the part. The numbers D01-R8 also names — `baseFireIntervalS`, `damagePerShot`, `spreadRad`, `heatPerShot`, `projectileSpeedMps` — are **stats** rather than fields here, so degradation (D05-S5.4) and utility multipliers (D05-S5.6) reach them. A family is an identity and cannot be a stat, for the same reason `handling` exists (DEC-031, DEC-039). A part in category `weapon` with no block never fires. |
| `handling` | object | optional; every field optional and defaulted from D06-S4.5's reference chassis. A chassis uses `dragCoefficient`, `rollingResistance` and `downforceCoefficient`; a wheel uses `suspensionCompression`, `suspensionDamping`, `rollInfluence`, `suspensionRestLengthM`, `maxSuspensionTravelCm` and `maxSuspensionForceN`. These are properties of one body or one corner rather than contributions to a vehicle-wide sum, which is why they are not stats — adding two chassis together must not add their drag coefficients. A field the loader cannot accept is reported `A210` and the whole block falls back to the reference. |
| `slots[].slotId` | string | unique within the part; `^[a-z][a-z0-9_]{1,31}$` |
| `slots[].localRotationDeg` | object | degrees with explicit `order` (D00-R17) |
| `slots[].covers` | string[] | each must be a `slotId` on the **same** part |
| `assets.*` | path | relative to the part directory; each must exist |
| `assets.morphTargets` | string[] | 0 or 4 entries; if 4, must be exactly the canonical names |

<!-- D08-S4.3 -->### 4.3 Material Definition Schema

**R7.** `assets/materials/materials.json` — a single table, the authority for density, resistance and acoustic family. The Blender tool reads the same file (D09-S6.3), so density is defined once.

**R7a.** A material row answers exactly the questions about what a part is **made of**: `densityKgPerM3`, `resistance`, `fractureBrittleness`, and `audioMaterial` (`D15-S8`). How a part **fails** is not a material property — a chassis rail and a door skin can be the same steel and fail completely differently — so `destructionClass` (`D15-S5.7`) lives on the part, defaulting from its `category`.

**R7b.** `audioMaterial` is deliberately coarser than `materialId`: `METAL`, `GLASS`, `RUBBER`, `PLASTIC`, `COMPOSITE`. Steel, hardened steel and lead are three things to a damage formula and one thing to an ear, and collapsing them is what keeps the sound bank at a size a person can author and mix (`D15-R37`).

```json
{
  "schemaVersion": "1.0.0",
  "materials": [
    {
      "materialId": "steel",
      "densityKgPerM3": 7850.0,
      "resistance": { "KINETIC": 1.0, "EXPLOSIVE": 1.0, "INCENDIARY": 1.0,
                      "ENERGY": 1.0, "COLLISION": 1.0 },
      "fractureBrittleness": 0.5,
      "audioMaterial": "METAL"
    },
    {
      "materialId": "steel_hardened",
      "densityKgPerM3": 7850.0,
      "resistance": { "KINETIC": 0.85, "EXPLOSIVE": 0.95, "INCENDIARY": 1.15,
                      "ENERGY": 1.05, "COLLISION": 0.90 },
      "fractureBrittleness": 0.35
    },
    { "materialId": "aluminium", "densityKgPerM3": 2700.0, "resistance": { "...": 1.0 },
      "fractureBrittleness": 0.6 },
    { "materialId": "rubber",    "densityKgPerM3": 1100.0, "resistance": { "...": 1.0 },
      "fractureBrittleness": 0.1 },
    { "materialId": "composite", "densityKgPerM3": 1900.0, "resistance": { "...": 1.0 },
      "fractureBrittleness": 0.8 },
    { "materialId": "glass",     "densityKgPerM3": 2500.0, "resistance": { "...": 1.0 },
      "fractureBrittleness": 1.0 }
  ]
}
```

**R8.** `fractureBrittleness ∈ [0,1]` biases the Blender tool's shard count and size distribution (D09-S5.2). It is authoring data, not runtime data; the game ignores it.

<!-- D08-S4.4 -->### 4.4 Assembly Manifest Schema

**R9.** `assets/vehicles/<vehicleTypeId>/assembly.json`, validated against `schemas/assembly_manifest.schema.json`.

```json
{
  "schemaVersion": "1.0.0",
  "vehicleTypeId": "vehicle_medium_raider_01",
  "displayName": "Raider",
  "vehicleClass": "medium",
  "unlockLevel": 3,
  "chassis": "chassis_medium_01",
  "parts": [
    {
      "slotPath": "root/wheel_fl",
      "parentSlotPath": "root",
      "parentSlotId": "wheel_fl",
      "partTypeId": "wheel_road_01",
      "overrides": { "isSteering": true, "isDriven": false }
    },
    {
      "slotPath": "root/hardpoint_left",
      "parentSlotPath": "root",
      "parentSlotId": "hardpoint_left",
      "partTypeId": "weapon_autocannon_01",
      "overrides": { "weaponGroup": 0 }
    },
    {
      "slotPath": "root/hardpoint_left/subslot_muzzle",
      "parentSlotPath": "root/hardpoint_left",
      "parentSlotId": "subslot_muzzle",
      "partTypeId": "deco_muzzle_01",
      "overrides": {}
    }
  ],
  "cosmetics": { "paintSchemeId": "scheme_rust_01" },
  "expected": {
    "totalMassKg": 1620.0,
    "powerBudget": 148.5,
    "comLocal": { "x": 0.0, "y": 0.62, "z": -0.05 }
  }
}
```

**R10.** `expected` is a **checked assertion**, not an input. The pipeline computes the real values and fails (strict) or warns (lenient) if they disagree beyond `MASS_DELTA_FRAC` / `COM_OFFSET_M`. This catches content drift: a part's mass changing without its vehicles being re-checked.

**R11.** `parts[]` must be listable in topological order; `parentSlotPath` must appear earlier or be `root`. `slotPath` must equal `parentSlotPath + "/" + parentSlotId`.

<!-- D08-S4.5 -->### 4.5 Export Format

**R12.** The runtime mesh format is **glTF 2.0 binary (`.glb`)**.

| Option | Verdict | Reasoning |
|---|---|---|
| **glTF 2.0 `.glb`** | **CHOSEN** | The only widely supported format that carries **morph targets** (our damage shape keys) as a first-class feature — that alone decides it. Blender exports it natively and reliably; `gdx-gltf` imports it into libGDX with morph target support; it is binary, compact, self-contained, and inspectable by standard tooling. Node hierarchy carries our shard nodes and slot empties. Extras (`extras` object) carry per-node custom properties. |
| OBJ | Rejected | No morph targets, no hierarchy, no custom properties, text-only. Would require a parallel sidecar format for exactly the data that matters most. |
| FBX | Rejected | Carries morphs, but the format is proprietary, the Blender exporter is lossy in practice, and there is no maintained libGDX importer. |
| libGDX `.g3db` | Rejected | Native to libGDX and fast, but produced by `fbx-conv` from FBX — inheriting FBX's problems — and its morph target support is not a first-class path. |
| Custom binary | Rejected | We would own a serialiser, a Blender exporter, and a debugging story, to gain nothing glTF does not already provide. Reconsider only if profiling shows glTF parse time dominating startup, and record via D13. |

**R13.** glTF conventions used:

| Data | Where it lives in the `.glb` |
|---|---|
| Visual mesh | Root node named `<partTypeId>` |
| Collision hull source | Node named `<partTypeId>_col` (may have several convex children) |
| Damage morphs | Morph targets on the visual mesh primitive, named via `extras.targetNames = ["dmg_25","dmg_50","dmg_75","dmg_100"]` |
| Shards | In `shards.glb`, one node per shard named `shard_<nnn>`, matching the manifest's `shards[].name` |
| Slots | Empty nodes named `slot_<slotId>`, with `extras.slot_type`, `extras.max_mass_kg`, `extras.covers` |
| Units/axes | Exported Y-up (`+Y up` exporter option), metres — the single conversion point (D00-R16) |

**R14.** Export settings are fixed and recorded in the tool (D09-S5.6) so exports are reproducible: `+Y up`, apply modifiers, export morph targets (no normals/tangents on morphs unless authored), no cameras/lights, no compression (Draco off — it is lossy for morph deltas and complicates determinism).

<!-- D08-S4.6 -->### 4.6 Asset Directory Layout

```
assets/
├── asset-index.json                       # generated by :asset-pipeline:buildIndex
├── materials/materials.json
├── balance/classes.json                   # power budget class targets (D05-R32)
├── parts/
│   └── armor_plate_medium_01/
│       ├── part.json
│       ├── mesh.glb                       # intact mesh + damage morphs + _col node
│       ├── shards.glb                     # shard meshes
│       └── fracture_manifest.json         # produced by the Blender tool (D09-S4.4)
├── vehicles/
│   └── vehicle_medium_raider_01/assembly.json
└── arenas/
    └── arena_scrapyard_01/
        ├── arena.json                     # spawn points, bounds, navmesh reference
        ├── collision.glb
        ├── visual.glb
        └── navmesh.bin                    # D11-S5.4
```

<!-- D08-S4.7 -->### 4.7 Arena Definition (Summary)

```json
{
  "schemaVersion": "1.0.0",
  "arenaId": "arena_scrapyard_01",
  "displayName": "Scrapyard",
  "boundsMin": { "x": -200, "y": -30, "z": -200 },
  "boundsMax": { "x":  200, "y": 120, "z":  200 },
  "groundY": 0.0,
  "killPlaneY": -25.0,
  "spawnPoints": [
    { "id": "sp_a_01", "team": 0, "position": {"x":-80,"y":2,"z":-60},
      "yawDeg": 45.0, "clearanceRadiusM": 8.0 }
  ],
  "assets": { "collision": "collision.glb", "visual": "visual.glb", "navmesh": "navmesh.bin" },
  "modes": ["DEATHMATCH", "TEAM_DEATHMATCH", "LAST_MACHINE"],
  "payloadPath": null
}
```

**R15.** `clearanceRadiusM` must be ≥ `MIN_SPAWN_SEPARATION_M` (D06-E7). Spawn points are validated to be above the ground collision and inside bounds.

**R15a.** `groundY` is the height of the drivable floor, metres. It is authored rather than derived from the collision mesh so that an arena's floor height is a number the simulation can read before any geometry is loaded — which is what lets `ArenaFactory` generate a floor when `assets.collision` is absent (DEV-014), and what lets a spawn point be validated as above the ground without a mesh to test against.

---

<!-- D08-S5 -->## 5. Logic & Algorithms

<!-- D08-S5.1 -->### 5.1 Pipeline Overview

```
art-source/*.blend
      │  (artist / AI authors clean mesh, materials, slot empties)
      ▼
[1] blender-tool (D09)  ──► assets/parts/<id>/{mesh.glb, shards.glb, fracture_manifest.json}
      │                      exit 0 only if self-verification passed (D09-S7)
      ▼
[2] human/AI authors    ──► assets/parts/<id>/part.json
      ▼
[3] asset-pipeline validate ─► schema + semantic + referential checks (D08-S5.4)
      ▼
[4] asset-pipeline buildIndex ─► assets/asset-index.json
      ▼
[5] test-environment (D14) ──► build/verify/<id>.report.json     (gate)
      ▼
[6] game startup: AssetRegistry.load(asset-index.json)  (D08-S5.3)
```

<!-- D08-S5.2 -->### 5.2 Asset Index Generation

```pseudo
function buildIndex(assetRoot):
    index = { schemaVersion: "1.0.0", generatedAt: now(), toolVersion: version,
              materials: [], parts: [], vehicles: [], arenas: [] }

    index.materials = load(assetRoot + "/materials/materials.json").materials
    assertUniqueIds(index.materials)

    for dir in listDirs(assetRoot + "/parts") sorted:          # sorted: deterministic index
        part = loadAndValidate(dir + "/part.json", schema = PART_SCHEMA)
        assert part.partTypeId == basename(dir)                # E-A105
        manifest = loadAndValidate(dir + "/" + part.assets.fractureManifest,
                                   schema = FRACTURE_MANIFEST_SCHEMA)   # D09-S4.4
        crossCheckPartAgainstManifest(part, manifest)          # D08-S5.4 rules A2xx
        index.parts.append(summarise(part, manifest))

    for dir in listDirs(assetRoot + "/vehicles") sorted:
        asm = loadAndValidate(dir + "/assembly.json", schema = ASSEMBLY_SCHEMA)
        resolveAndValidateAssembly(asm, index.parts)           # D05-S5.1 + D08 rules A3xx
        computed = computeAssemblyAggregate(asm, index.parts)  # mass, COM, power budget
        compareToExpected(asm.expected, computed)              # D08-R10
        index.vehicles.append(summarise(asm, computed))

    for dir in listDirs(assetRoot + "/arenas") sorted: ...     # rules A4xx

    validateBalanceClasses(index.vehicles, load("balance/classes.json"))   # D05-R30
    write(assetRoot + "/asset-index.json", index)
    return validationReport
```

<!-- D08-S5.3 -->### 5.3 Runtime Import

```pseudo
function AssetRegistry.load(assetRoot, headless):
    index = readJson(assetRoot + "/asset-index.json")
    if index == null: fail(ASSETS_NOT_FOUND, exit 66)          # D03-S4.4
    assertMajorVersionMatches(index.schemaVersion)

    # 1. Materials first — everything else references them.
    for m in index.materials: materialTable.put(m.materialId, m)

    # 2. Parts. Each becomes an immutable PartType record.
    for p in index.parts sortedBy partTypeId:                  # deterministic order (G3)
        def       = readJson(p.path + "/part.json")
        manifest  = readJson(p.path + "/" + def.assets.fractureManifest)

        collisionMesh = GltfLoader.loadNode(p.path + "/mesh.glb",
                                            node = def.assets.collisionSource.node)
        hulls = []
        for convexPiece in collisionMesh.convexPieces():
            hulls.append(ShapeCache.hullFor(def.partTypeId + "#" + piece.name,
                                            piece, MAX_HULL_VERTICES))   # D06-S5.2

        shardHulls = []
        for shard in manifest.shards sortedBy id:
            mesh = GltfLoader.loadNode(p.path + "/shards.glb", node = shard.name)
            shardHulls.append(ShapeCache.hullFor(shard.id, mesh, 32))

        if not headless:                                       # D03-S5.5
            visual = GltfLoader.loadModel(p.path + "/mesh.glb")   # incl. morph targets
            assertMorphTargetsPresent(visual, def.assets.morphTargets)
        else:
            visual = null                                      # never dereferenced (D03-R14)

        partTypes.put(def.partTypeId,
            new PartType(def, manifest, hulls, shardHulls, visual, slots = parseSlots(def)))

    # 3. Vehicles (assemblies) — validated again at load, cheaply.
    for v in index.vehicles sortedBy vehicleTypeId:
        asm = readJson(v.path + "/assembly.json")
        errors = validateAssembly(asm, partTypes)              # D05-S5.1
        if errors: handleValidationFailure(errors, strictMode)
        assemblies.put(asm.vehicleTypeId, buildAssemblyRecord(asm, partTypes))

    # 4. Arenas.
    for a in index.arenas: arenas.put(a.arenaId, loadArena(a, headless))

    logSummary(counts, elapsedMs)
    return registry
```

<!-- D08-S5.4 -->### 5.4 Validation Rule Catalogue

**R16.** Every rule has a stable code. Codes are permanent; retired codes are never reused. Severity: `ERROR` fails in strict mode and warns otherwise; `FATAL` always fails; `WARN` never fails.

| Code | Severity | Rule |
|---|---|---|
| **A1xx — schema and identity** | | |
| A101 | FATAL | File is not valid JSON |
| A102 | FATAL | File fails its JSON Schema; report includes the JSON pointer of each violation |
| A103 | FATAL | `schemaVersion` major differs from the loader's |
| A104 | ERROR | ID does not match `^[a-z][a-z0-9_]{2,63}$` |
| A105 | ERROR | `partTypeId` does not equal its directory name |
| A106 | ERROR | Duplicate ID across the catalogue |
| A107 | ERROR | Referenced file does not exist |
| **A2xx — part semantics** | | |
| A201 | ERROR | `massKg <= MIN_BODY_MASS_KG` |
| A202 | ERROR | `part.massKg` differs from `manifest.partMassKg` by more than `MASS_DELTA_FRAC` |
| A203 | ERROR | `materialId` does not resolve |
| A204 | ERROR | `maxHp <= 0` |
| A205 | ERROR | `decorative` part declares stats or non-zero `armorValue` (D05-R6) |
| A206 | ERROR | Unknown stat name in `stats` |
| A207 | ERROR | Duplicate `slotId` within a part |
| A208 | ERROR | `covers` references a slot not on the same part |
| A209 | ERROR | `slotTypeRequired` is not a valid `SlotType` |
| A210 | WARN | `powerCost` deviates >15% from the reference formula (D05-S5.7) |
| A211 | ERROR | `morphTargets` present but not exactly the four canonical names |
| A212 | WARN | Part has no `morphTargets` (it will never deform, D07-R17) |
| A213 | WARN | Part has no fracture manifest (it will detach whole, D07-E5) |
| A214 | ERROR | `breakImpulseN <= 0` |
| A215 | ERROR | Non-uniform scale in the exported node (D04-S4.3) |
| **A3xx — assembly semantics** | | |
| A301 | ERROR | Not exactly one root, or root is not a `chassis` |
| A302 | ERROR | Part count exceeds `MAX_PARTS_PER_VEHICLE` |
| A303 | ERROR | `slotPath` ≠ `parentSlotPath + "/" + parentSlotId` |
| A304 | ERROR | Parent slot does not exist on the parent part |
| A305 | ERROR | Category incompatible with the slot's `slotType` |
| A306 | ERROR | Part mass exceeds the slot's `maxMassKg` |
| A307 | ERROR | Slot occupied twice |
| A308 | ERROR | Slot graph contains a cycle, or a part is unreachable from root |
| A309 | ERROR | Fewer than 3 wheels |
| A310 | ERROR | `expected.totalMassKg` differs from computed by more than `MASS_DELTA_FRAC` |
| A311 | ERROR | `expected.comLocal` differs from computed by more than `COM_OFFSET_M` |
| A312 | ERROR | Power budget outside the class target by more than 3% (D05-R30) |
| A313 | WARN | Two armour parts cover the same slot (D05-E4) |
| A314 | ERROR | `unlockLevel < 0` or references an unknown class |
| **A4xx — arena** | | |
| A401 | ERROR | Spawn point outside `bounds` |
| A402 | ERROR | `clearanceRadiusM < MIN_SPAWN_SEPARATION_M` |
| A403 | ERROR | Fewer than 2 spawn points per team declared in `modes` |
| A404 | ERROR | Missing collision or navmesh asset |
| A405 | WARN | `killPlaneY` above `boundsMin.y` by less than 5 m |
| **A5xx — mesh and manifest agreement** | | |
| A501 | ERROR | `shards.glb` lacks a node named in the manifest |
| A502 | ERROR | `mesh.glb` lacks a declared morph target |
| A503 | ERROR | `mesh.glb` lacks the declared collision node |
| A504 | ERROR | Σ shard mass differs from `partMassKg` beyond `MASS_DELTA_FRAC` (G7) |
| A505 | ERROR | A shard mass ≤ `MIN_BODY_MASS_KG` |
| A506 | ERROR | Manifest `toolVersion` missing |
| A507 | WARN | Manifest `toolVersion` older than the current build's |
| A508 | ERROR | Mesh AABB outside `[MIN_PART_EXTENT_M, MAX_PART_EXTENT_M]` (unit error, D14) |
| A509 | ERROR | Collision hull exceeds `MAX_HULL_VERTICES` after simplification |

```pseudo
function handleValidationFailure(errors, strictMode):
    fatals = errors.filter(FATAL); hard = errors.filter(ERROR); soft = errors.filter(WARN)
    for e in errors: log(e.severity, "{} {} at {}: {}", e.code, e.assetId, e.pointer, e.message)
    if fatals.isNotEmpty(): exit(67)                          # ASSETS_INVALID
    if hard.isNotEmpty():
        if strictMode: exit(67)
        else: for e in hard: substituteFallback(e.assetId)    # G18
    # WARNs never stop anything.

function substituteFallback(assetId):
    # Fallbacks are deliberately unmistakable: a magenta 1 m cube, 100 kg, 100 HP,
    # no slots. It renders, it collides, and nobody will ever mistake it for content.
    log.warn("substituting FALLBACK asset for {}", assetId)
    registry.put(assetId, FALLBACK_PART)
```

<!-- D08-S5.5 -->### 5.5 Cross-Check Between Part and Fracture Manifest

```pseudo
function crossCheckPartAgainstManifest(part, manifest):
    errors = []
    if abs(part.massKg - manifest.partMassKg) > MASS_DELTA_FRAC * part.massKg:
        errors += A202
    if part.materialId != manifest.materialId:
        errors += A203 ("part and manifest disagree on material")
    if manifest.shards.isEmpty():
        errors += A505 ("manifest declares zero shards")
    total = sum(s.massKg for s in manifest.shards)
    if abs(total - manifest.partMassKg) > MASS_DELTA_FRAC * manifest.partMassKg:
        errors += A504
    for s in manifest.shards:
        if s.massKg <= MIN_BODY_MASS_KG: errors += A505
    if manifest.shards.size > MAX_SHARDS_PER_PART: errors += A505 ("too many shards")
    for name in part.assets.morphTargets:
        if name not in manifest.morphTargets: errors += A502
    return errors
```

<!-- D08-S5.6 -->### 5.6 Schema Versioning and Migration

```pseudo
# Every contract file carries schemaVersion (semver).
#   MAJOR bump  -> loader rejects older files (A103). A migration tool must be written
#                  and run over assets/ in the same change.
#   MINOR bump  -> new optional fields only; old files still load.
#   PATCH bump  -> documentation/constraint clarification, no field change.
#
function assertMajorVersionMatches(fileVersion):
    if major(fileVersion) != major(LOADER_SCHEMA_VERSION):
        fail(A103, "file schema {} incompatible with loader {}", fileVersion, LOADER_SCHEMA_VERSION)

# R17. Adding a required field is a MAJOR change. Adding an optional field with a
#      documented default is MINOR. This rule is what lets content and code ship on
#      different cadences without a coordination meeting.
```

---

<!-- D08-S6 -->## 6. Schema Catalogue

<!-- D08-S6.1 -->### 6.1 Schema Files

**R18.** Every contract file has a checked-in JSON Schema (draft 2020-12) under `schemas/`. The schema is the machine-readable form of the tables in D08-S4; where they disagree, the schema is authoritative for *syntax* and this document is authoritative for *semantics*.

| Schema file | Validates | Owner document |
|---|---|---|
| `schemas/part_definition.schema.json` | `assets/parts/*/part.json` | D08-S4.2 |
| `schemas/material_table.schema.json` | `assets/materials/materials.json` | D08-S4.3 |
| `schemas/assembly_manifest.schema.json` | `assets/vehicles/*/assembly.json` | D08-S4.4 |
| `schemas/arena_definition.schema.json` | `assets/arenas/*/arena.json` | D08-S4.7 |
| `schemas/asset_index.schema.json` | `assets/asset-index.json` | D08-S5.2 |
| `schemas/fracture_manifest.schema.json` | `assets/parts/*/fracture_manifest.json` | **D09-S4.4** |
| `schemas/verification_report.schema.json` | `build/verify/*.report.json` | **D14-S4.4** |
| `schemas/balance_classes.schema.json` | `assets/balance/classes.json` | D05-S5.7 |

**R19.** Schemas are used by three consumers: the JVM asset pipeline (`json-schema-validator`), the Blender tool's Python validator (`jsonschema`), and the verification harness. One schema, three consumers — that is what prevents the tool and the game from drifting apart.

---

<!-- D08-S7 -->## 7. Acceptance Criteria

- [ ] **AC-D08-1.** Every contract file validates against its schema; CI runs the validation over all of `assets/` and `fixtures/`.
- [ ] **AC-D08-2.** `:asset-pipeline:buildIndex` produces a deterministic `asset-index.json` (byte-identical across runs except `generatedAt`).
- [ ] **AC-D08-3.** Every validation rule in D08-S5.4 is implemented and triggerable by a crafted fixture.
- [ ] **AC-D08-4.** Strict mode exits 67 on any ERROR; lenient mode substitutes fallbacks and continues.
- [ ] **AC-D08-5.** Fallback assets are visually unmistakable and never crash the renderer or physics.
- [ ] **AC-D08-6.** Part mass and manifest mass agree within `MASS_DELTA_FRAC` for every shipped part.
- [ ] **AC-D08-7.** Every `covers` reference resolves to a slot on the same part.
- [ ] **AC-D08-8.** Every assembly's `expected` block matches computed values within tolerance.
- [ ] **AC-D08-9.** All vehicles of a class are within 3% of the class power budget.
- [ ] **AC-D08-10.** Headless loading performs zero visual-mesh, texture, or morph loads (D03-R13).
- [ ] **AC-D08-11.** Morph target names in `mesh.glb` exactly match the part definition's declared names.
- [ ] **AC-D08-12.** Every shard node named in a manifest exists in `shards.glb`.
- [ ] **AC-D08-13.** Exported meshes are Y-up, in metres; no runtime axis conversion exists anywhere (grep for conversion helpers).
- [ ] **AC-D08-14.** Loading all shipped assets on a cold start completes within the startup budget (D12-S5.6).
- [ ] **AC-D08-15.** A major schema version bump is rejected with A103 and a clear message.

---

<!-- D08-S8 -->## 8. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | `asset-index.json` missing | Exit 66. Do not attempt to scan directories at runtime; the index is the contract. |
| E2 | Index references a part directory that was deleted | A107 ERROR; strict fails, lenient substitutes the fallback. |
| E3 | Part mesh exists but the collision node is missing | A503 ERROR. The visual mesh is **not** silently used — silent substitution would ship wrong collision. |
| E4 | Morph target present in the mesh but not declared in `part.json` | WARN; the extra morph is ignored. Undeclared data is never used. |
| E5 | Morph declared but absent from the mesh | A502 ERROR. |
| E6 | Two parts declare the same `partTypeId` | A106 ERROR; the pipeline names both paths. |
| E7 | Manifest produced by a newer tool version than the build | A507 WARN. Newer is usually fine; older is what actually breaks (also A507). |
| E8 | A part's mesh is 1000× too large (authored in cm) | A508 ERROR via the extent check — this is the single most common real-world asset bug. |
| E9 | Assembly references an unlockable part at level 0 | Legal; unlock gating is a product concern, not a pipeline one. |
| E10 | Arena has spawn points for a team not present in its `modes` | A403 ERROR. |
| E11 | Non-uniform scale baked into a glTF node | A215 ERROR: non-uniform scale breaks convex hull mass computation and Bullet shape scaling. |
| E12 | Extremely high-poly collision source | A509 ERROR after simplification fails to reach the budget. |
| E13 | `materials.json` and the Blender tool's cached density table disagree | Impossible by construction: the tool reads the same file (D09-S6.3). If a copy is found, that is a defect to remove. |
| E14 | Validation report is huge (hundreds of errors) | Report the first 50 per severity, then a count, plus the full report written to `build/asset-validation.json`. |
| E15 | A part directory contains files not referenced by `part.json` | WARN listing them. Unreferenced files usually mean a stale export. |
| E16 | Fallback substitution happens for a chassis | Vehicle still spawns with a magenta cube chassis; the match runs. Never crash on content errors during play (G18). |

---

<!-- D08-S9 -->## 9. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D08-1 | Validate all shipped assets | Zero ERRORs, zero FATALs |
| T-D08-2 | Set `part.massKg` 10% above the manifest | A202 |
| T-D08-3 | Reference a nonexistent material | A203 |
| T-D08-4 | Give a `decorative` part an `armorValue` | A205 |
| T-D08-5 | Duplicate a `slotId` | A207 |
| T-D08-6 | `covers` a slot on a different part | A208 |
| T-D08-7 | Build an assembly with two chassis | A301 |
| T-D08-8 | Attach a weapon to an `ARMOR_PANEL` slot | A305 |
| T-D08-9 | Assembly with 2 wheels | A309 |
| T-D08-10 | Change a part's mass without updating `expected` | A310 |
| T-D08-11 | Push one vehicle 5% over its class power budget | A312 |
| T-D08-12 | Delete a shard node from `shards.glb` | A501 |
| T-D08-13 | Remove `dmg_50` from the mesh | A502 |
| T-D08-14 | Scale a source mesh by 100 and re-export | A508 |
| T-D08-15 | Run `buildIndex` twice | Identical output but `generatedAt` |
| T-D08-16 | Run with `--strict-assets` and one ERROR | Exit 67, full report written |
| T-D08-17 | Same without strict | Magenta fallback, match runs to completion |
| T-D08-18 | Load in headless mode with instrumentation | Zero texture/visual-mesh/morph loads; collision and shard hulls loaded |
| T-D08-19 | Bump a schema major and load old assets | A103 with both versions named |
| T-D08-20 | Validate `fixtures/` | Fixture problems are attributed to fixtures, not to the tool (D14-E30) |
| T-D08-21 | Time a cold load of the full asset set | Within the D12-S5.6 startup budget |
| T-D08-22 | Non-uniform-scaled node in a `.glb` | A215 |

---

<!-- D08-S10 -->## 10. Cross-References

| Topic | Section |
|---|---|
| Units, axes, one-conversion-point rule | `docs/00_master_index.md#D00-S4.3`, `#D00-S4.4` |
| Asset ID grammar | `docs/00_master_index.md#D00-S4.5` |
| Fail-loud-in-validation invariant (G18) | `docs/00_master_index.md#D00-S5.2` |
| `asset-pipeline` module | `docs/02_technical_architecture.md#D02-S4.5` |
| Data flow art → gameplay | `docs/02_technical_architecture.md#D02-S5.2` |
| Headless disabled resources | `docs/03_runtime_modes.md#D03-S5.5` |
| Asset exit codes | `docs/03_runtime_modes.md#D03-S4.4` |
| Part properties | `docs/05_vehicle_part_system.md#D05-S4.4` |
| Assembly validation | `docs/05_vehicle_part_system.md#D05-S5.1` |
| Power budget rule | `docs/05_vehicle_part_system.md#D05-S5.7` |
| Collision shape rules | `docs/06_physics_simulation.md#D06-S4.3` |
| Hull construction and caching | `docs/06_physics_simulation.md#D06-S5.2` |
| Morph target usage at runtime | `docs/07_damage_destruction_model.md#D07-S5.5` |
| Fracture manifest schema | `docs/09_blender_destruction_tool.md#D09-S4.4` |
| Material density table (shared file) | `docs/09_blender_destruction_tool.md#D09-S6.3` |
| Export settings used by the tool | `docs/09_blender_destruction_tool.md#D09-S5.6` |
| Navmesh asset | `docs/11_ai_bots_and_match_simulation.md#D11-S5.4` |
| CI asset validation stage | `docs/12_testing_validation_ci.md#D12-S5.4` |
| Startup performance budget | `docs/12_testing_validation_ci.md#D12-S5.6` |
| Harness manifest/mesh agreement checks | `docs/14_test_environment.md#D14-S5.3` |
