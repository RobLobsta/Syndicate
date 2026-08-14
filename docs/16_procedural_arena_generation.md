<!-- D16-S0 --># 16 — Procedural Arena Generation

**Status:** Contract. **Owner:** terrain, sky, road corridors, surface materials, structure placement.
**Depends on:** D06 (physics), D08 (asset formats), D05 (parts and slots), D07 (damage), D11 (bots).

---

<!-- D16-S1 -->## 1. Purpose

An arena today is a flat plane and four invisible walls generated from six numbers (DEV-014). That
was the right placeholder — every other system needed something to drive on before it needed
somewhere interesting to drive — and it is now the single largest thing standing between the
simulation and a game that is worth playing, because on a flat plane every position is equivalent to
every other position. Nothing is cover, nothing is high ground, no route is faster than any other,
and a vehicle's handling never has to deal with anything but a change of direction.

This document specifies how an arena's **ground, sky, surfaces and static structures are generated at
runtime from a seed**, so that:

- The ground has relief — slopes, dunes, cuttings, embankments — that a driver has to read and a
  vehicle's suspension has to work against.
- Where a vehicle *is* matters. A ridge is a sightline. A slip face is a wall from one side and a
  jump from the other. The tarmac is fast and the sand is not.
- The surface under each wheel is a real, queryable property that changes grip and sound, closing
  the standing gap in which every arena is tarmac because an arena declares no surface at all.
- Nothing is downloaded and nothing is a texture asset a person has to author, for the same reason
  the sound bank is synthesised (DEC-046): the project's content path is generation, and generated
  content carries no licence.
- Structures placed in the arena break the way vehicles break, through the machinery that already
  exists rather than through a second destruction system.

The first arena this specifies is a **desert highway**: a raised tarmac ribbon crossing a dune field,
with cuttings, embankments, verges and roadside structures. It is chosen because it exercises every
mechanism here — two surfaces, a carved corridor, natural impassable boundaries, and cover that is
placed rather than modelled — with the smallest possible amount of art.

---

<!-- D16-S2 -->## 2. Scope

<!-- D16-S2.1 -->### 2.1 In Scope

- The **height field**: its representation, its generation from a seed, its collision shape, and the
  queries the rest of the simulation makes against it.
- **Landform generation**: fractal relief, dune fields with an angle of repose, plateaus, and the
  border falloff that replaces the four walls.
- **Road corridors**: how a spline carves the terrain into cut and fill, and the grade limit that
  keeps a road drivable.
- **Surface classification**: the per-cell surface grid, and how it reaches wheel grip, tyre audio
  and bot navigation.
- **Sky and atmosphere**: the analytic sky model, how it is baked, and how it drives lighting, image
  based reflections and fog from one set of numbers.
- **Terrain rendering**: chunking, level of detail, and procedurally generated tiling surface
  textures.
- **Structures**: how a destructible static object is defined, placed deterministically, and broken
  by the systems that already break vehicles.
- **Verification**: what must be checked about a generated arena before it is playable.

<!-- D16-S2.2 -->### 2.2 Non-Goals

- **Not an arena editor.** Arenas are generated from a seed and a small parameter block; there is no
  authoring tool, no brush, and no hand-placed geometry. A designer's control surface is the
  parameter block and the seed.
- **Not runtime terrain deformation.** Craters, ruts and tyre tracks are out of scope. The height
  field is generated once at load and is immutable for the match (R3a).

  This clause originally justified itself on the wire: a mutable height field is replicated state, and
  terrain costs nothing to replicate only because it is derived (D16-S5.12). **That argument is
  weaker than it looked** and is corrected here rather than left standing, because a future session
  would otherwise take it at face value. Nobody would replicate the field. A crater is
  `(x, z, radius, depth)` — about twelve bytes — so deformation replicates as an *event log* that
  every peer stamps onto its own generated field, and a late joiner receives the log rather than the
  ground. A few hundred craters a match is a few kilobytes. Derived-plus-small, not replicated
  terrain.

  The real costs are elsewhere, and they are what keep this a non-goal:

  1. **Render chunk rebuilds.** Every touched chunk's vertex buffer must be regenerated (D16-S6.1).
     Local and bounded, but it is per-explosion GPU work in a renderer with no culling yet.
  2. **Reserved height range.** `btHeightfieldTerrainShape` bakes its min and max height at
     construction and derives its local AABB from them, so a crater below the generated minimum falls
     outside the shape's own bounds. Headroom has to be reserved up front, and the body's broadphase
     AABB refreshed after every edit.
  3. **The playability guarantee stops being an invariant.** D16-R58 and R58a are checked once, at
     load, and hold for the match because nothing moves afterwards. Craters can disconnect a region,
     bury a spawn point, or open a hole in the border rim, and re-running a flood fill over 361,000
     cells per explosion is not a per-tick cost anyone wants. This is the item that makes deformation
     a **design** question rather than an engineering one: craters are fun, and an arena that can be
     cratered into disconnected pockets is not.
  4. **Determinism.** The stamp is subject to every rule in D16-R61, applied in a fixed order, or two
     peers' ground diverges.
- **Not vegetation, weather or time of day.** The sun is fixed for a match. A sky whose sun moves
  makes shadows and baked reflections a per-frame cost for no gameplay change.
- **Not a replacement for D15.** D15 prepares *vehicles* from downloaded art. Structures here are
  authored as parts through the same D09 tool, but nothing in this document segments a downloaded
  building.
- **Not water, and not caves.** A height field is a function of `(x, z)`; anything with two surfaces
  above one point — a bridge, a tunnel, an overpass — is a **structure**, not terrain (D16-S7).

---

<!-- D16-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/06_physics_simulation.md#D06-S4.3` | Collision shapes; the height field is a new shape kind |
| `docs/06_physics_simulation.md#D06-S4.4` | Collision layers; terrain is `STATIC` |
| `docs/06_physics_simulation.md#D06-S5.2` | `ShapeCache`, which must own the height field shape and its data |
| `docs/06_physics_simulation.md#D06-S5.5` | The ray-cast wheel, which is where per-surface grip is applied |
| `docs/06_physics_simulation.md#D06-S5.8` | Seeded random streams; placement draws from one |
| `docs/08_asset_pipeline.md#D08-S4.7` | The arena definition this document extends |
| `docs/08_asset_pipeline.md#D08-S5.4` | Validation rule codes; arena rules are the `A4xx` block |
| `docs/05_vehicle_part_system.md#D05-S4.1` | Parts and the slot graph, which a structure reuses |
| `docs/07_damage_destruction_model.md#D07-S5.7` | Detachment triggers, which are what collapse a structure |
| `docs/09_blender_destruction_tool.md#D09-S4.4` | Fracture manifests; a structure part has one like any other |
| `docs/11_ai_bots_and_match_simulation.md#D11-S5.4` | Navigation, which reads the drivability grid |
| `docs/03_runtime_modes.md#D03-S5.1` | Startup order; terrain is generated before anything spawns |

