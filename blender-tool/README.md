# `blender-tool` — the Syndicate authoring suite

The Blender side of Syndicate: four headless Python packages that turn downloaded art into game
parts, and authored geometry into destruction data the engine can spend at runtime.

This file is the suite's **entry document** — the thing to read before touching any package in it. It
is not a blueprint. `docs/09_blender_destruction_tool.md`, `docs/15_vehicle_preparation_pipeline.md`
and `docs/17_weapon_system.md` are the contracts, and where this file and `docs/` disagree, `docs/`
wins (D00-S5.1). What lives here is the shape of the suite as a whole, which no single numbered
document owns because each of them owns one tool.

**Status.** Everything here ships and runs. §6 lists the six defects a review found on 2026-08-17
and what was done about each; all six are fixed. §7 records what was deliberately *not* done and
why.

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

That sentence is the design, and §5 is why the tools are the only place it can be enforced.

---

## 2. The transform model

A **transform** is not a Blender operation. It is a triple: an authoring-time operation, a runtime
representation, and a runtime trigger. Naming all three together is what stops "we could also do
melting" from being a Blender question when it is really an engine question.

| Transform | Authoring output | Runtime representation | Trigger | Classes |
|---|---|---|---|---|
| **DEFORM** | 4 morph targets `dmg_25…dmg_100` inside `mesh.glb`, plus `deform_manifest.json` | shape-key weights, two non-zero at a time | continuous, health fraction (D07-S5.5) | `SHEET_METAL`, `STRUCTURAL` |
| **FRACTURE** | `shards.glb` + `fracture_manifest.json`, N shards with masses and placements | N debris rigid bodies, one per shard | discrete, on `DESTROYED` (D07-S5.6) | `GLASS` |
| **DETACH** | nothing — it is the slot graph | the part's body leaves the vehicle compound | `breakImpulseN`, or destruction (D05-S5.5) | every class |
| **ARTICULATE** | an `articulation` block on `part.json` | a node pose composed after the render matrix | `OPEN` / `FIRE` / `AIM` / `CONTINUOUS` (DEC-083) | any part with a hinge or a mechanism |

Three properties fall out of the table and are worth stating because they are load-bearing:

- **DEFORM is continuous and reversible; FRACTURE is discrete and terminal.** They are not two points
  on one scale. A part cannot be 40% fragmented.
- **DEFORM is cosmetic; FRACTURE is authoritative.** Morph weights are written by the client and read
  by nobody (G6, D07-R18). Shards are rigid bodies with mass, and their masses must sum to the part's
  (G7). That is why a wrong morph is ugly and a wrong shard set is a physics bug.
- **Collision geometry never deforms** (D06-NG5). A dented door collides as an undented door. This is
  the single constraint that decides which future transforms are cheap and which are not — see §8.

DETACH is in the table for completeness and needs no tool: it is a consequence of the slot graph and
`breakImpulseN`, authored as numbers, and nothing in Blender produces it.

---

## 3. The packages

| Package | Runs as | In | Out | Contract |
|---|---|---|---|---|
| `syndicate_policy` | *library, no CLI* | — | the class/transform table and the exit-code table | D15-S5.7, D09-S4.3 |
| `syndicate_fracture` | `python3 -m syndicate_fracture` | one clean part mesh + its class | `mesh.glb`, `shards.glb`, `fracture_manifest.json` | D09 |
| `syndicate_deform` | `python3 -m syndicate_deform` | one part mesh + its class | `mesh.glb` with morph targets, `deform_manifest.json` | D09-S5.3 |
| `syndicate_prepare` | `python3 -m syndicate_prepare` | one whole-vehicle model | ~30 parts under `assets/vehicles/<id>/parts/`, `assembly.json`, `import.json` | D15 |
| `syndicate_weapon` | `python3 -m syndicate_weapon` | one whole-weapon model | a weapon's sub-parts + `<id>.weapon.json` | D17 |
| `syndicate_dissect` | *library, no CLI* | — | model loading, `import.json`, joins, origins, hulls, glTF | — |

Three kinds of thing, and the kind is what tells you what to expect of it:

- **Transform tools** (`fracture`, `deform`) take one part and author one transform. Each refuses
  the other's — asking the fracture tool for damage morphs is exit 64 naming the deform tool, and
  handing either a class D15-S5.7 does not give its transform to is exit 77.
- **Orchestrators** (`prepare`, `weapon`) classify a downloaded model into labelled parts and then
  decide, per part, which transform tool to call. They decide; they no longer *are* the rule.
