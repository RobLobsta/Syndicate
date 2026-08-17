# `blender-tool` — the Syndicate authoring suite

The Blender side of Syndicate: four headless Python packages that turn downloaded art into game
parts, and authored geometry into destruction data the engine can spend at runtime.

This file is the suite's **entry document** — the thing to read before touching any package in it. It
is not a blueprint. `docs/09_blender_destruction_tool.md`, `docs/15_vehicle_preparation_pipeline.md`
and `docs/17_weapon_system.md` are the contracts, and where this file and `docs/` disagree, `docs/`
wins (D00-S5.1). What lives here is the shape of the suite as a whole, which no single numbered
document owns because each of them owns one tool.

**Status.** Everything in §2 ships and runs. §6 is a list of real defects found by review on
2026-08-17, none of them yet fixed. §7 is the target shape. Read §6 before trusting §2.

---

## 1. The one idea

A part in Syndicate fails in exactly one authored way, decided by **what the part is** rather than
what it is made of (`DestructionClass`, D15-S5.7). A windscreen shatters. A door dents. A caliper
does neither and simply leaves.

Every tool in this suite exists to author **one transform**, and the invariant that makes the whole
thing legible is:

> **A part receives the transforms its destruction class names, and no others.**
>
> Glass gets shards and never morph targets. Sheet metal gets morph targets and never shards. A tool
> that produces shards produces only shards, labels its output as shards, and the runtime breaks that
> part the way it breaks glass — because that is the only thing shards mean.

That sentence is the design. §6 records where the code does not yet hold it.

---

## 2. The transform model

A **transform** is not a Blender operation. It is a triple: an authoring-time operation, a runtime
representation, and a runtime trigger. Naming all three together is what stops "we could also do
melting" from being a Blender question when it is really an engine question.

| Transform | Authoring output | Runtime representation | Trigger | Classes |
|---|---|---|---|---|
| **DEFORM** | 4 morph targets `dmg_25…dmg_100` inside `mesh.glb` | shape-key weights, two non-zero at a time | continuous, health fraction (D07-S5.5) | `SHEET_METAL`, `STRUCTURAL` |
| **FRAGMENT** | `shards.glb` + a manifest of N shards with masses and placements | N debris rigid bodies, one per shard | discrete, on `DESTROYED` (D07-S5.6) | `GLASS` |
| **DETACH** | nothing — it is the slot graph | the part's body leaves the vehicle compound | `breakImpulseN`, or destruction (D05-S5.5) | every class |
| **ARTICULATE** | an `articulation` block on `part.json` | a node pose composed after the render matrix | `OPEN` / `FIRE` / `AIM` / `CONTINUOUS` (DEC-083) | any part with a hinge or a mechanism |

Three properties fall out of the table and are worth stating because they are load-bearing:

- **DEFORM is continuous and reversible; FRAGMENT is discrete and terminal.** They are not two points
  on one scale. A part cannot be 40% fragmented.
- **DEFORM is cosmetic; FRAGMENT is authoritative.** Morph weights are written by the client and read
  by nobody (G6, D07-R18). Shards are rigid bodies with mass, and their masses must sum to the part's
  (G7). That is why a wrong morph is ugly and a wrong shard set is a physics bug.
- **Collision geometry never deforms** (D06-NG5). A dented door collides as an undented door. This is
  the single constraint that decides which future transforms are cheap and which are not — see §8.

DETACH is in the table for completeness and needs no tool: it is a consequence of the slot graph and
`breakImpulseN`, authored as numbers, and nothing in Blender produces it.

---

## 3. The packages, as they exist today