Nothing in D16 may be depended on by `game-core`'s existing systems in a way that requires a new
schedule slot: the 27 slots of `docs/04_entity_component_model.md#D04-S4.4` are closed, and D16-S7.3
exists to show that a structure needs none.

---

<!-- D16-S4 -->## 4. Data Contracts

<!-- D16-S4.1 -->### 4.1 Reserved Constants

**R1.** These are owned here and referenced by name elsewhere (D00-R26 permits a document to own
constants that are not cross-cutting).

| Constant | Value | Meaning |
|---|---|---|
| `TERRAIN_CELL_M` | 1.0 | Default spacing between height samples, metres |
| `TERRAIN_MAX_GRID` | 1025 | Hard cap on samples per side |
| `TERRAIN_CHUNK_CELLS` | 64 | Render chunk size, cells per side |
| `MAX_DRIVABLE_SLOPE_DEG` | 25.0 | At or below this, terrain is drivable and navigable |
| `SAND_REPOSE_DEG` | 33.0 | Angle of repose of dry sand; a dune slip face sits here |
| `ROAD_MAX_GRADE_PCT` | 6.0 | Steepest longitudinal grade a carved road may reach |
| `ROAD_MAX_CROSSFALL_PCT` | 2.5 | Camber across a carved road |
| `STRUCTURE_PAD_MARGIN_M` | 2.0 | Flattened margin around a structure's footprint |
| `TERRAIN_HASH_TOLERANCE` | 0 | Permitted difference between two processes' height fields |
| `PHASE_WARP` | 0.35 | How far the dune phase warp displaces crests, in wavelengths |
| `MIN_SLIP_FRAC` / `MAX_SLIP_FRAC` | 0.02 / 0.45 | Bounds on a slip face's share of a dune's wavelength |
| `CREST_GAP_LOW` / `CREST_GAP_HIGH` | 0.40 / 0.50 | Where the crest field stops and starts making dunes (R34a) |
| `CREST_PASS_FLOOR` | 0.7 | A dune's height the moment it exists, as a fraction of nominal |
| `PAD_RAMP_MIN_M` | 8.0 | Shortest ramp out of a levelled pad, metres |

**R2.** `MAX_DRIVABLE_SLOPE_DEG` is below `SAND_REPOSE_DEG` **by design and the gap is the game**. A
dune's windward face is shallow and drivable; its slip face stands at the angle of repose, which is
above what a vehicle can climb. The same generator therefore produces, from one physical constant, a
surface that is a ramp from one direction and a wall from the other. Nothing else in this document
produces as much gameplay for as little machinery, and the two numbers must not be brought together
without deciding to lose that.

<!-- D16-S4.2 -->### 4.2 Terrain Block

**R3.** `arena.json` (D08-S4.7) gains an optional `terrain` object.

```json
"terrain": {
  "seed": 20260814,
  "cellSizeM": 1.0,
  "gridSize": 601,
  "biome": "desert",
  "reliefM": 16.0,
  "baseFrequency": 0.0035,
  "octaves": 5,
  "duneWindDeg": 115.0,
  "duneWavelengthM": 90.0,
  "duneHeightM": 9.0,
  "borderWidthM": 60.0,
  "borderRiseM": 28.0,
  "maxDrivableSlopeDeg": 25.0
}
```

**R3a.** The height field, the surface grid and the drivability grid are written once by the
generator and never again. Nothing in the simulation, the renderer or the network layer may modify
them after `TerrainGenerator` returns. See D16-S2.2 for what changing this would cost.

**R4.** An arena with **no** `terrain` block is the flat floor and four box walls that ship today
(DEV-014). This is not a deprecation path: a flat arena remains a legal, useful arena — every physics
regression fixture is one, and a test that measures a braking distance wants a floor, not a landform.

**R5.** `gridSize` must be odd, at least 65, at most `TERRAIN_MAX_GRID`, and must satisfy
`(gridSize − 1) × cellSizeM == boundsMax.x − boundsMin.x == boundsMax.z − boundsMin.z`. The height
field is square and axis-aligned with the arena bounds, so a world position maps to a cell by
arithmetic rather than by search.

**R6.** `biome` is a closed set: `desert`, `tarmac_flat`. It selects which layer stack D16-S5.2 runs
and which surface table D16-S4.4 row is the default. It is closed rather than free-form because every
member costs a generator path, a surface set and a texture generator, and a biome nobody has
generated is worse than one that does not exist.

<!-- D16-S4.3 -->### 4.3 Road Corridors

**R7.** `arena.json` gains an optional `roads` array. A road is a centreline, a width, and the
surface it lays down.

```json
"roads": [
  {
    "id": "highway_main",
    "surface": "tarmac",
    "widthM": 14.0,
    "shoulderM": 3.0,
    "verge": "gravel",
    "maxGradePct": 6.0,
    "markings": "dashed_centre",
    "spline": [
      { "x": -300.0, "z": -240.0 },
      { "x":  -80.0, "z": -150.0 },
      { "x":   60.0, "z":   30.0 },
      { "x":  300.0, "z":  210.0 }
    ]
  }
]
```

**R8.** `spline` is a Catmull-Rom control polygon in world XZ, with at least two points. The road's
**elevation is not authored** — it is derived from the terrain the road crosses (D16-S5.4), because a
road whose height is authored either floats or buries itself the moment the seed changes.

**R9.** `widthM` must be at least 6.0 m. Below that a road is not a corridor two vehicles fight on,
it is a rut, and the carve's falloff would exceed the flat part.

**R10.** Roads are carved in array order and later roads win where they overlap, so a junction is
expressible without a junction primitive.

<!-- D16-S4.4 -->### 4.4 Surface Table

**R11.** A surface is a closed enum with four gameplay properties. This table is the single authority;
D06's wheel code, the audio system and the navigation grid all read it.

| Surface | Grip multiplier | Rolling resistance | Audio material | Drivable |
|---|---|---|---|---|
| `tarmac` | 1.00 | 0.015 | `tarmac` | yes |
| `gravel` | 0.72 | 0.028 | `gravel` | yes |
| `sand` | 0.55 | 0.060 | `gravel` | yes |
| `rock` | 0.88 | 0.020 | `metal` | yes |

**R12.** The grip multiplier scales the wheel's friction slip (D06-S5.5); it does **not** scale the
static body's Bullet friction. The reason is mechanical: a ray-cast wheel never generates a Bullet
contact for the tyre, so the terrain body's `friction` value affects a car's chassis sliding on its
roof and nothing else. Putting per-surface grip anywhere but the wheel would be a number that looks
correct and does nothing.

**R13.** `sand`'s rolling resistance is four times `tarmac`'s. That is what makes leaving the highway
a decision rather than a shortcut, and it is the one number in this table that should be tuned first
by driving.