- **Libraries** (`policy`, `dissect`) are imported and never run. `syndicate_policy` is the one
  every other package depends on, and it imports nothing — no `bpy`, no siblings — so the policy can
  be consulted before Blender is started and is unit-testable without a host.

Both hosts are supported everywhere: `blender --background --python -m <pkg>` and `python3 -m <pkg>`
against the `bpy` PyPI module. `tools/install-blender.sh` fetches headless Blender 4.2 LTS in about
ninety seconds; the sandbox not shipping one is not a constraint (DISC-064).

---

## 4. Naming

**Transform tools are named for their transform, orchestrators for their subject, libraries for
their contents.** The transform names are not new words: D00-S6 already defines *fracture* as "the
one-time replacement of a part's single rigid body with its pre-authored shards" and *deformation*
as "continuous visual mesh change driven by shape keys", and its note on each is "not the other
one". The glossary is the single authority for domain terms, and inventing a synonym for one is the
loose usage that outlives a session.

That is a correction to this file's own earlier recommendation, which proposed renaming
`syndicate_fracture` to `syndicate_shatter`. It was wrong: the package was always named correctly,
and the problem was never the name but that the tool did a second thing the name did not cover.
Fixing the tool fixed the name. `fracture_manifest.json` is right for the same reason, and
`deform_manifest.json` is its counterpart.

One rename did happen, and it is the one that was actually misleading:
`syndicate_prepare.exporter.fracture_glass()` is now `author_fracture()`. Glass being the only class
with shards is a *content* decision D15-S5.7 owns and can change; a function name is the wrong place
to record it.

Two names are still legacy and are left alone deliberately. `syndicate_dissect` describes a job it
no longer does — one car in, five parts out, retired — and is now the shared model/mesh library; and
`syndicate_prepare` is a verb with no object where `syndicate_weapon` is a noun. Renaming either
buys clarity and nothing else, and both are load-bearing in the Gradle wiring, the docs and every
memory entry that cites them. §7 says why that trade came out the way it did.

---

## 5. What the engine does with each output, and why the tools must enforce

| Output | Read by | Gate |
|---|---|---|
| morph targets in `mesh.glb` | `DamageVisualSystem` (slot 23) | **the mesh has shape keys** |
| `fracture_manifest.json` + `shards.glb` | `AssetLoader` → `FractureSystem` (slot 13) | **the part declares a manifest** |
| `part.json` `destructionClass` | `AudioSystem` (slot 25), to pick a break sound | — |

Read the third row against the first two. **`DestructionClass` gates nothing at runtime.** The
engine never asks whether a part is allowed to dent or allowed to shatter; it asks whether the data
is there. `DestructionClass.hasDamageShapeKeys()` exists, is correct, and is called by exactly one
unit test and nothing else.

That is a defensible design — the asset is the authority, the engine spends what it is given — but
it has a consequence that is not optional: **authoring time is where the rule has to live.** There
are now two independent places it does, in the spirit of DEC-041:

1. **The tools refuse.** Each takes `--destruction-class`, consults `syndicate_policy`, and exits 77
   rather than authoring a transform the class does not receive. The fracture tool also refuses a
   mesh that *arrives* carrying damage morphs, rather than deleting them as it used to.
2. **The asset gate refuses.** `A510` pairs a manifest's declared `transform` against the part's
   `destructionClass`, and rejects a manifest that declares neither. That is what catches a manifest
   that arrived by hand, in a copied directory, or from a tool version predating the split — none of
   which ever passed a tool's check.

---

## 6. What the review found, and what was done

Six defects, found on 2026-08-17 (DISC-068), most-severe first. All six are fixed.

### 6.1 `syndicate_fracture` performed both transforms unconditionally — **fixed**

`pipeline._process_one` ran stage 2 (Voronoi fracture) and stage 3 (damage morphs) one after the
other for every object, every run, with CLI defaults of `--shards 24` **and** `--damage-morphs 4`.
The documented D09 invocation produced a part that both dented and shattered, which no destruction
class permits and which the runtime would faithfully perform. It was not even symmetric:
`--damage-morphs 0` disabled deformation, but `--shards` was clamped to `[2, 256]`, so the tool
could not be asked *not* to fracture.

The deform stage is now `syndicate_deform`, its own package with its own CLI, manifest and
self-verification. `--damage-morphs` and `--morph-amplitude` are still parsed by the fracture tool
and are exit 64 naming the other tool — dropping them silently would leave every old invocation
looking like it still worked while quietly authoring one transform instead of two, which is the same
failure in the other direction.

### 6.2 The fixture gate shipped the violation — **fixed**