| Package | Runs as | In | Out | Contract |
|---|---|---|---|---|
| `syndicate_prepare` | `python3 -m syndicate_prepare` | one whole-vehicle model | ~30 parts under `assets/vehicles/<id>/parts/`, `assembly.json`, `import.json` | D15 |
| `syndicate_weapon` | `python3 -m syndicate_weapon` | one whole-weapon model | a weapon's sub-parts + `<id>.weapon.json` | D17 |
| `syndicate_fracture` | `python3 -m syndicate_fracture` | one clean part mesh | `mesh.glb`, `shards.glb`, `fracture_manifest.json` | D09 |
| `syndicate_dissect` | *library only, no CLI* | — | — | — |

The first two are **orchestrators**: they classify a downloaded model into labelled parts and then
decide, per part, which transforms to author. The third is a **transform tool**: it is handed one
part and one instruction. The fourth is a **shared library** — model loading, `import.json`
correction, joins, origins, collision hulls, glTF writing — that both orchestrators call.

Both hosts are supported everywhere: `blender --background --python -m <pkg>` and `python3 -m <pkg>`
against the `bpy` PyPI module. `tools/install-blender.sh` fetches headless Blender 4.2 LTS in about
ninety seconds; the sandbox not shipping one is not a constraint (DISC-064).

---

## 4. Naming, and what is wrong with it

The current names describe **implementation history** rather than the transform model. Four
inconsistencies, in the order they mislead:

1. **`syndicate_fracture` does two transforms.** It fractures *and* generates damage morphs, in one
   pass, in every run (§6.1). Its name promises one of them. So does its output file,
   `fracture_manifest.json`, which carries `morphTargets` and `morphStats` — a fracture manifest
   describing deformation.
2. **The four names are four different parts of speech.** `prepare` is a verb with no object,
   `weapon` is a noun, `fracture` is both, `dissect` is a verb describing something the package no
   longer does. Nothing in the set tells you which are orchestrators and which are transforms.
3. **`syndicate_dissect` is a legacy name its own docstring apologises for.** Its original job — one
   car in, five parts out — was retired. What survives is model loading and mesh export, used by both
   orchestrators on every run. It is the suite's shared library wearing a tool's name, and it is the
   only package with no CLI.
4. **Content policy is baked into a function name.** `syndicate_prepare.exporter.fracture_glass()` is
   the only caller of the fracture tool. It is correct today because glass is the only class that
   fractures — and it is the line that has to be renamed the day D15-S5.7 gives shards to anything
   else, which is a change §7 expects.

The convention §7 proposes: **transform tools are named for their transform, orchestrators for their
subject, libraries for their contents.**

---

## 5. What the engine actually does with each output

Worth having in one place, because the answer decides how much the tool has to enforce.

| Output | Read by | Gate |
|---|---|---|
| morph targets in `mesh.glb` | `DamageVisualSystem` (slot 23) | **the mesh has shape keys** |
| `fracture_manifest.json` + `shards.glb` | `AssetLoader` → `FractureSystem` (slot 13) | **the part declares a manifest** |
| `part.json` `destructionClass` | `AudioSystem` (slot 25), to pick a break sound | — |

Read the third row against the first two. **`DestructionClass` does not gate anything.** The runtime
never asks whether a part is allowed to dent or allowed to shatter; it asks whether the data is
there. `DestructionClass.hasDamageShapeKeys()` exists, is correct, and is called by exactly one unit
test and nothing else.

That is a defensible design — the asset is the authority, the engine spends what it is given — but it
has a consequence that is not optional: **the tools are the only enforcement point in the entire
project.** If a tool writes shards onto a steel door, that door shatters in game, and no loader, no
system and no asset-gate rule will say a word about it.

---

## 6. Findings

Six defects, most-severe first. All were confirmed by reading the code on 2026-08-17; none are fixed.

### 6.1 `syndicate_fracture` performs both transforms unconditionally

`pipeline._process_one` runs stage 2 (Voronoi fracture) and stage 3 (damage morphs) one after the
other for every object, every run. The CLI defaults are `--shards 24` **and** `--damage-morphs 4`, so
the documented invocation of the D09 tool produces a part that both dents and shatters — which no
destruction class in D15-S5.7 permits, and which the runtime will faithfully perform (§5).