**R14.** Audio material maps onto the existing tyre loop set. `sand` maps to `gravel` because the bank
has no sand loop; when one exists this row changes and nothing else does.

<!-- D16-S4.5 -->### 4.5 Sky Block

**R15.** `arena.json` gains an optional `sky` object.

```json
"sky": {
  "sunAzimuthDeg": 118.0,
  "sunElevationDeg": 34.0,
  "turbidity": 4.5,
  "groundAlbedo": [0.62, 0.52, 0.36],
  "fogDensity": 0.0022,
  "fogStartM": 120.0
}
```

**R16.** The sun direction in `sky` is the **only** authority for the sun. The renderer's directional
light, the baked image-based lighting, the skybox and the fog colour are all derived from it
(D16-S6.3). A scene whose sky and shadows disagree about where the sun is reads as wrong before a
viewer can say why, and today's sun direction is a constant compiled into the client.

**R17.** `groundAlbedo` is the biome's average ground colour, and it feeds the sky model's ground
bounce. Desert sand bouncing warm light into shadowed surfaces is most of why a desert looks like a
desert rather than like a blue-lit quarry.

<!-- D16-S4.6 -->### 4.6 Structure Definition

**R18.** A structure is defined by `assets/structures/<structureId>/structure.json`, and it is an
**assembly** in the D05-S4.1 sense: a root part, and parts attached to slots on it.

```json
{
  "schemaVersion": "1.0.0",
  "structureId": "str_gantry_sign_01",
  "displayName": "Overhead Sign Gantry",
  "rootPartTypeId": "struct_gantry_leg_01",
  "staticRoot": true,
  "parts": [
    { "partTypeId": "struct_gantry_leg_01",  "parentSlotPath": "leg_r" },
    { "partTypeId": "struct_gantry_span_01", "parentSlotPath": "leg_r/span" },
    { "partTypeId": "struct_sign_panel_01",  "parentSlotPath": "leg_r/span/panel" }
  ],
  "footprint": { "radiusM": 9.0, "heightM": 7.2 },
  "expected": { "massKg": 4200.0 }
}
```

**R19.** The `partTypeId`s refer to ordinary `assets/parts/<id>/part.json` entries with ordinary
fracture manifests produced by the ordinary D09 tool. A structure introduces **no new part schema, no
new material path and no new fracture path**. If a structure part needs something a vehicle part
cannot express, that is a defect in this design, not a reason to fork the schema.

**R20.** `staticRoot: true` means the root part's body has zero mass and lives on the `STATIC` layer
until it is destroyed. `footprint.radiusM` is what placement uses for spacing and what the terrain
pad flattens; it must enclose the structure's horizontal extent.

<!-- D16-S4.7 -->### 4.7 Structure Placement

**R21.** `arena.json` gains an optional `structures` array. Placement is declared as a rule, not as a
list of transforms, because a list of transforms is authored content and this document's premise is
that arena content is generated.

```json
"structures": [
  { "structureId": "str_jersey_barrier_01", "placement": "road_verge", "spacingM": 24.0, "jitterM": 1.5 },
  { "structureId": "str_gantry_sign_01",    "placement": "road_span",  "countMin": 2, "countMax": 4 },
  { "structureId": "str_fuel_bowser_01",    "placement": "cluster",    "clusters": 3, "perCluster": 5 },
  { "structureId": "str_boulder_01",        "placement": "scatter",    "densityPerHa": 1.2 }
]
```

**R22.** `placement` is a closed set: `road_verge` (along a road's shoulder, both sides, facing the
road), `road_span` (crossing a road, above it), `cluster` (a group on a flattened pad, off-road,
within reach of a road), `scatter` (anywhere drivable, sparse).

**R23.** Every placed structure must satisfy, or it is rejected and the next candidate is drawn: it is
`clearanceRadiusM + footprint.radiusM` from every spawn point; it does not overlap another structure's
footprint; its pad's slope is at or below `MAX_DRIVABLE_SLOPE_DEG` before flattening; and unless its
placement is `road_verge` or `road_span`, it does not intersect a road corridor.

<!-- D16-S4.8 -->### 4.8 Arena Generation Report

**R24.** Generation produces a report, written by the verification harness and by the asset pipeline
in strict mode, with the fields that make a generated arena reviewable without looking at it:

```json
{
  "arenaId": "arena_desert_highway_01",
  "terrainSeed": 20260814,
  "heightFieldHash": "b4f1c2...",
  "minHeightM": -3.2, "maxHeightM": 21.7,
  "slopeHistogramDeg": [0, 5, 10, 15, 20, 25, 30, 35, 40],
  "slopeHistogramFrac": [0.31, 0.22, 0.15, 0.11, 0.08, 0.05, 0.04, 0.03, 0.01],
  "drivableFrac": 0.87,
  "surfaceFrac": { "tarmac": 0.06, "gravel": 0.04, "sand": 0.90 },
  "roads": [ { "id": "highway_main", "lengthM": 812.4, "maxGradePct": 5.1, "maxCrossfallPct": 2.4 } ],
  "structuresPlaced": 137, "structuresRejected": 22,
  "spawnConnectivity": "all_pairs_reachable",
  "findings": []
}
```

**R25.** `heightFieldHash` is a hash of the quantised height field and the surface grid. It is the
artefact that makes D16-S5.12's determinism claim testable rather than asserted.

---

<!-- D16-S5 -->## 5. Logic & Algorithms

<!-- D16-S5.1 -->### 5.1 Generation Order

**R26.** Generation is a fixed pipeline, run once during startup (D03-S5.1), before any entity is
spawned and before spawn points are validated. Every stage is a pure function of the stage before it
and the arena definition.

```
[1] base relief          fBm over the whole grid                     → heights
[2] biome layer          dunes / plateaus, oriented                  → heights
[3] border falloff       rise the outer band to impassable           → heights
[4] road carve           per road, in array order: cut and fill      → heights, surfaces
[5] pad flatten          per structure candidate                     → heights
[6] surface classify     slope and height rules where no road won    → surfaces
[7] drivability          slope test + structure footprints           → drivable grid
[8] connectivity check   flood fill from spawn points                → report / reject
[9] collision build      height field shape, one static body         → world
[10] structure place     draw candidates, test, instantiate          → entities
```

**R27.** Stage 8 can **fail**, and failure is a hard error rather than a warning. An arena where a
spawn point cannot reach the rest of the map is not a difficult arena, it is a broken one, and it must
be caught by the generator rather than by a player who spawned in a bowl.

<!-- D16-S5.2 -->### 5.2 The Height Field

**R28.** The field is `float[gridSize * gridSize]` in row-major `(z, x)` order, holding metres above
`groundY`. World position of sample `(i, j)`:

```
x = boundsMin.x + i * cellSizeM
z = boundsMin.z + j * cellSizeM
```