`processFixtures` ran all five fixtures with `--damage-morphs 4`, so the gate that proves the tool
works proved it doing the forbidden thing. It now passes `--destruction-class GLASS` and no morph
flags, and a new `processDeformFixtures` exercises the deform tool over two of the same meshes as
`SHEET_METAL`, into a **separate** output directory — because a part carrying both manifests is the
exact mixture D15-S5.7 forbids, and a gate that produced one would assert the opposite of what it
means to.

The harness's `ASSET-007` asked for four morph targets on every fractured part. It now asserts there
are none, which is a sharper check than the one it replaced: it fails on exactly the mixture.

### 6.3 The invariant lived in one caller's discipline — **fixed**

`syndicate_prepare` got this right, by remembering to pass `damage_morphs=0`. Correctness lived in
an orchestrator, and the tool it orchestrated would happily do the wrong thing for anyone invoking
it directly — which is how D09 documents invoking it, and how the fixture gate did.

The table moved to `syndicate_policy`, where the tools can consult it themselves, and
`syndicate_prepare.destruction` keeps only the part that is genuinely a preparation run's: mapping a
D15-S4.1 *label* to a class. The orchestrator now passes the class down instead of passing a zero.

### 6.4 The manifest did not say what transform produced it — **fixed**

Both manifests now carry `transform` and `destructionClass` as required fields. That single pair is
what turns the invariant from a convention into something checkable, and it is what `A510` checks.
The eight shipped glass manifests were regenerated to declare them.

### 6.5 Two contract flags were accepted and ignored — **fixed**

`--verify-only` promised to produce no new data and performed a destructive overwrite; `--keep-blend`
did nothing at all. Both are implemented. `--verify-only` re-reads the manifest in `--out`, checks
its shape, its mass conservation, its shard set against `shards.glb`, the absence of damage morphs,
and its declared transform against D15-S5.7 — and reports as *skipped* anything it would have to
re-fracture to answer, because a check that silently does nothing is worse than one that says so. It
never starts Blender.

### 6.6 Three exit-code schemes that disagreed — **fixed**

`syndicate_policy.exit_codes` is now the one table. `64–79` shared, `80–89` weapon (D17-R19),
`90–99` vehicle. Two numbers moved: `syndicate_weapon`'s undocumented `INPUT_MISSING` went 66 → 65,
where D09 already has "input file unreadable" and where D17-R19 already said it was; and
`syndicate_prepare`'s `UNDER_LABELLED` went 65 → 90, off a shared code it had no claim to. `77`
is new: `TRANSFORM_NOT_PERMITTED`, its own code rather than `USAGE` because the invocation is well
formed and the content decision behind it is what is wrong.

---

## 7. What was deliberately not done

- **`syndicate_dissect` and `syndicate_prepare` keep their names.** Both are misleading (§4) and
  neither is wrong in a way that can produce a bad asset. Renaming them touches the Gradle wiring,
  three blueprint documents and a dozen memory entries to buy readability, and the review's own
  ranking put them last. Worth doing in a commit that has no behaviour in it.
- **The shared low-level modules stay in `syndicate_fracture`.** `syndicate_deform` imports
  `blender`, `geometry`, `rng` and `errors` from its sibling, which reads as though deformation
  depends on fracture and does not. The honest fix is a `syndicate_mesh` library and it is a pure
  file move; it was left out of a commit that changes behaviour, so that a bisect over this change
  lands on the behaviour and not on thirty rewritten imports.
- **The runtime still does not check.** `A510` closes the gap at the gate, which is where content is
  decided. Making `AssetLoader` refuse a manifest whose class disagrees with its part would close it
  again at load, and is worth doing when there is a second class with shards to get it wrong.

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
  cosmetic and is the one to build first; the first is FRACTURE with a different cell pattern and
  gets nothing new from a new tool.
- **Bending as a constraint rather than a shape key.** A hanging bumper that swings is
  ARTICULATE, not DEFORM, and DEC-083's articulation block already has five motions. Adding a
  `SAG` motion driven by health is a smaller change than it sounds and does not need Blender.

The pattern in that list is worth naming: **every cheap transform reuses DEFORM's or FRACTURE's
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
./gradlew :blender-tool:unitTest             # 343 pure-Python tests, no Blender needed
./gradlew :blender-tool:processFixtures      # FRACTURE over fixtures/meshes (needs Blender)
./gradlew :blender-tool:processDeformFixtures # DEFORM over two of them, separate directory
./gradlew :test-environment:verifyFixtures   # re-checks the fracture output inside Bullet
python3 -m pytest blender-tool/tests/unit -q
```

The unit suite deliberately runs without Blender: geometry, RNG, CLI parsing, classification and the
treatment tables are all pure functions, and keeping them that way is what makes the suite testable
in CI at all.