It is worse than symmetric. `--damage-morphs 0` disables deformation, but there is **no way to
disable fracture**: `--shards` is clamped to `[2, 256]`, so `--shards 0` silently becomes two shards.
The tool cannot be asked to dent a panel without also breaking it into two pieces.

### 6.2 The fixture gate ships the violation

`:blender-tool:processFixtures` runs all five fixtures — every one of them steel or aluminium — with
`--shards <n> --damage-morphs 4`. So the gate that proves the tool works proves it doing the one
thing §1 forbids, and `:test-environment:verifyFixtures` checks the result and passes it.

### 6.3 The invariant is held by one caller's discipline, not by the tool

`syndicate_prepare` gets this **right**. Its `destruction.TREATMENTS` table is a faithful transcript
of D15-S5.7 — `GLASS` is `morphs=False, shards=24`, `SHEET_METAL` is `morphs=True, shards=0` — and
the two halves are applied in two different places: `exporter.export_part` subdivides and morphs,
`exporter.fracture_glass` calls the fracture tool with `damage_morphs=0`.

So the shipped content is correct. But the correctness lives in an orchestrator, and the tool it
orchestrates will happily do the wrong thing for anyone who invokes it directly — which is exactly
how D09 documents invoking it, and how the fixture gate does invoke it.

### 6.4 The manifest does not say what transform produced it

`fracture_manifest.json` records the tool version, the seed, the source hash, the topology hash, the
material and 24 shards — and nowhere states that this is a **fragmentation** manifest for a **GLASS**
part. There is no `destructionClass` and no transform discriminator in it. A consumer cannot tell a
manifest that should exist from one that should not, which is why no gate catches 6.1.

### 6.5 Two contract flags are accepted and silently ignored

`--verify-only` and `--keep-blend` are in D09-S4.2's table, are parsed by `cli.parse`, are validated
(`--no-export` and `--verify-only` are correctly rejected as contradictory) — and are then read by
nothing. `verify_only` appears in `cli.py` three times and nowhere else in the package; `keep_blend`
appears in `cli.py` and nowhere at all.

`--verify-only` is the dangerous one. Its documented meaning is "verify existing outputs, produce no
new data". Its actual behaviour is a full destructive run that overwrites them.

This directly contradicts the module's own reasoning: `cli.py`'s docstring explains that unknown
arguments must be fatal because "silently ignoring `--shard=24` would produce a 24-shard default that
looks like success and ships the wrong asset". A *known* flag that is accepted and ignored fails the
same way, with more confidence behind it.

### 6.6 Three exit-code schemes that do not agree

`syndicate_fracture` uses 64–76 (D09-S4.3). `syndicate_weapon` extends it into a reserved 80–89 range
(D17-R19) — good — but re-uses 65 for `BAD_MODEL` where D09 has `INPUT_INVALID`, and 66 for
`INPUT_MISSING` where D09 has `INPUT_GEOMETRY_INVALID`. `syndicate_prepare` uses 65 for
`UNDER_LABELLED`. An agent branching on the code by integer division, which D09-R5 explicitly invites
it to do, gets three different answers to "what is a 65".

---

## 7. The target shape

What the suite should become. Ordered by value, and none of it is started.

### 7.1 Split the transform tools

| New package | Transform | Reads | Writes |
|---|---|---|---|
| `syndicate_shatter` | FRAGMENT | one watertight solid or a surface + `--shell-thickness` | `shards.glb`, `shatter_manifest.json` |
| `syndicate_deform` | DEFORM | one part mesh | morph targets inside `mesh.glb`, `deform_manifest.json` |

Each does one thing, and neither has a flag that would let it do the other. Most of the split is
moving files: `fracture.py`, `shell.py`, `sites.py`, `decompose.py`, `hulls.py` and `mass.py` go one
way; `morphs.py` goes the other; `blender.py`, `geometry.py`, `rng.py`, `errors.py`, `exporter.py`
and `selfverify.py` are shared and belong in the library (§7.3).