**R29.** Base relief is fractal Brownian motion over 2D gradient noise:

```pseudo
function fbm(x, z, freq, octaves, lacunarity=2.0, gain=0.5):
    sum = 0; amp = 1.0; norm = 0
    for o in 0..octaves-1:
        sum  += amp * gradientNoise(x * freq, z * freq, o)
        norm += amp
        freq *= lacunarity
        amp  *= gain
    return sum / norm                       # in [-1, 1]
```

**R30.** `gradientNoise` takes its lattice gradients from an **integer hash of the cell coordinates
mixed with the terrain seed and the octave index**, not from a permutation table and not from
`java.util.Random`. Two reasons, both hard requirements: a hash is a pure function of position, so the
field can be evaluated at one point without generating the whole grid (which is what makes a query
cheap in D16-S5.9), and an integer hash produces identical results on every JVM, which
D16-S5.12 requires.

**R31.** Interpolation between lattice points uses the quintic smoothstep `6t⁵ − 15t⁴ + 10t³`. The
cubic `3t² − 2t³` leaves second-derivative discontinuities at cell boundaries, which are invisible in
a heightmap image and extremely visible as a repeating jolt through a vehicle's suspension at 30 m/s.

<!-- D16-S5.3 -->### 5.3 Dunes and the Angle of Repose

**R32.** A dune field is not fBm. Fractal noise is symmetric — its up-slopes and down-slopes have the
same distribution — and a dune's defining property is that it is *not*: a long shallow windward face
and a short slip face standing at the sand's angle of repose.

```pseudo
function duneLayer(x, z, p):                       # p = terrain params
    # Rotate into wind-aligned coordinates; dunes are transverse to the wind.
    u = x·cos(p.duneWindDeg) + z·sin(p.duneWindDeg)

    # A drifting phase, so crests are neither straight nor evenly spaced.
    warp  = fbm(x, z, p.baseFrequency * 2.0, 3)
    phase = u / p.duneWavelengthM + PHASE_WARP * warp
    t     = fract(phase)                           # sawtooth in [0,1)

    # How fast phase advances *here*, per metre along the wind: the nominal rate plus whatever
    # the warp is doing, differenced along the wind rather than along x.
    dPhase = 1/p.duneWavelengthM + PHASE_WARP * d(warp)/du
    dPhase = max(dPhase, 0.25 / p.duneWavelengthM)

    # How tall a dune stands here, and whether there is one at all (R34a).
    amplitude = p.duneHeightM * crestScale(0.5 + 0.5 * fbm(x, z, p.baseFrequency, 3))
    if amplitude == 0: return 0

    # Solve the slip width from the repose angle rather than fixing it (R33).
    slip = clamp(amplitude * dPhase / tan(SAND_REPOSE_DEG), MIN_SLIP_FRAC, MAX_SLIP_FRAC)
    wind = 1 - slip

    if t < wind:  return amplitude * (1 - (1 - t/wind)²)     # long, easing rise
    else:         return amplitude * (1 - (t - wind)/slip)   # the face, at repose
```

with `PHASE_WARP = 0.35`, `MIN_SLIP_FRAC = 0.02`, `MAX_SLIP_FRAC = 0.45`.

**R33. The slip angle is an input, not an output.** An earlier form of this section fixed the
windward fraction at 0.72 and made the resulting angle a consequence of height and wavelength, to be
brought back by a correction pass that scaled `duneHeightM` down. That is wrong in two ways, both
found by implementing it (DISC-044): at the parameters this document shipped, the face came out at
19.6° — a *ramp*, not a wall — and a correction that only ever reduces the height cannot fix a face
that is too shallow. Solving for the slip width instead makes the property hold **by construction**.

**R33a.** The width is solved from the **local** crest height and the **local** phase gradient, not
from the nominal figures. Real dunes stand at repose whatever their size, so a half-height dune needs
a half-width face rather than a gentler one; and the phase warp means a cycle is not
`duneWavelengthM` of ground everywhere, so a face solved against the mean is too steep wherever the
warp has crowded two crests together. With both, measured slip faces on the shipped arena run to a
mean of **32.5°** against a target of 33.0, with a 90th percentile of 34.6°.

**R34.** The final field is `reliefM × fbm(...) + duneLayer(...)`, and the two are summed rather than
multiplied so that a dune field crossing a broad rise stays a dune field rather than flattening out
in the troughs.

**R34a. The crest field must reach zero, opening passes.** `crestScale` is zero below
`CREST_GAP_LOW`, rises to `CREST_PASS_FLOOR` by `CREST_GAP_HIGH`, and varies up to full height above
it. Without the zero region the arena is **not connected**: dunes run transverse to the wind and
every slip face is past what a vehicle can climb, so uniformly non-zero dune heights make uniformly
continuous walls. Measured, that arena was 73% drivable and split into 42 regions, the largest under
a quarter of it (DISC-045). The gate is narrow — a tenth of the crest field's range — because a wide
one connects the arena by deleting the dunes rather than by opening passes between them.

<!-- D16-S5.4 -->### 5.4 Road Carving

**R35.** A road is carved in three passes: derive its elevation profile, limit its grade, then blend
the terrain toward it.

```pseudo
function carveRoad(heights, surfaces, road, p):
    centre = catmullRom(road.spline, step = cellSizeM)          # polyline, ~1 m apart

    # [1] Sample the land the road crosses, then smooth it hard. The road follows the
    #     landform; it does not ignore it and it does not copy its every bump.
    profile = [ sampleHeight(heights, c) for c in centre ]
    profile = gaussianSmooth(profile, sigma = 25.0 / cellSizeM)

    # [2] Clamp the longitudinal grade. A single forward-then-backward pass is enough:
    #     each pass makes the profile grade-legal in one direction and cannot break the other.
    limit = road.maxGradePct / 100 * cellSizeM
    for i in 1 .. profile.length-1:   profile[i] = clamp(profile[i], profile[i-1] - limit, profile[i-1] + limit)
    for i in profile.length-2 .. 0:   profile[i] = clamp(profile[i], profile[i+1] - limit, profile[i+1] + limit)

    # [3] Blend the terrain toward the road surface, with a falloff that reaches the
    #     untouched terrain smoothly.
    halfWidth = road.widthM / 2
    reach     = halfWidth + road.shoulderM + FALLOFF_M
    for each cell within `reach` of the polyline:
        (d, s) = distanceAndStation(cell, centre)               # metres from centre, index along
        target = profile[s] - crossfall(d, road)                # camber, ROAD_MAX_CROSSFALL_PCT
        if d <= halfWidth:
            heights[cell]  = target
            surfaces[cell] = road.surface
        else if d <= halfWidth + road.shoulderM:
            heights[cell]  = target
            surfaces[cell] = road.verge
        else:
            w = smoothstep(1, 0, (d - halfWidth - road.shoulderM) / FALLOFF_M)
            heights[cell] = lerp(heights[cell], target, w)
```

