<!-- D09-S0 --># 09 — Blender Destruction Tool

**Document ID:** D09
**Owns:** The headless Blender CLI tool: argument schema, exit codes, fracture manifest schema, Voronoi fracturing, shape key generation, mass assignment, hull generation, export, self-verification, determinism, machine-readable error reporting.

---

<!-- D09-S1 -->## 1. Purpose

This document specifies the **destruction tool**: a headless Blender Python application that takes a clean authored mesh and produces game-ready destruction data — Voronoi shards, damage shape keys, per-shard masses, collision hulls, a glTF export, and a JSON manifest — then **verifies its own output** before reporting success.

The tool is **automation-first**: its primary user is an AI agent invoking it from a shell, reading its exit code, and parsing its JSON output. Every design decision follows from that: no interactive UI, no prompts, no partial success, deterministic output, and failures reported as structured data an agent can act on without reading prose.

Requirements are numbered `R1..Rn`, cited as `D09-R21`.

---

<!-- D09-S2 -->## 2. Scope

<!-- D09-S2.1 -->### 2.1 In Scope

- The tool contract: inputs, outputs, invocation, exit codes, stdout/stderr discipline.
- CLI argument schema.
- Fracture manifest schema (the tool's primary output contract).
- Voronoi fracturing algorithm with seeded site generation.
- Damage shape key (morph target) generation.
- Per-shard mass assignment from volume × material density.
- Collision hull generation and simplification.
- glTF export settings.
- The self-verification pipeline (seven stages).
- Determinism guarantees and how they are achieved.
- Machine-readable failure reporting.

<!-- D09-S2.2 -->### 2.2 Non-Goals

- **NG1.** Independent verification in the engine — `docs/14_test_environment.md#D14-S5.2`. The tool checks what it produced; the harness checks what the game will load. Both exist deliberately.
- **NG2.** Part definition authoring (`part.json`) — `docs/08_asset_pipeline.md#D08-S4.2`. The tool never writes `part.json`.
- **NG3.** An interactive Blender add-on with UI panels.
- **NG4.** Runtime fracture. Everything here happens at asset time.
- **NG5.** Texturing, UV generation, or material authoring. The tool preserves materials; it does not create them.
- **NG6.** Rigging, animation, or skinning.

---

<!-- D09-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S4.3`/`#D00-S4.4` | Units and the single axis-conversion point |
| `docs/00_master_index.md#D00-S5.2` | G7 (mass conservation), G11 (tool determinism), G12 (manifest is the contract) |
| `docs/08_asset_pipeline.md#D08-S4.1` | Source `.blend` conventions the tool relies on |
| `docs/08_asset_pipeline.md#D08-S4.3` | `materials.json` — the shared density table |
| `docs/08_asset_pipeline.md#D08-S6.1` | Where the manifest schema lives |
| `docs/06_physics_simulation.md#D06-S4.3` | Hull constraints the tool must satisfy |
| External: Blender 4.2 LTS (`bpy`, `bmesh`, `mathutils`) only — no add-ons, no `voro++` (R8a, DEV-002) | Implementation substrate |

---

<!-- D09-S4 -->## 4. Data Contracts

<!-- D09-S4.1 -->### 4.1 Tool Contract

**R1.** The tool is invoked as a Blender background process:

```
blender --background --factory-startup [<input.blend>] \
        --python -m syndicate_fracture -- <tool arguments>
```

**R2.** Contract summary:

| Aspect | Contract |
|---|---|
| **Input** | One `.blend` or `.glb` containing one or more clean meshes with material assignments and slot empties (D08-S4.1) |
| **Output** | `mesh.glb` (intact + morphs + collision node), `shards.glb` (shard meshes), `fracture_manifest.json`; optionally a processed `.blend` with `--keep-blend` |
| **Success** | Exit 0 **only if** every self-verification check passed (D09-S7). There is no "succeeded with problems". |
| **Failure** | Non-zero exit + a `failure report` JSON on stdout (D09-S9) |
| **stdout** | **JSON only.** Exactly one JSON document, always: the manifest summary on success, the failure report on failure. Nothing else ever goes to stdout. |
| **stderr** | Human/diagnostic log lines, `LEVEL message` format. Blender's own banner noise is suppressed with `--factory-startup` and filtered. |
| **Idempotence** | Re-running with identical inputs and seed overwrites the output with byte-identical mesh topology and an equal manifest (excluding timestamps) |
| **Atomicity** | Outputs are written to a temp directory and moved into place only after verification passes, so a failed run never leaves a half-written asset |
| **Side effects** | None outside `--out` (and the temp dir). Never modifies the input file. |

**R3.** `--factory-startup` is mandatory: user preferences, enabled add-ons, and unit settings would otherwise vary per machine and break determinism (G11). The tool enables **no add-ons at all** — the half-space cell construction of R8a depends on `bmesh` and `mathutils` only, which is what makes the guarantee this rule asks for achievable rather than aspirational (DEV-002).

<!-- D09-S4.2 -->### 4.2 CLI Argument Schema

**R4.** Arguments after `--`. Unknown arguments are a **fatal usage error** (exit 64), never ignored.

| Argument | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `--input <path>` | path | yes | — | Source `.blend` or `.glb` |
| `--out <dir>` | path | yes | — | Output directory; created if absent |
| `--object <name>` | string | no | all mesh objects | Process only this object |
| `--seed <int>` | int | no | `1337` | Master seed for all randomness (G11) |
| `--shards <int>` | int | no | `24` | Target shard count; clamped to `[2, MAX_SHARDS_PER_PART=256]` |
| `--shard-mode <mode>` | enum | no | `uniform` | `uniform` \| `surface_biased` \| `impact_biased` |
| `--impact-point x,y,z` | vec3 | no | — | Required when `--shard-mode impact_biased` |
| `--destruction-class <class>` | enum | **yes** | — | The part's class (D15-S5.7). The tool exits 77 rather than fracturing a class that receives no shards, and it cannot refuse what it was not told. Case-insensitive; never defaulted, because a misspelled class silently becoming `RIGID` would author nothing and report success |
| `--material-table <path>` | path | no | `assets/materials/materials.json` | Density source (D08-S4.3) |
| `--material-override <id>` | string | no | — | Force a material for every mesh |
| `--hull-max-verts <int>` | int | no | `32` | Per-shard hull vertex budget |
| `--part-hull-max-verts <int>` | int | no | `64` | Intact-part hull budget |
| `--min-shard-volume <float>` | float | no | `1e-6` | m³; smaller shards are merged (D09-S6.2) |
| `--shell-thickness <float>` | float | no | `0.0` | m; above zero, the source is treated as a **surface** and fractured by S5.2.1 — cut into patches, then each patch thickened to this. Zero keeps the solid path. The caller states this; the tool never infers it from the geometry, because a thin solid and a surface look alike and guessing wrong silently changes a part's mass. |
| `--expected-mass <float>` | float | no | — | If given, mass plausibility is checked against it |
| `--mass-tolerance <float>` | float | no | `0.02` | Fractional tolerance for conservation checks |
| `--keep-blend` | flag | no | off | Also write `processed.blend` into the staged output, so a fracture that looks wrong can be opened |
| `--no-export` | flag | no | off | Run fracture + verify, skip glTF export (fast iteration) |
| `--verify-only` | flag | no | off | Re-check the outputs already in `--out` and **write nothing**. Answers the checks that compare the manifest against the files beside it — schema, mass conservation (G7), the shard set against `shards.glb`, the absence of damage morphs, and the declared transform against D15-S5.7. Reports as *skipped* anything derived from geometry the tool would have to re-fracture to hold. Never starts Blender |
| `--report <path>` | path | no | stdout only | Also write the JSON result to a file |
| `--log-level <level>` | enum | no | `INFO` | stderr verbosity |
| `--dry-run` | flag | no | off | Parse and validate arguments, print the plan as JSON, change nothing |
| `--version` | flag | no | — | Print `{"toolVersion": "..."}` and exit 0 |

<!-- D09-S4.3 -->### 4.3 Exit Codes

**R5.** Exit codes are stable and are the agent's primary control signal. They are grouped so an agent can branch on the category with integer division.

| Code | Name | Meaning | Agent's typical response |
|---|---|---|---|
| 0 | `OK` | All stages and all verification checks passed | Proceed to `part.json` authoring / harness |
| 64 | `USAGE` | Bad or unknown argument, contradictory flags | Fix the invocation |
| 65 | `INPUT_INVALID` | Input file unreadable, wrong format, or contains no mesh | Check the source path/export |
| 66 | `INPUT_GEOMETRY_INVALID` | Mesh not watertight, has loose geometry, zero volume, or NaN coordinates | Fix the source mesh |
| 67 | `MATERIAL_UNRESOLVED` | A mesh material has no entry in the material table | Add the material or pass `--material-override` |
| 68 | `FRACTURE_FAILED` | Voronoi/boolean stage produced no shards or failed | Lower `--shards`, or fix source geometry |
| 69 | `SHAPEKEY_FAILED` | Morph generation failed or produced degenerate morphs. Raised by `syndicate_deform`; the fracture tool no longer generates morphs | Lower `--amplitude`, check topology |
| 70 | `BLENDER_ERROR` | Blender API raised, or Blender not found | Check the Blender install/version |
| 77 | `TRANSFORM_NOT_PERMITTED` | The transform was asked of a destruction class D15-S5.7 does not give it — a windscreen sent to the deform tool, a door sent to the fracture tool, or a mesh arriving at the fracture tool already carrying damage morphs. Its own code rather than `USAGE` because the invocation is well formed and the content decision behind it is what is wrong | Fix the part's class, or run the other tool |
| 71 | `HULL_FAILED` | A shard's convex hull could not be built or exceeds budget | Reduce shard count / raise budget deliberately |
| 72 | `MASS_IMPLAUSIBLE` | Mass conservation or expected-mass check failed | Check density, units, and watertightness |
| 73 | `VERIFICATION_FAILED` | One or more self-verification checks failed (any other than mass) | Read `failures[]` in the report |
| 74 | `EXPORT_FAILED` | glTF export raised or produced an unreadable file | Check disk/permissions/exporter version |
| 75 | `OUTPUT_WRITE_FAILED` | Could not write to `--out` | Check disk/permissions |
| 76 | `DETERMINISM_VIOLATION` | The internal determinism self-check found run-to-run divergence | Report as a tool bug; do not ship the asset |

**R6.** Exit code 73 is the general verification failure; 66/67/68/69/71/72/76 are the specific ones. A specific code always wins over 73, so an agent can branch without parsing the report in the common cases.

<!-- D09-S4.4 -->### 4.4 Fracture Manifest Schema

**R7.** `fracture_manifest.json`, validated against `schemas/fracture_manifest.schema.json` (D08-S6.1). This is the tool's contract with the game and the harness (G12).

```json
{
  "schemaVersion": "1.0.0",
  "toolVersion": "0.1.0",
  "blenderVersion": "4.2.1",
  "generatedAt": "2026-08-07T14:02:11Z",
  "sourceFile": "art-source/parts/panels/panel_plate_medium_01.blend",
  "sourceHash": "sha256:9f2c…",
  "partTypeId": "glass_eclipse_windscreen_01",
  "transform": "FRACTURE",
  "destructionClass": "GLASS",
  "materialId": "glass",
  "seed": 1337,
  "parameters": {
    "shards": 24,
    "shardMode": "uniform",
    "hullMaxVerts": 32,
    "minShardVolumeM3": 1e-6
  },
  "partMassKg": 160.0,
  "partVolumeM3": 0.020382,
  "densityKgPerM3": 7850.0,
  "comLocal": { "x": 0.0, "y": 0.0, "z": 0.0 },
  "inertiaDiagonal": { "x": 21.44, "y": 30.17, "z": 28.82 },
  "aabbMin": { "x": -1.0, "y": -0.5, "z": -0.05 },
  "aabbMax": { "x":  1.0, "y":  0.5, "z":  0.05 },
  "morphTargets": ["dmg_25", "dmg_50", "dmg_75", "dmg_100"],
  "morphStats": [
    { "name": "dmg_25",  "meanDisplacementM": 0.0071, "maxDisplacementM": 0.0154 },
    { "name": "dmg_50",  "meanDisplacementM": 0.0163, "maxDisplacementM": 0.0322 },
    { "name": "dmg_75",  "meanDisplacementM": 0.0288, "maxDisplacementM": 0.0489 },
    { "name": "dmg_100", "meanDisplacementM": 0.0421, "maxDisplacementM": 0.0600 }
  ],
  "shardCount": 24,
  "shards": [
    {
      "id": "panel_plate_medium_01_shard_000",
      "name": "shard_000",
      "index": 0,
      "massKg": 6.83,
      "volumeM3": 0.00087,
      "centroid": { "x": -0.71, "y": 0.22, "z": 0.0 },
      "localTransform": {
        "position": { "x": -0.71, "y": 0.22, "z": 0.0 },
        "rotation": { "x": 0.0, "y": 0.0, "z": 0.0, "w": 1.0 }
      },
      "aabbMin": { "x": -0.92, "y": 0.05, "z": -0.05 },
      "aabbMax": { "x": -0.50, "y": 0.41, "z":  0.05 },
      "vertexCount": 28,
      "faceCount": 42,
      "hullVertexCount": 24,
      "materialId": "steel_hardened",
      "neighbors": [1, 2, 7]
    }
  ],
  "collision": {
    "partHullVertexCount": 58,
    "partHullPieces": 1,
    "shardHullMaxVertexCount": 31
  },
  "topologyHash": "sha256:4b71…",
  "verification": {
    "passed": true,
    "checks": [
      { "id": "TV-001", "name": "All shards have positive mass", "status": "pass",
        "measured": "min 0.42 kg", "expected": "> 0.01 kg" }
    ],
    "warnings": []
  }
}
```

**R8.** Field rules:

| Field | Rule |
|---|---|
| `toolVersion` | The tool's own version, from `gradle.properties`-derived packaging (D02-R17). Always present (A506). |
| `sourceHash` | SHA-256 of the input file; lets an agent detect a stale manifest without re-running. |
| `seed`, `parameters` | The full invocation, so a run is reproducible from the manifest alone. |
| `partMassKg` | `partVolumeM3 × densityKgPerM3`, rounded to 6 significant decimals. |
| `comLocal`, `inertiaDiagonal` | Computed from the intact mesh at uniform density; the harness re-derives independently (D14-S5.4). |
| `transform` | Always `"FRACTURE"`. What this manifest *is*, so a consumer can tell a manifest that should exist from one that should not. Deformation writes `deform_manifest.json` with `"DEFORM"`. |
| `destructionClass` | The D15-S5.7 class this was authored for, checked against `part.json`'s own by the asset gate (A510). Without these two fields nothing could catch a manifest on a part that must not have one, which is how a steel door could have ended up with shards (DISC-068). |
| `morphTargets` | Always empty in a fracture manifest, and kept rather than dropped: `TV-006` compares it with what the exported mesh carries, so it is the check that a fracturing part shipped no damage morphs. |
| `shards[].id` | Globally unique, `<partTypeId>_shard_<nnn>`. **Stable for a given seed** — this is what makes golden comparison by id meaningful (D14-S5.8). |
| `shards[].name` | The node name in `shards.glb`; must match exactly (A501). |
| `shards[].localTransform` | Position/rotation of the shard relative to the part origin — used to spawn debris bodies (D07-S5.6). |
| `shards[].neighbors` | Indices of shards sharing a Voronoi face; informational (useful for future progressive fracture). |
| `topologyHash` | SHA-256 over the sorted, quantised (1e-6 m) vertex positions and face indices of all shards. The determinism fingerprint (G11). |
| `verification` | Embedded copy of the self-verification result, so a manifest is self-describing even if the report file is lost. |

---

<!-- D09-S5 -->## 5. Logic & Algorithms

<!-- D09-S5.1 -->### 5.1 Top-Level Pipeline

```pseudo
function main(argv):
    args = parseArgs(argv)                      # exit 64 on anything unknown
    if args.version: print({"toolVersion": VERSION}); exit(0)
    if args.dryRun:  print(plan(args));          exit(0)

    seedAllRandomness(args.seed)                # D09-S8
    tmp = makeTempDir()
    try:
        scene   = loadInput(args.input)                          # exit 65
        objects = selectObjects(scene, args.object)
        materials = loadMaterialTable(args.materialTable)        # exit 67 if unresolved

        results = []
        for obj in objects sortedBy name:                        # deterministic order
            validateSourceGeometry(obj)                          # STAGE 1, exit 66
            refuseExistingDamageMorphs(obj, args)                # STAGE 1, exit 77
            shards   = voronoiFracture(obj, args)                # STAGE 2, exit 68
            masses   = assignMasses(obj, shards, materials, args)# STAGE 3, exit 72
            hulls    = generateHulls(obj, shards, args)          # STAGE 4, exit 71
            manifest = buildManifest(obj, shards, masses, hulls, args)
            if not args.noExport:
                exportGltf(obj, shards, tmp)                     # STAGE 5, exit 74
            report   = selfVerify(obj, shards, masses, hulls, manifest, tmp)  # STAGE 6
            if not report.passed:
                print(failureReport(report)); exit(worstExitCode(report))
            manifest.verification = report
            results.append((manifest, tmp))

        # Atomic publish: only now do outputs become visible.
        for (manifest, tmp) in results:
            writeJson(tmp + "/fracture_manifest.json", manifest)
            movePublish(tmp, args.out)                           # exit 75 on IO failure
        print(successSummary(results))
        exit(0)
    finally:
        cleanup(tmp)
```

<!-- D09-S5.2 -->### 5.2 Voronoi Fracturing

```pseudo
# STAGE 2. Voronoi cells partition the part's volume. A cell is built AS A HALF-SPACE
# SET and intersected with the source exactly — see R8a. Blender 4.2 no longer ships the
# Cell Fracture add-on, and depending on one would reintroduce the add-on-state
# determinism risk R3 exists to remove.

function voronoiFracture(obj, args):
    # --- 2a. Generate fracture SITES ourselves. ------------------------------------
    # Explicit sites are the only way to guarantee G11 across Blender patch versions:
    # any "own particles/verts" source mode depends on scene state we do not control.
    rng   = Pcg32(seed = mix(args.seed, hash(obj.name)))
    bbox  = obj.boundingBoxLocal()
    sites = []

    switch args.shardMode:
        case uniform:
            # Poisson-ish: rejection-sample so sites are not clustered, which produces
            # shards of comparable size rather than a few huge and many slivers.
            minDist = pow(bbox.volume / args.shards, 1/3) * 0.55
            attempts = 0
            while sites.size < args.shards and attempts < args.shards * 200:
                p = bbox.samplePoint(rng)
                if pointInsideMesh(obj, p) and all(distance(p, s) >= minDist for s in sites):
                    sites.append(p)
                attempts += 1
            if sites.size < 2: fail(FRACTURE_FAILED, "could not place sites")

        case surface_biased:
            # Bias toward the surface: real plate damage shatters near the skin.
            for i in 0 .. args.shards-1:
                s = samplePointNearSurface(obj, rng, inwardBiasM = 0.15 * bbox.minExtent)
                sites.append(s)

        case impact_biased:
            # Dense near the impact point, sparse away from it: r ~ U^(1/3) scaled.
            for i in 0 .. args.shards-1:
                dir = rng.nextUnitVector()
                r   = pow(rng.nextFloat(), 1/3) * bbox.maxExtent * 0.6
                p   = args.impactPoint + dir * r
                sites.append(clampInsideMesh(obj, p))

    sites = sortLexicographically(sites)      # G11: cell ORDER must not depend on
                                              # insertion order or hash iteration

    # --- 2b. Build each cell as an exact convex polytope, then intersect. ----------
    # R8a. A Voronoi cell about site s is by DEFINITION the intersection of the
    # half-spaces bisecting s against every other site. Constructing it that way is not
    # a workaround for the missing add-on — it is the definition, it needs no
    # third-party library, and it is exact rather than a boolean solver's approximation.
    raw = []
    for s in sites:
        cell = bbox.asHalfSpaceSet()
        for other in sites where other != s:
            cell = cell.clip(bisectingPlane(s, other))     # half-space intersection
        # A non-convex source is first decomposed into disjoint convex pieces by a solid
        # BSP over its own face planes (DEC-011), so `cell ∩ source` is a union of exact
        # polytope intersections. An approximate decomposition (V-HACD) and a per-cell
        # mesh boolean were both rejected: each gives up the exactness that is the point.
        for piece in convexPiecesOf(obj):
            fragment = cell.intersect(piece)
            if not fragment.isEmpty(): raw.append(fragment.toMesh())
    if raw.isEmpty(): fail(FRACTURE_FAILED, "no cell intersected the source")

    # --- 2c. Post-process: merge slivers, clean, rename deterministically. ---------
    shards = []
    for cell in raw:
        applyAllTransforms(cell)                       # bake to mesh (D08-R2)
        removeDoubles(cell, threshold = 1e-5)
        deleteLooseGeometry(cell)
        recalcNormalsOutward(cell)
        v = meshVolume(cell)
        if v < args.minShardVolume:
            mergeIntoNearestNeighbour(cell, shards)    # D09-R11
            continue
        shards.append(cell)

    if shards.size < 2: fail(FRACTURE_FAILED, "all cells merged away; lower --min-shard-volume")

    # Deterministic naming and ordering: sort by centroid, lexicographic on (x,y,z)
    # quantised to 1e-5 m. Never by creation order, which the operator does not fix.
    shards = sortBy(shards, key = quantisedCentroid)
    for i, s in enumerate(shards): s.name = "shard_" + zeroPad(i, 3)

    computeNeighbourGraph(shards)                      # shared-face adjacency, for the manifest
    return shards
```

**R9.** Fracture is never recursive. Recursion would produce a shard count that is a function of the construction's internal choices, which defeats both the shard budget and determinism. Different shard sizes are achieved by site distribution (`--shard-mode`).

**R10.** Sites are generated by our own PCG32, sorted lexicographically before use, and shards are re-sorted by quantised centroid after generation. Two sorts: the first fixes which cell is which, the second fixes the order they are written in, and neither is implied by the other.

**R11.** Sub-minimum-volume cells are **merged into the nearest neighbour**, never dropped and never mass-clamped. Dropping would break mass conservation (G7); clamping mass would break the volume × density relationship (D06-R3).

<!-- D09-S5.2.1 -->#### 5.2.1 Shell Fracturing

§5.2 fractures a **solid**. A pane of glass is a **surface**, and the difference is not a matter of degree: given a windscreen thickened into a solid, the partition in §5.2 has to split on hundreds of nearly-parallel face planes, exceeds its depth bound, and falls back to a path that conserves no volume. Measured on the Eclipse, the windscreen's shards summed to 45.3 kg against a 20.7 kg pane, and every pane on both shipped cars failed one guard or another (`DISC-039`).

A shell is fractured by **cutting first and thickening second**.

```pseudo
# STAGE 2b. Cut the surface into Voronoi patches, then give each patch its thickness.
# Selected by --shell-thickness > 0; the caller knows the part is a shell because the
# taxonomy told it so (D15-S5.7), not by guessing from the geometry.

function shellFracture(obj, args):
    surface = readMesh(obj)
    t       = args.shellThickness                    # metres; the class's own wall (D15-R33)
    rng     = Pcg32(seed = mix(args.seed, hash(obj.name)))

    # --- 2b-i. Sites lie ON the surface, area-weighted, with no inward bias. -------
    # §5.2 pushes sites inward because real plate damage shatters near the skin. A shell
    # has no interior to bias towards: all of it is skin. A site off the sheet gives a
    # bisector that meets it at a glancing angle and cuts a crescent instead of a cell.
    sites = sampleOnSurface(surface, args.shards, rng)
    if sites.size < 2: fail(FRACTURE_FAILED, "could not place sites on the surface")

    # --- 2b-ii. Each patch is the surface bisected by its own half-spaces. ---------
    patches = []
    for site in sites:
        m = copyOf(surface)
        for (normal, offset) in bisectorPlanes(site, sites - site):
            bisect(m, normal, offset - CELL_MARGIN_M, clearOuter = true, fill = false)
        removeDoubles(m); deleteLooseGeometry(m)
        if surfaceArea(m) < args.minPatchArea: continue
        patches.append(m)
    if patches.size < 2: fail(FRACTURE_FAILED, "the cut produced fewer than two patches")

    # --- 2b-iii. The conservation check, on the cut itself. ------------------------
    checkCoverage(sum(surfaceArea(p) for p in patches), surfaceArea(surface))

    # --- 2b-iv. Only now does each patch become a solid. --------------------------
    for p in patches:
        recalcNormalsOutward(p)                      # one offset direction per patch
        solidify(p, thickness = t)                   # closed slab: area x t of material

    return postProcess(patches, args)                # merge, sort, name: as §5.2's 2c
```

**R11a.** The cut is **not filled**. A solid must be re-closed after every cut or its volume stops meaning anything; a surface has an open boundary already, and filling the cut would web each patch over.

**R11b.** The coverage check compares the patches' area **as cut** against the source's, within 1%. It is deliberately not made by recovering an area from each finished slab's volume: `solidify` offsets along vertex normals, so a patch of convex curvature encloses more than `its own area × thickness` — 0.1% on a windscreen and 6% on a tightly curved quarter-light, measured on the two shipped cars. That excess is real glass and belongs in the shard's mass; folding it into this check only makes the check fail on curvature it was never about (`DISC-043`).

**R11c.** A shell part's volume for R16's mass is the **sum of its shards'**, not `area × thickness`. The two differ by exactly the curvature excess in R11b, and defining the part as its pieces is what makes G7 exact here rather than true to a tolerance — which is the property the solid path's fallback lost and the reason this path exists.

<!-- D09-S5.3 -->### 5.3 Damage Shape Key Generation — `syndicate_deform`

**This section specifies a second tool, not a stage of the first.** D00-S6 gives the project two
separate words for two separate things — *deformation* is "continuous visual mesh change driven by
shape keys" and its glossary note is "not fracture" — and D15-S5.7 gives no destruction class both.
The implementation authored both in one pass regardless, by default, for any mesh handed to it
(DISC-068), so the algorithm below is now `python3 -m syndicate_deform`:

- it takes `--input`, `--out`, `--seed`, `--destruction-class` and `--levels`/`--amplitude` in
  place of `--damage-morphs`/`--morph-amplitude`, and refuses a class D15-S5.7 does not deform
  with exit 77;
- it subdivides to the class's target edge length first (D15-S5.7), because the density exists to
  serve the dent and a tool that authored morphs without it would produce a facet;
- it writes `deform_manifest.json` beside the mesh, declaring `transform: "DEFORM"` and the class;
- it owns `TV-002` and `TV-003`, which follow the shape keys they check. **Ids are permanent and
  never reused** (D09-S7): they mean what they always meant, reported by the tool that authors them.

The fracture tool now *refuses* a mesh that already carries `dmg_*` keys on a class that does not
deform (exit 77, E10) rather than deleting and re-authoring them.

```pseudo
# Produce N morph targets on the INTACT mesh representing progressive damage.
# These are cosmetic at runtime (D07-S4.2) but must be well-formed: no NaN, no zero-area
# faces, monotonically increasing severity (D14 ASSET-008/009).

function generateDamageMorphs(obj, levels, amplitude, seed):
    if levels == 0: return []

    mesh = obj.data
    if mesh.shape_keys is None:
        obj.shape_key_add(name = "Basis", from_mix = False)      # required base key

    basis   = captureVertexPositions(mesh)                        # local space
    rng     = Pcg32(seed = mix(args.seed, hash(obj.name), 0xM0RPH))
    normals = computeVertexNormals(mesh)
    levels  = ["dmg_25", "dmg_50", "dmg_75", "dmg_100"][0 : args.damageMorphs]
    stats   = []

    # Deformation model: a sum of smooth "dent" fields plus a small high-frequency
    # crumple. Dents are placed once and REUSED across levels with increasing depth,
    # so the morphs form a coherent progression rather than four unrelated shapes.
    dentCount = clamp(round(4 + 8 * surfaceArea(mesh)), 4, 16)
    dents = []
    for i in 0 .. dentCount-1:
        dents.append({ center: samplePointOnSurface(mesh, rng),
                       radius: uniform(rng, 0.15, 0.45) * boundingRadius(mesh),
                       weight: uniform(rng, 0.5, 1.0),
                       dir:    -normalAt(center) })               # dents push inward

    for levelIndex, name in enumerate(levels):
        severity = (levelIndex + 1) / len(levels)                 # 0.25, 0.5, 0.75, 1.0
        key = obj.shape_key_add(name = name, from_mix = False)

        maxDisp = 0; sumDisp = 0
        for vi in 0 .. mesh.vertexCount-1:
            p = basis[vi]
            displacement = ZERO_VEC

            # (a) Smooth dents, falling off with a smoothstep kernel.
            for d in dents:
                dist = distance(p, d.center)
                if dist < d.radius:
                    falloff = smoothstep(1.0 - dist / d.radius)   # 1 at centre, 0 at rim
                    depth   = args.morphAmplitude * severity * d.weight * falloff
                    displacement += d.dir * depth

            # (b) High-frequency crumple along the normal, deterministic per vertex.
            #     Value noise seeded by (seed, vertexIndex) so it is stable regardless
            #     of iteration order or Blender version.
            crumple = valueNoise3(p * CRUMPLE_FREQ (18.0), seed = mix(args.seed, vi))
            displacement += normals[vi] * crumple
                            * args.morphAmplitude * 0.25 * severity

            # (c) Edge preservation: vertices on sharp/boundary edges move less, so
            #     silhouettes stay readable and the part remains recognisable (P1).
            if isSharpOrBoundaryVertex(mesh, vi): displacement *= 0.4

            newPos = p + displacement
            assertFinite(newPos)                                   # exit 69 on NaN
            key.data[vi].co = newPos
            m = magnitude(displacement); maxDisp = max(maxDisp, m); sumDisp += m

        # Guards that make ASSET-008/009 pass by construction rather than by luck.
        meanDisp = sumDisp / mesh.vertexCount
        if maxDisp < MORPH_MIN_DELTA_M (0.005):
            fail(SHAPEKEY_FAILED, "morph {} is degenerate (max displacement {})", name, maxDisp)
        if levelIndex > 0 and meanDisp <= stats[-1].meanDisplacementM:
            fail(SHAPEKEY_FAILED, "morph severity not monotonic at {}", name)
        if anyZeroAreaFaceAtWeight(mesh, key, weight = 1.0, minArea = MIN_FACE_AREA_M2):
            fail(SHAPEKEY_FAILED, "morph {} produces a zero-area face", name)

        stats.append({ name: name, meanDisplacementM: meanDisp, maxDisplacementM: maxDisp })

    return stats
```

**R12.** Morphs are generated on the **intact** mesh only. Shards never carry morph targets — a shard is already the "fully broken" representation, and morphing debris would cost memory for no visible benefit.

**R13.** Displacement is inward-biased (dents, not bulges). Outward displacement would make a damaged part occupy more space than its collision hull, producing visible interpenetration since collision geometry never deforms (D06-NG5).

<!-- D09-S5.4 -->### 5.4 Mass Assignment

See D09-S6 for the algorithm and the density table — mass is important enough to have its own section.

<!-- D09-S5.5 -->### 5.5 Collision Hull Generation

```pseudo
# STAGE 5. Every shard needs a convex hull Bullet can use, within a vertex budget
# (D06-S4.3). We generate and validate here so the game never has to fall back.

function generateHulls(obj, shards, args):
    hulls = {}

    # --- Intact part hull(s) ------------------------------------------------------
    colSource = findObject(obj.name + "_col") or obj              # D08-R3
    pieces = splitByLooseParts(colSource)                          # each piece -> one hull
    partHulls = []
    for piece in pieces sortedBy quantisedCentroid:
        h = convexHull(piece)                                      # bmesh.ops.convex_hull
        h = simplifyHull(h, maxVerts = args.partHullMaxVerts)
        validateHull(h, source = piece)
        partHulls.append(h)
    hulls["part"] = partHulls

    # --- Per-shard hulls ----------------------------------------------------------
    for s in shards:
        h = convexHull(s)
        h = simplifyHull(h, maxVerts = args.hullMaxVerts)
        validateHull(h, source = s)
        hulls[s.name] = h
    return hulls

function simplifyHull(hull, maxVerts):
    # R14a. Removing a vertex from a CONVEX hull can only shrink it, never grow it, so
    # the rule is "keep the most volume", not "add the least". The wording this replaces
    # described polygon simplification, where the opposite comparison is the right one.
    if hull.vertexCount - maxVerts > GREEDY_MAX_REDUCTION:      # 8
        # Direction sampling: keep the extreme vertex along each of maxVerts
        # Fibonacci-sphere directions. The greedy loop below re-hulls once per candidate
        # per step — O(n^3) — and takes minutes on a 362-vertex sphere hull.
        hull = convexHull(extremeVerticesAlong(fibonacciDirections(maxVerts), hull))
    else:
        while hull.vertexCount > maxVerts:
            best = argmax(v in hull.vertices, key = volumeIfRemoved(hull, v))
            hull = convexHull(hull.vertices - best)

    # R14b. Simplifying a curved hull ALWAYS leaves it inside its source, so inflation
    # is not optional. Scale about the centroid by 1 + margin / r_min, where r_min is
    # the closest any face plane comes to the centroid: every plane then moves outward
    # by at least margin, and enclosure holds by construction rather than by luck.
    return inflateHull(hull, margin = HULL_ENCLOSE_M)

function validateHull(hull, source):
    if hull.vertexCount < 4:            fail(HULL_FAILED, "degenerate hull for " + source.name)
    if hullVolume(hull) <= 0:           fail(HULL_FAILED, "zero-volume hull for " + source.name)
    if hull.vertexCount > budget:       fail(HULL_FAILED, "hull exceeds budget")
    for v in source.vertices:
        # The hull must ENCLOSE the source; simplification may shave a hair, bounded
        # by HULL_ENCLOSE_M (D14-S6.4).
        if signedDistanceToHull(hull, v) > HULL_ENCLOSE_M (0.002):
            fail(HULL_FAILED, "hull does not enclose source vertex")
```

**R14.** Hulls are generated *by the tool*, verified by the tool, verified again by the harness in Bullet (D14 ASSET-010/011/012), but **not stored** in the manifest as geometry — the game rebuilds them from the shard meshes with the same budget. Storing them would create a third representation to keep in sync. The manifest records only the *counts* so a mismatch is detectable.

<!-- D09-S5.6 -->### 5.6 Export

```pseudo
# STAGE 6. Two glTF files per part. Export settings are fixed here and nowhere else
# (D08-R14) — this is the single axis/unit conversion point in the entire project (D00-R16).

GLTF_SETTINGS = {
    export_format:        'GLB',
    export_yup:           True,          # Blender Z-up -> glTF/game Y-up. THE conversion.
    export_apply:         True,          # apply modifiers
    export_morph:         True,
    export_morph_normal:  False,         # normals per morph triple memory for little gain
    export_morph_tangent: False,
    export_draco_mesh_compression_enable: False,   # lossy on morph deltas; breaks G11
    export_materials:     'EXPORT',
    export_cameras:       False,
    export_lights:        False,
    export_animations:    False,
    export_extras:        True,          # carries slot custom properties (D08-R13)
    export_texcoords:     True,
    export_normals:       True,
}

function exportGltf(obj, shards, morphs, outDir):
    # mesh.glb: intact mesh (with morph targets) + the collision node.
    selectOnly([obj, findObject(obj.name + "_col")])
    setMorphTargetNames(obj, morphs.names)          # -> extras.targetNames
    bpy.ops.export_scene.gltf(filepath = outDir + "/mesh.glb",
                              use_selection = True, **GLTF_SETTINGS)

    # shards.glb: one node per shard, named shard_000.., matching the manifest.
    selectOnly(shards)
    bpy.ops.export_scene.gltf(filepath = outDir + "/shards.glb",
                              use_selection = True, **GLTF_SETTINGS)

    for f in [mesh.glb, shards.glb]:
        if not exists(f) or size(f) == 0: fail(EXPORT_FAILED, f)
        reimportAndSanityCheck(f)     # parse it back; catches silent exporter failures
```

**R15.** Every export is **re-imported and sanity-checked** in the same run. A glTF exporter that silently drops morph targets (it has happened across Blender versions) would otherwise produce a green tool run and a broken asset — exactly the failure mode this project cannot afford.

---

<!-- D09-S6 -->## 6. Mass Assignment and Material Data

<!-- D09-S6.1 -->### 6.1 Principle

**R16.** Mass is never authored per shard. It is always `volume × density`, computed from the actual shard geometry and the material's density. This is what makes G7 (mass conservation) a *consequence* of the algorithm rather than a constraint to enforce afterwards.

<!-- D09-S6.2 -->### 6.2 Volume and Mass Computation

```pseudo
# Closed-mesh volume by the divergence theorem. The harness implements the same
# formula independently (D14-S5.4); agreement between two implementations is evidence
# that both are right.
function meshVolume(mesh):
    v6 = 0.0
    for (a, b, c) in mesh.triangles():          # triangulated, outward-facing normals
        v6 += a.dot(b.cross(c))
    return abs(v6) / 6.0

function meshCentroid(mesh):
    num = ZERO_VEC; den = 0.0
    for (a, b, c) in mesh.triangles():
        vol = a.dot(b.cross(c)) / 6.0
        num += vol * (a + b + c) * 0.25
        den += vol
    return num / den

function assignMasses(obj, shards, materials, args):
    materialId = args.materialOverride or materialIdOf(obj)       # material slot name
    density    = materials[materialId].densityKgPerM3             # exit 67 if missing

    partVolume = meshVolume(obj)
    if partVolume <= 0: fail(INPUT_GEOMETRY_INVALID, "source mesh has zero volume")
    partMass = partVolume * density
    if args.expectedMass and relDelta(partMass, args.expectedMass) > args.massTolerance:
        fail(MASS_IMPLAUSIBLE,
             "computed {} kg vs expected {} kg — check units, density, watertightness",
             partMass, args.expectedMass)

    total = 0
    for s in shards:
        v = meshVolume(s)
        m = v * density
        if m <= MIN_BODY_MASS_KG:
            # Must not happen — sub-minimum cells were merged in stage 2. If it does,
            # the merge threshold and the mass threshold disagree; that is a tool bug.
            fail(MASS_IMPLAUSIBLE, "shard {} mass {} below minimum after merging", s.name, m)
        s.massKg = m; s.volumeM3 = v; total += m

    # Conservation. Voronoi cells + boolean leave tiny gaps at cell boundaries, so the
    # sum is slightly UNDER the part's volume; the tolerance accounts for that.
    if abs(total - partMass) > args.massTolerance * partMass:
        fail(MASS_IMPLAUSIBLE,
             "shard mass sum {} deviates {}% from part mass {}",
             total, 100*abs(total-partMass)/partMass, partMass)

    # Optional redistribution: scale every shard by partMass/total so the sum matches
    # EXACTLY. Applied only when the deviation is already within tolerance, so it
    # corrects float/boolean noise and never hides a real geometry problem.
    scale = partMass / total
    for s in shards: s.massKg *= scale
    return { partMass, partVolume, density, materialId }
```

**R17.** The final rescale is deliberately applied *after* the tolerance check, not instead of it. Rescaling first would make the conservation check tautologically pass and would silently mask a broken fracture.

<!-- D09-S6.3 -->### 6.3 Material Density Table

**R18.** Densities come from `assets/materials/materials.json` (D08-S4.3) — the same file the game reads. The tool never carries its own copy. If the file is unreachable, the tool fails with exit 67 rather than falling back to built-in numbers.

```pseudo
function loadMaterialTable(path):
    table = readJson(path)                     # exit 65 if unreadable
    validateAgainstSchema(table, "schemas/material_table.schema.json")
    byId = {}
    for m in table.materials:
        assert m.densityKgPerM3 > 0
        byId[m.materialId] = m
    return byId

# Reference densities (authoritative values live in the JSON; this table is documentation):
#   steel            7850 kg/m³
#   steel_hardened   7850
#   aluminium        2700
#   composite        1900
#   rubber           1100
#   glass            2500
#   plastic           950
#   lead            11340
```

**R19.** A mesh whose material name is not in the table is a **hard failure** (exit 67), never a default-to-steel. A wrong density silently produces a wrong mass, which silently produces wrong physics, which is the hardest class of bug to trace back to its cause.

---

<!-- D09-S7 -->## 7. Verification Pipeline

**R20.** Stage 7 runs after everything else and gates exit 0. Every check has a stable `TV-nnn` id and is embedded in the manifest.

```pseudo
function selfVerify(obj, shards, morphs, masses, hulls, manifest, tmpDir):
    checks = []

    # ---- TV-001: All shards have positive mass -------------------------------
    checks += check("TV-001", "All shards have positive mass",
        passed  = all(s.massKg > MIN_BODY_MASS_KG for s in shards),
        measured= "min {} kg, max {} kg".format(minMass, maxMass),
        expected= "> {} kg".format(MIN_BODY_MASS_KG),
        failCode= MASS_IMPLAUSIBLE)

    # ---- TV-002: Shape keys are non-degenerate --------------------------------
    # No NaN, no zero-area faces at full weight, displacement above the floor.
    for m in morphs:
        deltas = morphDeltas(obj, m.name)
        checks += check("TV-002", "Shape key {} is non-degenerate".format(m.name),
            passed  = allFinite(deltas)
                      and m.maxDisplacementM >= MORPH_MIN_DELTA_M
                      and not anyZeroAreaFaceAtWeight(obj, m.name, 1.0, MIN_FACE_AREA_M2),
            measured= "max disp {} m, NaN count {}".format(m.maxDisplacementM, nanCount),
            expected= "finite, max disp >= {} m, no zero-area faces".format(MORPH_MIN_DELTA_M),
            failCode= SHAPEKEY_FAILED)

    # ---- TV-003: Shape key severity is monotonic ------------------------------
    checks += check("TV-003", "Shape key severity increases across levels",
        passed  = isStrictlyIncreasing([m.meanDisplacementM for m in morphs]),
        measured= str([m.meanDisplacementM for m in morphs]),
        expected= "strictly increasing",
        failCode= SHAPEKEY_FAILED)

    # ---- TV-004: Collision shapes are valid -----------------------------------
    for name, hull in hulls:
        checks += check("TV-004", "Hull {} is valid".format(name),
            passed  = hull.vertexCount >= 4
                      and hullVolume(hull) > 0
                      and hull.vertexCount <= budgetFor(name)
                      and not selfIntersects(hull)          # convex hulls cannot, but the
                                                            # SOURCE may have been non-manifold
                      and enclosesSource(hull, sourceOf(name), HULL_ENCLOSE_M),
            measured= "{} verts, volume {} m³".format(hull.vertexCount, hullVolume(hull)),
            expected= "4..{} verts, positive volume, encloses source".format(budgetFor(name)),
            failCode= HULL_FAILED)

    # ---- TV-005: Manifest matches exported mesh count -------------------------
    exported = reimportNodeNames(tmpDir + "/shards.glb")
    checks += check("TV-005", "Manifest shard count matches exported meshes",
        passed  = manifest.shardCount == len(exported)
                  and set(s.name for s in manifest.shards) == set(exported),
        measured= "manifest {}, exported {}".format(manifest.shardCount, len(exported)),
        expected= "equal sets",
        failCode= VERIFICATION_FAILED)

    # ---- TV-006: Morph targets survived export --------------------------------
    exportedMorphs = reimportMorphTargetNames(tmpDir + "/mesh.glb")
    checks += check("TV-006", "Morph targets present in exported mesh",
        passed  = exportedMorphs == manifest.morphTargets,
        measured= str(exportedMorphs),
        expected= str(manifest.morphTargets),
        failCode= EXPORT_FAILED)

    # ---- TV-007: Mass distribution is physically plausible --------------------
    total = sum(s.massKg for s in shards)
    checks += check("TV-007", "Total shard mass conserves part mass",
        passed  = abs(total - manifest.partMassKg) <= args.massTolerance * manifest.partMassKg,
        measured= "{} kg vs {} kg".format(total, manifest.partMassKg),
        expected= "within {}%".format(100*args.massTolerance),
        failCode= MASS_IMPLAUSIBLE)

    checks += check("TV-008", "Shard mass distribution is not pathological",
        # A fracture where one shard holds >70% of the mass, or where the largest is
        # >200x the smallest, is technically valid but visually and physically poor.
        passed  = maxMass <= 0.70 * total and (maxMass / minMass) <= 200,
        measured= "max/total {}, max/min {}".format(maxMass/total, maxMass/minMass),
        expected= "max/total <= 0.70, max/min <= 200",
        severity= WARNING)                                   # advisory, does not fail

    # ---- TV-009: Geometry sanity ----------------------------------------------
    checks += check("TV-009", "No NaN or Inf in any output vertex",
        passed  = all(allFinite(s.vertices) for s in shards) and allFinite(obj.vertices),
        measured= "checked {} shards + intact mesh".format(len(shards)),
        expected= "all finite",
        failCode= VERIFICATION_FAILED)

    checks += check("TV-010", "Extents are plausible (unit check)",
        passed  = MIN_PART_EXTENT_M <= maxExtent(obj) <= MAX_PART_EXTENT_M,
        measured= "{} m".format(maxExtent(obj)),
        expected= "{}..{} m".format(MIN_PART_EXTENT_M, MAX_PART_EXTENT_M),
        failCode= INPUT_GEOMETRY_INVALID)

    # ---- TV-011: Manifest validates against its own schema --------------------
    checks += check("TV-011", "Manifest conforms to schema",
        passed  = validateJsonSchema(manifest, "schemas/fracture_manifest.schema.json"),
        measured= violationsOrNone,
        expected= "no schema violations",
        failCode= VERIFICATION_FAILED)

    return { passed: none(c.failed and c.severity == BLOCKING for c in checks),
             checks: checks,
             warnings: [c for c in checks if c.severity == WARNING and c.failed] }
```

**R21.** The tool's checks and the harness's checks (D14-S4.5) overlap on purpose. The tool checks Blender-side data before export; the harness checks the exported data in Bullet. Where they agree, confidence is high; where they disagree, the disagreement itself is the bug report.

---

<!-- D09-S8 -->## 8. Determinism

**R22.** Given the same input file, seed, arguments, tool version, and Blender version, the tool produces **byte-identical mesh topology** and an equal manifest (excluding `generatedAt`). This is G11.

```pseudo
DETERMINISM MECHANISMS:
  1. --factory-startup: no user prefs, no add-on state, no unit-scale surprises (D09-R3).
  2. All randomness from one explicit PCG32 seeded by --seed. Never Python's `random`
     module without seeding, never Blender's internal RNG, never `id()` or memory
     addresses, never `time`.
  3. Per-purpose sub-seeds: mix(seed, hash(objectName), purposeTag). Adding a morph
     therefore cannot shift shard placement.
  4. Fracture sites generated by us, sorted lexicographically before use (D09-R10).
  5. Shards re-sorted by quantised centroid after generation, then named by index.
     Never rely on Blender collection ordering.
  6. Dict/set iteration never drives output order; every loop that affects output
     iterates a sorted sequence.
  7. Floating-point values in the manifest are rounded to 6 significant decimals so
     that last-bit noise cannot change the file.
  8. topologyHash: SHA-256 over all shard vertices (quantised to 1e-6 m) and face
     indices, in sorted order. This is the fingerprint compared by D14 GOLD-007.

SELF-CHECK (--verify-determinism, run in CI):
function verifyDeterminism(args):
    a = runPipelineInMemory(args)
    resetBlenderState()                       # fresh scene, re-load input
    b = runPipelineInMemory(args)
    if a.topologyHash != b.topologyHash:
        fail(DETERMINISM_VIOLATION,
             "same-process reruns diverge: {} vs {}", a.topologyHash, b.topologyHash)
    for (sa, sb) in zip(a.shards, b.shards):
        if sa.id != sb.id or relDelta(sa.massKg, sb.massKg) > 1e-9:
            fail(DETERMINISM_VIOLATION, "shard {} differs", sa.id)

# R23. Determinism is guaranteed WITHIN one Blender version. A Blender upgrade may
#      legitimately change boolean results. That is why GOLD-007 is advisory when
#      toolVersion/blenderVersion differ (D14-S5.8) and why blenderVersion is recorded
#      in every manifest.
```

---

<!-- D09-S9 -->## 9. Error Reporting

**R24.** On failure, stdout carries exactly one JSON document in this shape. It is designed to be actionable without reading the human log.

```json
{
  "status": "failed",
  "exitCode": 72,
  "exitName": "MASS_IMPLAUSIBLE",
  "toolVersion": "0.1.0",
  "blenderVersion": "4.2.1",
  "input": "art-source/parts/panels/panel_plate_medium_01.blend",
  "object": "panel_plate_medium_01",
  "stage": "mass_assignment",
  "seed": 1337,
  "message": "shard mass sum 143.20 kg deviates 10.5% from part mass 160.00 kg",
  "failures": [
    {
      "checkId": "TV-007",
      "name": "Total shard mass conserves part mass",
      "measured": "143.20 kg",
      "expected": "within 2% of 160.00 kg",
      "delta": "16.80 kg (10.5%)",
      "hint": "source mesh may not be watertight; boolean stage likely lost volume"
    }
  ],
  "warnings": [
    { "checkId": "TV-008", "message": "largest shard holds 62% of total mass" }
  ],
  "diagnostics": {
    "partVolumeM3": 0.020382,
    "shardVolumeSumM3": 0.018241,
    "volumeLossFrac": 0.105,
    "densityKgPerM3": 7850.0,
    "shardCount": 24,
    "meshIsWatertight": false,
    "nonManifoldEdgeCount": 14,
    "looseVertexCount": 0
  },
  "suggestedActions": [
    "Run Blender's 'Select Non Manifold' on the source mesh; 14 non-manifold edges found",
    "Re-export the source with 'Merge by Distance' applied",
    "If the mesh is intentionally open, close it or supply a separate _col object"
  ]
}
```

**R25.** `suggestedActions` is required for every failure category. An agent that cannot act on a failure report will re-run the same command and get the same error; the report must always point at the next thing to try.

**R26.** Warnings never change the exit code, but they always appear in both the success manifest and the failure report.

---

<!-- D09-S10 -->## 10. Acceptance Criteria

- [ ] **AC-D09-1.** The tool runs headlessly under `blender --background --factory-startup` with no user interaction.
- [ ] **AC-D09-2.** stdout contains exactly one JSON document per run, always; nothing else.
- [ ] **AC-D09-3.** Exit 0 occurs only when every BLOCKING verification check passed.
- [ ] **AC-D09-4.** Every exit code in D09-S4.3 is produced by its stated cause, verified by a crafted input per code.
- [ ] **AC-D09-5.** Unknown arguments exit 64.
- [ ] **AC-D09-6.** A failed run leaves the output directory unchanged (atomic publish).
- [ ] **AC-D09-7.** The manifest validates against `schemas/fracture_manifest.schema.json`.
- [ ] **AC-D09-8.** Two runs with the same seed produce identical `topologyHash` and equal manifests except `generatedAt`.
- [ ] **AC-D09-9.** Changing `--seed` changes `topologyHash`.
- [ ] **AC-D09-10.** Shard masses are `volume × density`; Σ shard mass equals part mass exactly after the post-check rescale, and the pre-rescale deviation was within tolerance.
- [ ] **AC-D09-11.** No shard has mass ≤ `MIN_BODY_MASS_KG`; sub-minimum cells were merged, not dropped.
- [ ] **AC-D09-12.** All morph targets are finite, above the displacement floor, and monotonically more severe.
- [ ] **AC-D09-13.** Every hull is valid, within budget, and encloses its source within `HULL_ENCLOSE_M`.
- [ ] **AC-D09-14.** Exports are re-imported and checked in the same run; a dropped morph target fails the run.
- [ ] **AC-D09-15.** An unresolved material fails with exit 67; there is no default density anywhere in the code (grep).
- [ ] **AC-D09-16.** Every failure report includes `suggestedActions`.
- [ ] **AC-D09-17.** The tool never writes outside `--out` and the temp directory, and never modifies the input.
- [ ] **AC-D09-18.** All five canonical fixtures (D14-S7.1) process to exit 0 at their recorded seeds.
- [ ] **AC-D09-19.** `--dry-run` changes nothing and prints the plan.
- [ ] **AC-D09-20.** `--verify-only` re-verifies existing output without regenerating it.
- [ ] **AC-D09-21.** With `--shell-thickness` set, the patches cover the source surface within 1% of its area, every shard is a closed solid, and Σ shard mass equals part mass — the part's volume being the sum of its shards' (R11c), so the equality is exact rather than within a tolerance.

---

<!-- D09-S11 -->## 11. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | Source mesh is not watertight | Exit 66 with `nonManifoldEdgeCount` and the "Select Non Manifold" suggestion. Never fracture an open mesh — volume, and therefore mass, would be meaningless. **Unless `--shell-thickness` is set** (S5.2.1), which is the caller stating that the source is a surface and supplying the thickness its mass is to be computed from; the watertight check is then replaced by an area check. |
| E2 | Source has zero volume (flat plane) | Exit 66, for the same reason and with the same exception: a flat pane is the shell path's ordinary input, and it is checked on area instead. |
| E3 | Source is smaller than `MIN_PART_EXTENT_M` or larger than `MAX_PART_EXTENT_M` | Exit 66 via TV-010, with a unit-error hint — this catches cm/mm authoring. |
| E4 | `--shards` exceeds `MAX_SHARDS_PER_PART` | Clamped to 256 with a warning; not an error. |
| E5 | `--shards 1` | Clamped to 2 with a warning. A single "shard" is not a fracture. |
| E6 | Sites cannot be placed (thin geometry, high shard count) | Exit 68 with the achieved site count and a suggestion to lower `--shards`. |
| E7 | Boolean stage produces a shard with zero volume | Merged if below `--min-shard-volume`; otherwise exit 68. |
| E8 | One shard holds most of the mass | TV-008 warning; still exit 0. The asset is usable but poor; a human should look. |
| E9 | `--amplitude` too large, morphs self-intersect | TV-002 catches zero-area faces; exit 69 with a suggestion to lower amplitude. **`syndicate_deform`**, not this tool. |
| E10 | A mesh reaches the fracture tool already carrying `dmg_*` shape keys | Exit 77. It is evidence that something authored the DEFORM transform onto a part that fractures, and no class in D15-S5.7 receives both. The tool used to *delete* the keys and re-author its own, which is how the two transforms stayed tangled (DISC-068). |
| E11 | Mesh has multiple materials | Exit 67 unless `--material-override` is given. Per-shard mixed density is not supported in v1; recording this limitation is preferable to guessing. |
| E12 | Material not in the table | Exit 67. No default density (D09-R19). |
| E13 | Blender executable not found | Exit 70 naming the paths tried (D02-R12). |
| E14 | A cell's half-space set clips to an empty polytope for every convex piece of the source | The site contributed no shard; it is dropped and the run continues, because a site outside the mesh is a legitimate rejection-sampling outcome rather than a failure. Exit 71 only if fewer than two shards survive. |
| E15 | glTF exporter silently drops morph targets | TV-006 catches it after re-import; exit 74. |
| E16 | Disk full mid-write | Exit 75; temp directory cleaned; output directory untouched. |
| E17 | Determinism self-check diverges | Exit 76. This is a tool bug; the asset must not ship. |
| E18 | Blender version differs from the manifest's on re-verify | Warning; `topologyHash` comparison becomes advisory (D09-R23). |
| E19 | Input contains multiple objects and no `--object` | All are processed; the manifest is written per object into `--out/<objectName>/`. |
| E20 | Object name is not a valid asset id | Exit 65 with the id grammar (D00-R19). |
| E21 | Interrupted mid-run (SIGINT) | Temp directory removed on exit; output untouched. |
| E22 | Extremely high vertex count source (500k tris) | Processes, but warns about time; hull simplification is the slow stage. A budget warning appears above `SLOW_MESH_TRIS = 200000`. |

---

<!-- D09-S12 -->## 12. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D09-1 | Process `test_cube_1m.glb`, seed 1001, 24 shards | Exit 0; part mass 7850 kg ± 2%; 24 shards; Σ mass equals part mass |
| T-D09-2 | Run T-D09-1 twice | Identical `topologyHash`; manifests equal except `generatedAt` |
| T-D09-3 | Run T-D09-1 with seed 1002 | Different `topologyHash`; mass conservation still holds |
| T-D09-4 | Process `test_plate_2x1x0.1.glb` | Exit 0; no sliver hull exceeds `ASPECT_RATIO_MAX`; all hulls ≤ 32 verts |
| T-D09-5 | Process `test_cylinder_r0.5_h1.glb` | Exit 0; hulls enclose the curved surface within `HULL_ENCLOSE_M` |
| T-D09-6 | Process `test_complex_hollow.glb` | Exit 0; mass ≈ 2500 kg (cavity accounted for), not solid-box mass |
| T-D09-7 | Process `test_vehicle_chassis.glb` | Exit 0; slot empties round-trip into `mesh.glb` extras |
| T-D09-8 | Delete a face to make the source open | Exit 66 with `meshIsWatertight: false` and non-manifold count |
| T-D09-9 | Scale the source by 100 | Exit 66 via TV-010 with the unit hint |
| T-D09-10 | Assign an unknown material | Exit 67 naming the material |
| T-D09-11 | `--shards 500` | Clamped to 64 with a warning; exit 0 |
| T-D09-12 | `--shards 1` | Clamped to 2 with a warning |
| T-D09-13 | `syndicate_deform --amplitude 2.0` on a 0.1 m plate | Exit 69 (zero-area faces) with the lower-amplitude suggestion |
| T-D09-14 | `syndicate_fracture --destruction-class SHEET_METAL` | Exit 77, naming the transform the class does receive |
| T-D09-15 | `syndicate_deform --destruction-class GLASS` | Exit 77 — glass does not dent |
| T-D09-16 | `--verify-only` over a manifest whose shard masses were edited | Exit 72, outputs untouched |
| T-D09-15 | Corrupt the exporter so morphs are dropped | TV-006 fails; exit 74 |
| T-D09-16 | Make `--out` read-only | Exit 75; input and out unchanged |
| T-D09-17 | `--dry-run` | Exit 0; plan JSON printed; nothing written |
| T-D09-18 | `--verify-only` on a valid output | Exit 0; no files rewritten (mtimes unchanged) |
| T-D09-19 | `--verify-only` after corrupting a shard mass in the manifest | Exit 72 |
| T-D09-20 | Unknown flag `--shardz 4` | Exit 64 naming the flag |
| T-D09-21 | Multiple materials on one mesh, no override | Exit 67 with the limitation explained |
| T-D09-22 | Kill the process mid-fracture | Temp dir removed; output dir unchanged |
| T-D09-23 | Parse stdout of 100 runs (mixed success/failure) | Every run yields exactly one parseable JSON document |
| T-D09-24 | Feed the output of T-D09-1 to the harness | D14 asset-level checks all pass |
| T-D09-25 | `--verify-determinism` in CI | Exit 0; identical hashes across in-process reruns |
| T-D09-26 | Process a 5 mm curved pane with `--shell-thickness 0.005`, 24 shards | Exit 0; 24 closed shards; cut covers the source area within 1%; Σ shard mass equals part mass |
| T-D09-27 | The same pane with no `--shell-thickness` | Exit 66 — an open surface is not a solid, and the tool does not guess a thickness |

---

<!-- D09-S13 -->## 13. Cross-References

| Topic | Section |
|---|---|
| Units, axes, single conversion point | `docs/00_master_index.md#D00-S4.3`, `#D00-S4.4` |
| G7 mass conservation, G11 determinism, G12 manifest contract | `docs/00_master_index.md#D00-S5.2` |
| `MAX_SHARDS_PER_PART`, `MIN_BODY_MASS_KG` | `docs/00_master_index.md#D00-S6.4` |
| Gradle wiring and Blender discovery | `docs/02_technical_architecture.md#D02-S4.6` |
| Data flow art → gameplay | `docs/02_technical_architecture.md#D02-S5.2` |
| Bullet hull constraints | `docs/06_physics_simulation.md#D06-S4.3` |
| Hull construction at runtime | `docs/06_physics_simulation.md#D06-S5.2` |
| How shards become debris bodies | `docs/07_damage_destruction_model.md#D07-S5.6` |
| Morph weights at runtime | `docs/07_damage_destruction_model.md#D07-S5.5` |
| Source `.blend` conventions | `docs/08_asset_pipeline.md#D08-S4.1` |
| Material table (shared file) | `docs/08_asset_pipeline.md#D08-S4.3` |
| glTF export format decision | `docs/08_asset_pipeline.md#D08-S4.5` |
| Part/manifest cross-checks | `docs/08_asset_pipeline.md#D08-S5.5` |
| Schema locations | `docs/08_asset_pipeline.md#D08-S6.1` |
| CI stage running the tool over fixtures | `docs/12_testing_validation_ci.md#D12-S5.4` |
| Independent verification in Bullet | `docs/14_test_environment.md#D14-S5.2`, `#D14-S5.3` |
| Golden manifest comparison | `docs/14_test_environment.md#D14-S5.8` |
| Canonical fixtures and seeds | `docs/14_test_environment.md#D14-S7.1` |
| Tolerances referenced by the checks | `docs/14_test_environment.md#D14-S6.4` |