The two tools are then genuinely independent — a part can be handed to both, neither, or one, and the
decision is the caller's and is visible in the invocation. Which is the point: today that decision is
invisible because it was never asked.

### 7.2 Make every manifest name its transform and its class

Both manifests carry, as required fields:

```json
{ "transform": "FRAGMENT", "destructionClass": "GLASS", "partTypeId": "…" }
```

That single field is what turns §6.1 from a convention into something checkable. It gives:

- **the tool** a reason to refuse: `syndicate_shatter --destruction-class SHEET_METAL` exits non-zero
  citing D15-S5.7 rather than producing a door that shatters;
- **the asset gate** a rule to enforce (a new `A510`: the transform a part's manifests declare must
  match its `destructionClass`), so a hand-run tool cannot slip past `asset-pipeline`;
- **the runtime** the option, later, of refusing to load a manifest whose class disagrees with the
  part's — closing the last hole in §5.

### 7.3 Rename for the model

| Now | Then | Why |
|---|---|---|
| `syndicate_fracture` | `syndicate_shatter` + `syndicate_deform` | one transform each (§7.1) |
| `syndicate_prepare` | `syndicate_vehicle` | it is the vehicle orchestrator, and `syndicate_weapon` is already named that way |
| `syndicate_dissect` | `syndicate_mesh` | it is the shared model/mesh library and has been for two sessions |
| `exporter.fracture_glass()` | `exporter.author_fragmentation()` | the class is the table's business, not the function name's |
| `fracture_manifest.json` | `shatter_manifest.json` | matches the tool; the loader reads a path out of `part.json`, so the rename is a content migration and not a code change |

`syndicate_mesh` gains a `--version`-only CLI so all four packages answer the same way.

### 7.4 Fix the flags and the codes

Implement `--verify-only` and `--keep-blend`, or delete them from both the tool and D09-S4.2 in the
same commit. Accepting a flag and ignoring it is the one outcome that is not allowed.

Move the three schemes onto one table: 64–79 shared (usage, input, geometry, material, Blender,
export, write), 80–89 weapon, 90–99 vehicle. One number, one meaning, across the suite.

### 7.5 Fix the fixture gate

Fixtures are steel and aluminium, so they are `SHEET_METAL` or `STRUCTURAL` and must not fracture.
Either add a glass fixture and split the gate — deform the metal ones, shatter the glass one — or
declare the fixtures class-free geometry probes and say so in D14-S7.1. The first is more work and is
the one that would have caught §6.1.

---

## 8. Transforms not yet built

Assessed against the architecture rather than against Blender, because in every case the engine is
the constraint. Ordered by cost.

### Cheap: they reuse a representation the engine already has

- **Impact-directed denting.** DEFORM already places dent centres from a seeded RNG; D09-S4.2 already
  has `--impact-point` and `--shard-mode impact_biased` for the fragment side. Authoring a small set
  of directional morph variants — dented-from-front, from-left — and picking between them by the
  hit's incident direction is a content change and a weight-selection change, no new representation.
  This is the highest value-per-unit-work item on the list: it turns damage from "this part is worn"
  into "this part was hit *there*".
- **Charring and heat.** A burn is a material response, not a geometric one, and D07 already has a
  burn timer. Authored as an emissive/albedo ramp driven by the same health fraction that drives
  morph weights. No new tool — it belongs to the style table (DEC-076) rather than here.
- **Progressive fragmentation.** The manifest already records `shards[].neighbors`, the Voronoi
  adjacency, and nothing reads it. Shattering a windscreen in two stages — a cracked region, then the
  rest — is a runtime scheduling change over data that already ships.

### Expensive: they need a representation the engine does not have