with `FALLOFF_M = 12.0`.

**R36.** The falloff blend is what produces **cut and fill for free**, and it is the whole reason the
highway is interesting to fight on. Where the terrain was above the road, the blend digs a cutting and
the road runs between two banks — cover on both sides, and a place you can be pushed into and not get
out of. Where the terrain was below, the blend raises an embankment and the road becomes a ridge you
can be pushed *off*. Neither is authored; both fall out of one lerp.

**R37.** The falloff's outer edge may exceed `MAX_DRIVABLE_SLOPE_DEG` on a deep cutting. That is
correct and must not be clamped: a cutting whose walls are climbable is not a cutting.

<!-- D16-S5.5 -->### 5.5 Border Falloff

**R38.** The four box walls of DEV-014 are replaced by terrain. Over the outer `borderWidthM` of the
grid, the height is raised toward `borderRiseM` on a smoothstep, producing a rim of dunes standing
well above the angle of repose.

```pseudo
d = distance from cell to the nearest arena bound, metres
w = smoothstep(1, 0, clamp(d / borderWidthM, 0, 1))
heights[cell] += borderRiseM * w * w
```

**R39.** The rim is a **soft** boundary: a vehicle at speed can get part way up it and will slide
back. It is not a hard stop, and it is deliberately not one — an invisible wall at the arena edge is
the single most immersion-breaking object in a driving game, and it is also a surface that ramming
someone into is a free kill. The kill plane (D08-S4.7) remains as the hard backstop for anything that
leaves anyway.

**R40.** The border rise is applied **after** road carving, so a road that reaches the boundary
terminates in the rim rather than being flattened through it. A road that must leave the arena should
end in a cutting that closes — which is what this produces.

<!-- D16-S5.6 -->### 5.6 Surface Classification

**R41.** Cells that no road claimed take their surface from slope and height, evaluated in order; the
first match wins.

```pseudo
function classify(cell, slopeDeg, height, p):
    if slopeDeg > SAND_REPOSE_DEG + 2:  return rock      # too steep to hold sand
    if height > p.rockExposureM:        return rock      # exposed plateau caps
    if slopeDeg > 18:                   return gravel    # sand scoured off the steeper faces
    return sand
```

**R42.** The rule set is physical rather than decorative: sand does not sit on a face steeper than its
angle of repose, so rock showing through on the steepest slopes is what the world would actually do.
That it also gives the player a **legible grip signal** — the steep bits grip better than they look —
is the payoff for deriving it rather than painting it.

<!-- D16-S5.7 -->### 5.7 Structure Placement

**R43.** Placement draws from a new random stream, `StreamId.ARENA_LAYOUT`. Adding a stream is safe
because a stream's seed mixes the stream's `name()` rather than its `ordinal()`, so an added member
does not renumber the existing ones.

```pseudo
function place(arena, terrain, roads, rng):
    placed = []
    for rule in arena.structures:                        # array order, deterministic
        candidates = candidatesFor(rule, roads, terrain, rng)   # ordered, rule-specific
        for c in candidates:
            if not clearOfSpawns(c) : reject(c, "spawn clearance"); continue
            if overlapsPlaced(c, placed): reject(c, "footprint overlap"); continue
            if padSlope(c) > MAX_DRIVABLE_SLOPE_DEG: reject(c, "slope"); continue
            if rule.placement not in (road_verge, road_span) and onRoad(c): reject(c, "on road"); continue
            flattenPad(terrain, c, STRUCTURE_PAD_MARGIN_M)
            placed.append(c)
    return placed
```

**R44.** Candidates are generated in a defined order and tested in that order (G3). A placement pass
that iterated a hash set would produce a different arena on a different JVM from the same seed, which
is the same class of defect as an unsorted system iteration and is harder to notice.

**R45.** `flattenPad` runs **during** placement rather than as a separate stage, because a pad flatten
changes the slope test for every later candidate. A structure standing on the lip of another
structure's pad is the artefact this ordering prevents.

<!-- D16-S5.8 -->### 5.8 Collision

**R46.** Terrain collision is one `btHeightfieldTerrainShape` on one zero-mass body on the `STATIC`
layer (D06-S4.4), owned by `ShapeCache` under a new `HEIGHTFIELD` variant.

**R47. The height data buffer is owned, not borrowed.** Bullet's height field shape holds a **raw
pointer** to the caller's height array and never copies it. A Java `float[]`, or a non-direct buffer,
or a direct buffer that nothing holds a reference to, will be moved or collected and the shape will
then read freed memory — a crash with no stack in our code, at an arbitrary later tick. `ShapeCache`
therefore holds the direct buffer alongside the shape, for exactly the lifetime of the shape. This is
a G19 ownership rule with teeth: it is the first native object in this project whose *input* has to
outlive the call that created it.

**R48. The shape is centred on its own AABB.** Bullet places a height field with its local origin at
the midpoint of `(minHeight, maxHeight)`, not at zero and not at `minHeight`. The body's transform
must therefore be translated by `groundY + (minHeight + maxHeight) / 2`. Getting this wrong produces
terrain that is visibly offset from its collision by half its relief — which looks exactly like a
rendering bug and is not one.

**R49.** `setUseDiamondSubdivision(true)`. Bullet's default triangulation splits every quad on the
same diagonal, which produces a directional bias in the surface — a vehicle crossing the grid one way
rides differently than crossing it the other.

**R50.** This shape **also resolves DISC-017**. Bullet finds a convex shape with an iterative
subsimplex cast whose accuracy degrades with size, which is why the current arena floor is an infinite
plane rather than a box: against a large box, each suspension ray came back up to 0.14 m off,
differently every tick. A height field is ray-tested per triangle, analytically, at any extent. The
standing risk that "the trap returns the moment an arena is built out of large boxes" is retired for
the ground, and the answer is not "avoid large shapes" but "the ground is not a convex shape".

<!-- D16-S5.9 -->### 5.9 Terrain Query

**R51.** `TerrainField` carries the generated grids **and** the queries over them, and is the one way
the simulation asks about the ground. Every method is a pure function of those grids. The queries are
not a separate `TerrainQuery` type: they are a view of exactly one field's data, and a second type
holding a reference to the first buys an interface seam that nothing has two implementations of.

| Query | Returns | Used by |
|---|---|---|
| `heightAt(x, z)` | metres, bilinear between samples | spawn placement, structure pads, camera |
| `normalAt(x, z)` | unit normal from central differences | slope tests, bot steering |
| `slopeDegAt(x, z)` | degrees from vertical | drivability, classification |
| `surfaceAt(x, z)` | a `Surface` | wheel grip, tyre audio, bot cost |
| `isDrivable(x, z)` | boolean | navigation, placement |

**R52.** `surfaceAt` is **nearest-sample, not interpolated**. A surface is a discrete kind, and a
lerp between `sand` and `tarmac` is not a surface. The road edge is therefore a hard line one cell
wide, which is what a road edge is.

**R53.** Every query clamps to the grid rather than returning a sentinel for out-of-bounds. A vehicle
outside the bounds is above the border rim or already falling to the kill plane; a query that returned
"no ground" there would make the caller invent a fallback, and the callers are wheel physics and bot
steering.

<!-- D16-S5.10 -->### 5.10 Per-Surface Grip and Audio

**R54.** Grip is applied in the shared vehicle control operation (DEC-061), once per wheel per tick,
after the suspension ray has resolved:

```pseudo
for wheel in vehicle.wheels sorted by index:
    contact = wheel.raycastInfo.contactPointWS
    if wheel.isInContact:
        s = terrain.surfaceAt(contact.x, contact.z)
        wheel.frictionSlip = basefrictionSlip(wheel) * s.gripMultiplier
        rollingResistance += s.rollingResistance * wheelLoad(wheel)
```

**R55.** This is deliberately **not** implemented with Bullet's custom material callback. That
callback fires per contact point on the collision path, and a ray-cast wheel generates no contact
point — so the callback would be correct-looking code that never runs for a tyre. It is also a native
callback into Java on the physics thread, which the headless requirement (G17) and determinism (G2)
would both then have to reason about.

**R56.** Slot 25 (audio) reads the same `surfaceAt` for its tyre loop selection. The two must not
derive the surface independently: if the sound says gravel while the physics says tarmac, the mismatch
teaches the player to distrust the audio, which is worse than having no surface variation at all.

<!-- D16-S5.11 -->### 5.11 Drivability and Navigation

**R57.** The drivability grid is a bit per cell: drivable if `slopeDegAt ≤ maxDrivableSlopeDeg` and
the cell is not inside a structure footprint. It is generated once and is immutable, like the height
field.

**R58.** Connectivity is checked by one flood fill from the first spawn point over the drivable grid.
All spawn points must lie in the region it finds, and the union of road cells must be reachable from
it. Failure is an `A4xx` error, not a warning (R27).

**R58a. The same fill checks that the region does not touch the arena's edge.** The border rim
(D16-S5.5) is an *additive* rise, so how impassable it is depends on what the landform under it was
doing — at the shipped parameters it holds with 33 m to spare, and at three quarters of the rise it
has a gully a vehicle climbs out through. Which is which is a property of the seed, so tuning the
rise until one arena is sealed proves nothing about the next one. Measuring the reachable region
makes a leaky rim a load-time failure instead of a player discovering they can drive into the void.

**R59.** Bot navigation (D11-S5.4) uses this grid directly as a uniform-cost graph, with cost weighted
by `1 / surface.gripMultiplier`, so a bot prefers the highway without being told the highway exists.
This replaces the `navmesh.bin` reference in D08-S4.7 for generated arenas; a generated arena declares
no navmesh and needs none.

<!-- D16-S5.12 -->### 5.12 Determinism and Cross-Process Agreement

**R60.** The terrain is **derived, never replicated**. The authority sends `arenaId` and `terrainSeed`
in the handshake (D10-S4.2) and every peer generates the identical field, exactly as a vehicle's
network id block is derived rather than sent (DEC-059). A 601² height field is 1.4 MB; the seed is
8 bytes.

**R61.** That trade is only sound if every process produces a **bit-identical** field, so the
generator obeys four rules, each of which has broken a deterministic system in some project before:

1. All lattice randomness is integer hashing (R30). No `java.util.Random`, no `Math.random`.
2. All transcendental functions are `StrictMath`. `Math.sin` is permitted to differ between JVMs and
   platforms; `StrictMath.sin` is not.
3. Accumulation order is fixed and documented — octaves ascending, roads in array order, cells in
   row-major order. Floating-point addition is not associative, so "the same numbers in a different
   order" is a different field.
4. No parallelism inside a stage unless the reduction is order-independent.

**R62.** `heightFieldHash` (R25) is the check, and it is checked in CI against a golden value for a
fixed seed. A generator that drifts is otherwise found by two players desynchronising, which is the
most expensive place to find it.

---

<!-- D16-S6 -->## 6. Rendering

<!-- D16-S6.1 -->### 6.1 Terrain Mesh

**R63.** The client builds the visible terrain from the **same grids** the collision was built from —
not from a second evaluation of the noise, and not from a mesh loaded off disk. The failure this
prevents is the one `ArenaModel` already documents for the flat arena: geometry you can see and drive
through, or geometry you cannot see and stop dead against.

**R64.** The mesh is split into chunks of `TERRAIN_CHUNK_CELLS` per side, each with its own bounding
box, and chunks are frustum-culled. At `gridSize` 601 that is 100 chunks; drawing all of them is
720,000 triangles and drawing the visible third of them is not.

**R65.** Two levels of detail per chunk — full and half resolution — selected by distance, with a
**skirt**: a one-cell band around each chunk dropped vertically by the chunk's maximum LOD error. The
alternative, stitching LOD boundaries with transition strips, is more correct and considerably more
code, and a skirt's artefact (a sliver of near-vertical terrain at a chunk seam, seen edge-on at
distance) is invisible where a crack is not.

**R66.** Normals come from central differences on the height grid, computed once. Per-face normals
make a dune field look faceted at exactly the distance the dune field is the subject.

<!-- D16-S6.2 -->### 6.2 Surface Texturing

**R67.** Surface textures are **generated at load**, not shipped. Three tiling textures — sand,
tarmac, gravel — built from the same noise code the terrain uses, at 512², uploaded once. This follows
DEC-046's reasoning exactly: the licence on a downloaded texture is a problem the project already
has with its car models, and a generated texture has none.

**R68.** Blending uses the surface grid as a per-vertex weight set, so a vertex on a road edge carries
a hard-ish blend between tarmac and gravel while the interior carries one surface at full weight. Four
weights per vertex covers the surface enum with no splat map texture to allocate.

**R69.** Tiling repetition is broken by scaling the UV at two frequencies and blending on a
low-frequency noise mask. A 512² texture tiled across 600 m is 1,200 repeats; without this the eye
sees the grid, not the ground, and that is the single most common way procedural terrain reads as
cheap.

**R70.** Road markings are drawn as a separate strip mesh along the road's own spline, lifted 0.01 m,
rather than painted into the terrain texture. The spline is already a polyline of the right shape, the
markings then stay crisp at any camera distance, and `markings` becomes a per-road content choice
rather than a texture variant.

<!-- D16-S6.3 -->### 6.3 Sky

**R71.** The sky is analytic and baked once at load. From `sunAzimuthDeg`, `sunElevationDeg`,
`turbidity` and `groundAlbedo`, an atmosphere model produces five colours — zenith, horizon, near
ground, far ground and sun — which drive:

- the **skybox** cubemap that is actually drawn,
- the **irradiance** and **radiance** cubemaps the PBR shader samples for ambient and reflections,
- the **directional light**'s colour and direction,
- the **fog** colour.

**R72.** All five come from one model evaluated once, which is the entire point. The client today
builds outdoor image-based lighting procedurally from a hardcoded sun and draws **no sky at all** —
so a car reflects an environment that is not the one behind it, and the horizon is a clear colour.
Deriving the set from one sun makes the reflections, the shadows, the haze and the sky agree by
construction rather than by a person matching four numbers by eye.

**R73.** Turbidity is the desert knob. A high-turbidity sky has a pale, warm, wide horizon band and a
weak zenith blue, which is most of what makes a desert read as hot rather than as a blue-lit plain.

<!-- D16-S6.4 -->### 6.4 Fog and Aerial Perspective

**R74.** Exponential-squared distance fog, coloured with the sky's horizon colour, with density from
`sky.fogDensity`. This is not atmosphere for its own sake: on a 600 m open terrain the far rim would
otherwise meet the sky at a hard line, and every object would be equally legible at every distance,
which removes the depth cue a driver uses to judge closing speed.

**R75.** Fog density and the terrain's draw distance are one decision. Fog thick enough to hide the
rim is fog thick enough to let the furthest chunks be dropped entirely.

---

<!-- D16-S7 -->## 7. Destructible Structures

<!-- D16-S7.1 -->### 7.1 A Structure Is an Assembly

**R76.** A structure entity is built exactly like a vehicle entity minus the vehicle: one entity per
part, the D05 slot graph between them, a `PartComponent` with health, damage state and material per
part, and a fracture manifest per part type. The systems that already act on those components —
damage (12), fracture (13), detach (14), lifetime (16), destroy (27) — act on a structure with no
change and no knowledge that it is one.

**R77.** The one structural difference from D05-S5.2 is the body layout. A vehicle is **one dynamic
body with a compound shape** (DEC-004) because its parts share one inertia tensor that has to stay
correct as parts leave. A structure is **one static body per part**, because a structure has no
inertia tensor worth maintaining, never moves as a whole, and gains something real from the split: a
destroyed part is removed from the world without rebuilding its neighbours' shapes, and a hundred
static bodies cost the broadphase nothing while a hundred dynamic ones would cost the solver.

<!-- D16-S7.2 -->### 7.2 Static Until Broken

**R78.** A structure part's body is zero-mass and `STATIC` while its support chain to the ground is
intact. When a part is destroyed, the existing pipeline runs unchanged: it fractures into its
manifest's shards (13), and every part below it in the slot graph becomes unsupported.

**R79.** An unsupported part is **exactly D07-S5.7's existing detachment trigger** — a part whose
parent is gone or destroyed. `DetachSystem` (14) already turns such a part into debris with its own
body and its own mass. The only addition is that for a structure the new body's mass comes from the
part definition rather than from a share of a vehicle's, and the part's layer changes from `STATIC` to
the debris layer in the same operation.

**R80.** So a gantry whose leg is shot away drops its span, and the span's sign panel goes with it,
because each is a part whose parent stopped holding it. **No new system, no new component, no new
schedule slot.** That this falls out is not a happy accident — it is the reason a structure was
specified as an assembly instead of as a new kind of object.

<!-- D16-S7.3 -->### 7.3 What Is Reused Unchanged

| Concern | Mechanism | Change needed |
|---|---|---|
| Damage application, armour, propagation | `DamageApplication` (DEC-038) | none |
| Damage state machine | D07-S5.3 | none |
| Fracture into shards | `FractureSystem` (13) | none |
| Unsupported part falls | `DetachSystem` (14), D07-S5.7 | mass source, layer transition |
| Debris expiry | `LifetimeSystem` (16) | none |
| Native teardown | `EntityDestroySystem` (27) | none |
| Fracture manifests | D09 tool | none |
| Materials and density | `materials.json` (DEC-045) | none |
| Visual damage morphs | slot 23 | none |
| Replication of structural events | D10-S4.3 | structures join the replicated set |

**R81.** The new code is a factory and a placement pass. If implementing this requires a new system,
a new component, or a change to the damage pipeline, stop: the design has drifted from "a structure is
an assembly" and the cheap version has been lost.

---

<!-- D16-S8 -->## 8. Acceptance Criteria

- [ ] **AC-D16-1.** An arena with no `terrain` block loads as today's flat floor and box walls, and
      every existing physics regression fixture produces identical results.
- [ ] **AC-D16-2.** Generating the same `(arenaId, terrainSeed)` twice in one process, and in two
      processes, produces height fields that differ by no more than `TERRAIN_HASH_TOLERANCE`.
- [ ] **AC-D16-3.** A generated arena's `heightFieldHash` matches the golden value committed for its
      seed.
- [ ] **AC-D16-4.** Every spawn point is on drivable terrain, above the kill plane, and can reach
      every other spawn point over the drivable grid.
- [ ] **AC-D16-5.** Every carved road's longitudinal grade is at or below its `maxGradePct` at every
      station, and its surface cells are contiguous end to end.
- [ ] **AC-D16-6.** The mean slope of dune slip faces is within ±4° of `SAND_REPOSE_DEG`.
- [ ] **AC-D16-6a.** The drivable ground reachable from a spawn point is one region containing at
      least 90% of all drivable cells — an arena is not a set of parallel corridors (R34a).
- [ ] **AC-D16-6b.** That region does not touch the arena's edge on any side (R58a).
- [ ] **AC-D16-7.** A vehicle driving from tarmac onto sand shows a measurable drop in lateral grip
      and a measurable rise in rolling resistance, in the same tick the wheel's contact point crosses
      the boundary.
- [ ] **AC-D16-8.** The tyre audio material and the physics surface agree for every wheel on every
      tick.
- [ ] **AC-D16-9.** The terrain's rendered surface and its collision surface agree to within the
      collision margin at every sample.
- [ ] **AC-D16-10.** No structure overlaps another, overlaps a spawn clearance, or stands on terrain
      steeper than `MAX_DRIVABLE_SLOPE_DEG` before its pad is flattened.
- [ ] **AC-D16-11.** Destroying a structure's supporting part causes every part above it to fall,
      through `DetachSystem` alone, with no new schedule slot.
- [ ] **AC-D16-12.** Terrain generation runs headless with no GL context (G17), and the client and the
      dedicated server produce identical grids.
- [ ] **AC-D16-13.** The sun direction used by the skybox, the image-based lighting, the directional
      light and the fog colour all derive from `sky.sunAzimuthDeg` / `sunElevationDeg`.
- [ ] **AC-D16-14.** The height field's data buffer is reachable for the whole lifetime of its shape,
      and is released only after the shape is disposed (G19).