- **Real-time / runtime fracture.** Cutting geometry at the point of impact during a match. This is
  not a Blender feature at all — it is a decision to move fracture from authoring time to runtime,
  and it collides with two invariants at once: G4/G11 determinism (every peer must cut identically,
  from the same seed, in the same order) and the fixed tick budget (a boolean decomposition of a car
  panel is seconds, not milliseconds). The pre-authored path exists precisely to avoid both. The
  honest version of this feature is **more authored variants selected at runtime**, which is
  impact-directed fragmentation and belongs in the cheap list.
- **Melting and plastic flow.** A melt is a topology change over time, and the blocker is
  D06-NG5 — collision geometry never deforms. A puddle that still collides as an engine block is
  worse than no melt at all. Doing it properly means per-state collision hulls and a hull-swap on
  state change, which is a physics change (a new `ShapeCacheKey` variant, a body rebuild mid-match)
  before it is an authoring one. Worth wanting for a flamethrower; not worth starting until a
  flamethrower exists.
- **Piercing and holes.** A visible hole through a panel needs either geometry the tool cut in
  advance at authored locations, or a decal-with-alpha that fakes it. The second is cheap and
  cosmetic and is the one to build first; the first is FRAGMENT with a different cell pattern and
  gets nothing new from a new tool.
- **Bending as a constraint rather than a shape key.** A hanging bumper that swings is
  ARTICULATE, not DEFORM, and DEC-083's articulation block already has five motions. Adding a
  `SAG` motion driven by health is a smaller change than it sounds and does not need Blender.

The pattern in that list is worth naming: **every cheap transform reuses DEFORM's or FRAGMENT's
runtime representation, and every expensive one demands a new one.** A proposal for a new transform
should be assessed on that question first, and on how it looks second.

---

## 9. Running it

```bash
bash blender-tool/tools/install-blender.sh          # once per session; ~90 s

# A whole vehicle: model in, ~30 labelled parts out. Without --assets it classifies and
# reports but exports nothing, which is the form to run when a threshold changed.
python3 -m syndicate_prepare --model art-source/vehicles/<name> --vehicle <name> --assets assets

# A whole weapon: model in, sub-parts and a weapon manifest out.
python3 -m syndicate_weapon --model art-source/weapons/<file>.glb --out assets/parts/<id>

# One part's destruction data. NOTE §6.1: this authors morphs as well unless you pass
# --damage-morphs 0, and it cannot be asked not to fracture.
python3 -m syndicate_fracture --input mesh.glb --out . --shards 24 --damage-morphs 0 \
    --material-override glass --shell-thickness 0.006

python3 -m syndicate_fracture --dry-run --input x.glb --out y   # plan only, changes nothing
```

Every tool writes **exactly one JSON document to stdout** and nothing else, on success and on
failure alike (D09-R2). Diagnostics go to stderr. Blender writes to the real stdout at the C level
whatever Python does about it (DISC-002), so fd 1 is redirected for the duration and the document
goes to a private duplicate. An agent reads the exit code first and the document only when it needs
detail.

Two things about the `blender` executable host that cost a session each to find (DISC-064, DISC-065):
its bundled Python ignores `PYTHONPATH`, so the invocation needs a `sys.path` insert inside
`--python-expr`; and it exits 0 on an uncaught exception, so it needs `--python-exit-code 1`. Both
are handled in `build.gradle.kts`; anything invoking the tools outside Gradle has to handle them too.

### Checks

```bash
./gradlew :blender-tool:unitTest          # 300 pure-Python tests, no Blender needed
./gradlew :blender-tool:processFixtures   # runs the tool over fixtures/meshes (needs Blender)
./gradlew :test-environment:verifyFixtures # re-checks that output inside Bullet
python3 -m pytest blender-tool/tests/unit -q
```

The unit suite deliberately runs without Blender: geometry, RNG, CLI parsing, classification and the
treatment tables are all pure functions, and keeping them that way is what makes the suite testable
in CI at all.