- [ ] **AC-D16-15.** Every spawn point on a generated arena stands on a levelled pad with a drivable
      ramp out of it (E2).

---

<!-- D16-S9 -->## 9. Edge Cases & Failure Modes

| # | Situation | Required behaviour |
|---|---|---|
| E1 | `gridSize` does not match the arena bounds and `cellSizeM` | A410 ERROR at validation; generation refuses to run. |
| E2 | A spawn point lands on a dune slip face | Generation flattens a spawn pad of `clearanceRadiusM`, exactly as for a structure. The ramp out of the pad is widened until it is drivable — a fixed-width falloff around a pad cut into a dune is a wall, which is the failure the pad exists to prevent, reintroduced by the fix for it. |
| E3 | Flood fill finds an unreachable spawn point | A411 ERROR. The arena is rejected; it is not "playable but awkward" (R27). |
| E3a | Flood fill reaches the arena's edge | A411 ERROR. The border rim has a gully; the arena is rejected rather than shipped with a hole in it (R58a). |
| E4 | A road's spline leaves the arena bounds | Legal. The road is clipped at the bounds and terminates in the border rim (R40). |
| E5 | Two roads cross | Later road in array order wins the overlapping cells (R10). No junction geometry is generated. |
| E6 | A road's derived profile needs a grade above `maxGradePct` to follow the land | The grade limiter wins and the road cuts through the landform. Reported as `maxGradePct` reached. |
| E7 | Every structure candidate is rejected | Warning with the rejection reasons and counts. An arena with no structures is playable. |
| E8 | A structure's `footprint.radiusM` is smaller than its geometry | Placement spacing is wrong and structures interpenetrate. Validated at asset-index build: A412 ERROR. |
| E9 | The height data buffer is garbage-collected while the shape lives | Native crash with no Java frame. Prevented by ownership (R47); a leak check asserts the pairing. |
| E10 | The height field's body is placed at `groundY` rather than the AABB midpoint | Terrain visibly offset from its collision by half the relief. Prevented by R48; asserted by AC-D16-9. |
| E11 | Client and server disagree on the field by one ULP | Desynchronised prediction. Caught by AC-D16-2 before it can ship; caused by breaking one of R61's four rules. |
| E12 | A vehicle drives outside the grid | Queries clamp (R53). The border rim and the kill plane handle the rest. |
| E13 | `biome` names an unimplemented value | A413 ERROR at load. The enum is closed (R6). |
| E14 | A structure part is destroyed but its children are already debris | No-op; the detach trigger is idempotent on a part that has already left (DEC-018). |
| E15 | Terrain relief puts a spawn point below `killPlaneY` | A414 ERROR. Bounds and kill plane are authored; the generator does not silently move either. |

---

<!-- D16-S10 -->## 10. Test Cases

| ID | Level | Case | Asserts |
|---|---|---|---|
| T-D16-1 | unit | `fbm` at a fixed point, fixed seed | Bit-identical across runs; matches golden |
| T-D16-2 | unit | Full grid generated twice | Byte-identical arrays |
| T-D16-3 | unit | Grid generated by two independently constructed generators | Byte-identical; catches hidden state |
| T-D16-4 | unit | Dune slip-face slope over a 601² desert grid | Mean within ±4° of `SAND_REPOSE_DEG` |
| T-D16-5 | unit | Road grade after limiting, on a deliberately hilly seed | Every station ≤ `maxGradePct` |
| T-D16-6 | unit | Road surface cells along a carved spline | Contiguous, width within one cell of `widthM` |
| T-D16-7 | unit | Cut and fill: terrain above and below the profile | Cutting and embankment both produced |
| T-D16-8 | unit | Border falloff | Drivable ground reachable from the centre never touches the arena edge |
| T-D16-8a | unit | Connected-component labelling of the drivable grid | Largest region holds ≥90% of drivable cells |
| T-D16-9 | unit | `heightAt` vs the raw grid at sample points | Exact at samples, monotone between |
| T-D16-10 | unit | `surfaceAt` at a road edge | Nearest-sample, no interpolated value |
| T-D16-11 | unit | Flood fill on a seed with a known basin | Unreachable spawn detected |
| T-D16-12 | unit | Placement with a seed, run twice | Identical transforms, identical rejections |
| T-D16-13 | integration | Height field shape + a dropped body | Rests on the surface within the collision margin |
| T-D16-14 | integration | Suspension ray against the height field at 40 m/s | Contact height variance below the DISC-017 figure |
| T-D16-15 | integration | Vehicle crossing a tarmac/sand boundary | Grip and rolling resistance change on the crossing tick |
| T-D16-16 | integration | Vehicle climbing a dune windward face, then a slip face | Windward climbable, slip face not |
| T-D16-17 | integration | Gantry with its leg destroyed | Span and panel fall; no new system involved |
| T-D16-18 | integration | Structure part destroyed twice in one tick | Idempotent; one debris body |
| T-D16-19 | physics | Seed-locked drive across a generated arena, 600 ticks | Final transform matches golden to `1e-4` |
| T-D16-20 | headless | Generate every shipped arena with no GL context | Exit 0; report written; no GL call attempted |
| T-D16-21 | integration | Buffer lifetime: shape disposed, then buffer released | Native tracker balances; no use-after-free |
| T-D16-22 | unit | Sky model at a fixed sun and turbidity | Five colours match golden; sun direction round-trips |
| T-D16-23 | integration | The shipped desert arena, loaded from `assets/` | Validates; every spawn point drivable and above the kill plane |

---

<!-- D16-S11 -->## 11. Cross-References

| Topic | Authoritative section |
|---|---|
| Arena definition schema | `docs/08_asset_pipeline.md#D08-S4.7` |
| Collision shapes and margins | `docs/06_physics_simulation.md#D06-S4.3` |
| Collision layers | `docs/06_physics_simulation.md#D06-S4.4` |
| Shape ownership | `docs/06_physics_simulation.md#D06-S5.2` |
| Ray-cast wheel model | `docs/06_physics_simulation.md#D06-S5.5` |
| Seeded random streams | `docs/06_physics_simulation.md#D06-S5.8` |
| Parts and the slot graph | `docs/05_vehicle_part_system.md#D05-S4.1` |
| Vehicle instantiation, for contrast | `docs/05_vehicle_part_system.md#D05-S5.2` |
| Detachment triggers | `docs/07_damage_destruction_model.md#D07-S5.7` |
| Fracture manifest | `docs/09_blender_destruction_tool.md#D09-S4.4` |
| Bot navigation | `docs/11_ai_bots_and_match_simulation.md#D11-S5.4` |
| Startup order | `docs/03_runtime_modes.md#D03-S5.1` |
| Handshake and message catalogue | `docs/10_networking_multiplayer.md#D10-S4.2` |
| Global invariants | `docs/00_master_index.md#D00-S5.2` |
