<!-- D00-S0 --># 00 — Master Index, Glossary, and Global Invariants

**Document ID:** D00
**Status:** Contractual blueprint. Implementers MUST satisfy this document.
**Project:** Syndicate — modular vehicular combat game + Blender destruction tooling.

---

<!-- D00-S1 -->## 1. Purpose

This document is the entry point to the Syndicate blueprint suite. It specifies:

- **R1.** The canonical list of blueprint documents and what each one owns.
- **R2.** The **glossary** — the single authoritative definition of every domain term. No other document may redefine a glossary term or use it with a second meaning.
- **R3.** The **global invariants** — cross-cutting constraints that every other document and every implementation must respect.
- **R4.** The **stable ID convention** used to cite any section of any document.
- **R5.** The **document ownership map** — which document is the single source of truth for each concern, so that conflicts are resolvable by rule rather than by judgement.

This document does not specify game systems directly. It constrains the documents that do.

---

<!-- D00-S2 -->## 2. Scope

<!-- D00-S2.1 -->### 2.1 In Scope

- Document map and summaries.
- Glossary of all domain terms.
- Global invariants (G1–G20).
- Section ID convention and citation format.
- Conflict-resolution rule between documents.
- Units, coordinate system, and numeric conventions used project-wide.

<!-- D00-S2.2 -->### 2.2 Non-Goals

- **NG1.** This document does not specify any subsystem behaviour. Behaviour lives in D01–D14.
- **NG2.** This document does not define code APIs (class names, method signatures). Those live in D02 and D04.
- **NG3.** This document is not a project plan, schedule, or task list. Progress tracking lives in `.agent-memory/progress/` per D13.
- **NG4.** This document does not define the memory system's contents, only that it exists (see D13).

---

<!-- D00-S3 -->## 3. Dependencies

| Dependency | Kind | Notes |
|---|---|---|
| D01–D16 | Internal | This index summarises and constrains them. |
| Markdown (CommonMark + GFM tables) | Format | All docs are GFM Markdown. |
| Git | Tooling | Docs and `.agent-memory/` are version controlled. |

This document has **no external system dependencies**. Every other document depends on this one.

---

<!-- D00-S4 -->## 4. Data Contracts

<!-- D00-S4.1 -->### 4.1 Section ID Convention

**R6.** Every section header in `docs/` MUST be immediately preceded (on its own line, directly above the header) by an HTML comment containing that section's stable ID:

```
<!-- D06-S4.2 -->## 4.2 Timestep and Fixed Update
```

**ID grammar:**

```
ID       := "D" DocNumber "-S" SectionPath
DocNumber := two decimal digits, matching the document filename prefix (00..14)
SectionPath := digit+ ( "." digit+ )*
```

**R7.** IDs are **stable**. Once published, an ID is never reused for different content. If a section is deleted, its ID is retired (may be listed as `RETIRED` in that doc's changelog) and never re-issued.

The section path `99.9` is **reserved and never allocated in any document**. It exists so that a document, a test case, or a lint fixture can write a citation that is *required not to resolve* (see T-D00-2, T-D13-4) without that citation becoming a real dangling reference. The cross-reference validator (D00-S5.3) ignores citations to `S99.9` and ignores everything inside fenced code blocks, since a fenced block is an illustration of the syntax rather than a citation.

**R8.** If a section is renamed, its ID does not change. If a section is split, the original ID stays with the part that retains the original meaning; new parts receive new sibling IDs.

**R9.** A **citation** is written as `docs/<filename>#<ID>`, e.g. `docs/06_physics_simulation.md#D06-S4.2`. Prose may cite as `D06-S4.2` when the containing document is unambiguous.

**R10.** Requirement numbering: within each document, normative requirements are numbered `R1, R2, ...` monotonically in document order. A requirement is cited as `D06-R14`. Requirement numbers are stable; deleted requirements become `R<n>. (RETIRED)`.

<!-- D00-S4.2 -->### 4.2 Document Numbering and Filenames

| Doc ID | Filename | Owns (single source of truth for) |
|---|---|---|
| D00 | `00_master_index.md` | Glossary, invariants, ID scheme, units |
| D01 | `01_product_game_design.md` | Game rules, modes, progression, scoring |
| D02 | `02_technical_architecture.md` | Modules, build, versions, package layout |
| D03 | `03_runtime_modes.md` | Process modes, startup, configuration |
| D04 | `04_entity_component_model.md` | Entities, components, systems, IDs, lifecycle |
| D05 | `05_vehicle_part_system.md` | Parts, slots, degradation curves, stat aggregation |
| D06 | `06_physics_simulation.md` | Bullet config, vehicle physics, timestep, mass properties |
| D07 | `07_damage_destruction_model.md` | Damage math, state machine, fracture trigger, authority split |
| D08 | `08_asset_pipeline.md` | Asset formats, schemas, import/validation |
| D09 | `09_blender_destruction_tool.md` | Blender CLI tool contract, fracture/shape-key/mass generation |
| D10 | `10_networking_multiplayer.md` | Replication, prediction, lag compensation, wire format |
| D11 | `11_ai_bots_and_match_simulation.md` | Bot AI, navigation, match state machine, headless sim |
| D12 | `12_testing_validation_ci.md` | Test strategy, CI pipeline, benchmarks |
| D13 | `13_persistent_memory_system.md` | `.agent-memory/` structure, entry format, triggers |
| D14 | `14_test_environment.md` | Verification harness, checks, tolerances, fixtures, report schema |
| D15 | `15_vehicle_preparation_pipeline.md` | Vehicle segmentation, labelling, geometry repair, rigging, destruction authoring, audio inventory |
| D16 | `16_procedural_arena_generation.md` | Terrain, sky, road corridors, ground surfaces, structure placement and destructible structures |

<!-- D00-S4.3 -->### 4.3 Units and Numeric Conventions

**R11.** All documents and all code use these units. No document may introduce an alternative unit for a quantity listed here.

| Quantity | Unit | Notes |
|---|---|---|
| Length / position | metres (m) | Blender scene scale is 1 unit = 1 m (D08-S2). |
| Mass | kilograms (kg) | Never grams, never "units". |
| Density | kg/m³ | Material density table in D09-S6.3. |
| Time | seconds (s) | Tick durations expressed in seconds and ticks. |
| Linear velocity | m/s | |
| Angular velocity | rad/s | |
| Force | newtons (N) | |
| Torque | N·m | |
| Impulse | N·s (kg·m/s) | |
| Angle | radians internally; degrees only in authored data files (suffix `_deg`) | |
| Health | abstract hit points (HP), float | See D07-S4.1. |
| Health fraction | `healthFraction ∈ [0.0, 1.0]` | `currentHP / maxHP`. |
| Percentages in config | fractions `[0,1]`, not 0–100 | Field names never say "percent" for fractions. |

**R12.** Floating point: all gameplay and physics state is `float` (IEEE-754 binary32) unless a document explicitly requires `double`. Manifests and JSON use decimal numbers with at most 6 significant fractional digits.

**R13.** **NaN and Inf are always errors.** No system may accept, store, replicate, or export a NaN or infinite value. Detection sites and responses are specified in D06-S7, D09-S7, D14-S8.

<!-- D00-S4.4 -->### 4.4 Coordinate System and Handedness

**R14.** The world coordinate system is **Y-up, right-handed**, matching libGDX and Bullet defaults.

```
      +Y (up)
       |
       |
       o------ +X (right)
      /
    +Z (toward viewer / "back")
```

**R15.** A vehicle's local **forward** axis is **−Z**, **up** is **+Y**, **right** is **+X**. Every authored part and vehicle asset is exported in this convention.

**R16.** Blender authors in Z-up right-handed. The export step (D09-S5.6, D08-S5) performs the Z-up → Y-up conversion **exactly once**, at export. No runtime axis conversion is permitted anywhere in the game.

**R17.** Rotations are stored as unit quaternions `(x, y, z, w)`. Euler angles appear only in human-authored files, always with an explicit `_deg` suffix and an explicit rotation order field.

<!-- D00-S4.5 -->### 4.5 Identifier Conventions

**R18.** Three distinct kinds of identifier exist and MUST NOT be confused:

| Kind | Form | Lifetime | Defined in |
|---|---|---|---|
| **Asset ID** (`PartTypeId`, `VehicleTypeId`, `MaterialId`) | lowercase snake string, e.g. `armor_plate_medium_01` | Permanent, authored, content-addressed by name | D08-S4.2 |
| **Entity ID** (`EntityId`) | unsigned 32-bit integer with generation counter | One match; stable across the network within a match | D04-S6 |
| **Local handle** (Bullet pointer, render handle) | native/JVM reference | One process, one frame-to-frame lifetime | D06-S4 |

**R19.** Asset IDs match the regex `^[a-z][a-z0-9_]{2,63}$`. They are the only identifier form permitted in authored content files.

**R20.** Entity IDs are never persisted to disk and never appear in authored content.

---

<!-- D00-S5 -->## 5. Logic & Algorithms

<!-- D00-S5.1 -->### 5.1 Conflict Resolution Between Documents

**R21.** If two documents contradict each other, resolution follows this ordered rule set:

```pseudo
function resolveConflict(statementA in docA, statementB in docB):
    # Rule 1: Ownership wins.
    ownerDoc = lookupOwner(topic, table D00-S4.2)
    if docA == ownerDoc: return statementA
    if docB == ownerDoc: return statementB

    # Rule 2: Global invariants win over everything.
    if statementA violates any invariant in D00-S5.2: return statementB
    if statementB violates any invariant in D00-S5.2: return statementA

    # Rule 3: The more specific (lower-level) document wins for implementation detail;
    #         the more abstract document wins for intent.
    if topic is "why / player-facing intent": prefer D01
    if topic is "how / runtime behaviour":     prefer the lower-numbered owner in D00-S4.2

    # Rule 4: Unresolvable.
    record entry in .agent-memory/decisions/ per D13-S5.3
    raise SPEC_CONFLICT with both citations; assume the safer (more restrictive) reading
```

**R22.** An implementer who hits an unresolvable conflict MUST record a `decisions/` memory entry citing both section IDs before proceeding (D13-S5.3), and MUST proceed with the more restrictive reading.

<!-- D00-S5.2 -->### 5.2 Global Invariants (G1–G20)

These hold at all times, in all modes, in all documents. Any design that breaks one is invalid.

**Simulation and authority**

- **G1.** **Single authority.** For any given match, exactly one process is the *authority*. All gameplay-authoritative state changes originate there. (D10-S5.1)
- **G2.** **Fixed tick.** Authoritative simulation advances only in fixed steps of `TICK_DT = 1/60 s`. Rendering rate never influences simulation results. (D06-S5.4)
- **G3.** **Deterministic ordering.** Within one tick, systems execute in the fixed order declared in D04-S5.3. Iteration over entities is by ascending `EntityId`, never by hash order.
- **G4.** **Seeded randomness only.** All gameplay randomness draws from a per-match seeded PRNG owned by the authority. Cosmetic-only randomness may use an unseeded client-local PRNG and MUST NOT feed back into gameplay state. (D06-S5.5, D07-S5.7)
- **G5.** **No wall-clock in simulation.** Simulation logic reads tick numbers, never system time.

**Damage and destruction**

- **G6.** **Authoritative/cosmetic split.** Every destruction effect is classified as *authoritative* or *cosmetic*. Authoritative state is replicated and affects stats; cosmetic state is client-local and MUST NOT affect any replicated value. (D07-S4.2, D10-S4.3)
- **G7.** **Mass conservation.** When a part fractures, the sum of shard masses equals the part's mass within `MASS_TOLERANCE_FRAC = 0.02` (2%). (D06-S5.7, D14-S6.4)
- **G8.** **Monotonic damage.** Part health never increases except through an explicit, replicated repair event. Damage state transitions are monotonic within a life: `INTACT → DAMAGED → CRITICAL → DESTROYED → DETACHED`. (D07-S5.3)
- **G9.** **Detachment is one-way.** A detached part never reattaches within a match.
- **G10.** **Physics profile consistency.** After any part attach/detach, the vehicle's total mass, centre of mass, and inertia tensor are recomputed in the same tick, before the next physics step. (D05-S5.5, D06-S5.7)

**Assets and tooling**

- **G11.** **Determinism of tooling.** Given the same input file, the same seed, and the same tool version, the Blender destruction tool produces byte-identical mesh topology and an equal manifest (excluding timestamp fields). (D09-S8)
- **G12.** **Manifest is the contract.** The game and the test harness trust the manifest only after validation; every manifest is validated against its schema before use. (D08-S6, D14-S5)
- **G13.** **No positive-mass exceptions.** Every dynamic rigid body has mass > `MIN_BODY_MASS_KG = 0.01`. Static bodies have mass exactly 0. There is no other legal mass. (D06-S4.2)
- **G14.** **One conversion point.** Coordinate-system, unit, and axis conversion happens exactly once, at export (G-see R16).

**Networking**

- **G15.** **Clients never author gameplay state.** A client message is an *input intent*, never a state assertion. The authority validates and may reject it. (D10-S5.1, D10-S9)
- **G16.** **Replication is idempotent.** Applying the same snapshot twice yields the same state. Applying snapshots out of order is detected and the stale one discarded via tick numbers. (D10-S5.4)

**Engineering**

- **G17.** **Headless parity.** Every gameplay system runs identically with rendering disabled. No gameplay system may depend on a graphics context, a window, or a loaded texture. (D03-S5.2)
- **G18.** **Fail loud in validation, degrade gracefully at runtime.** Asset validation and the test harness abort with a machine-readable error. The running game logs, substitutes a safe fallback, and continues. (D08-S8, D14-S8)
- **G19.** **Native resource ownership.** Every Bullet native object has exactly one owning Java object responsible for disposal; ownership is documented at the allocation site. (D02-S5.7)
- **G20.** **Memory before action.** A coding session reads `.agent-memory/INDEX.md` before implementing, and records decisions, discoveries, and deviations as specified in D13-S6/S7.

<!-- D00-S5.3 -->### 5.3 Cross-Reference Integrity Check

```pseudo
function validateCrossReferences(docsDir):
    declaredIds = {}                      # ID -> (file, lineNumber)
    for file in listMarkdown(docsDir):
        for line in file:
            if line matches /^<!--\s*(D\d\d-S[\d.]+)\s*-->/:
                id = capture(1)
                assert id.docNumber == file.numericPrefix   # ID must match its file
                assert id not in declaredIds                # IDs are globally unique
                declaredIds[id] = (file, lineNumber)

    for file in listMarkdown(docsDir):
        for citation in findCitations(file):               # docs/NN_name.md#Dxx-Sy.z or bare Dxx-Sy.z
            assert citation.id in declaredIds : "dangling reference"
            if citation has filename:
                assert declaredIds[citation.id].file == citation.filename

    for docNumber in 00..14:
        assert exactlyOneFileWithPrefix(docNumber)
        assert requiredSectionsPresent(docNumber)          # see D00-S5.4

    return OK
```

This check is run by CI (D12-S5.4) and is a blocking gate.

<!-- D00-S5.4 -->### 5.4 Required Section Structure

**R23.** Every document D01–D16 MUST contain, in this order, sections titled:

1. Purpose
2. Scope (including an explicit non-goals subsection)
3. Dependencies
4. Data Contracts
5. Logic & Algorithms
6. Acceptance Criteria
7. Edge Cases & Failure Modes
8. Test Cases
9. Cross-References

**R24.** The nine required sections appear in the **relative order** above. A document MAY interleave additional top-level sections between them when its subject matter warrants a dedicated section; the required nine must still be present and must still appear in that relative order. Top-level sections are numbered sequentially from 1 in document order, so a document with extra sections has its Cross-References at a number above 9. Subsections nest freely (`S5.2.1`).

Documents that currently carry extra top-level sections, and why:

| Doc | Extra section(s) | Cross-References lands at |
|---|---|---|
| D04 | `S6` ID Schemes | `S10` |
| D08 | `S6` Schema Catalogue | `S10` |
| D09 | `S6` Mass Assignment and Material Data, `S7` Verification Pipeline, `S8` Determinism, `S9` Error Reporting | `S13` |
| D14 | `S7` Test Fixtures | `S10` |
| D15 | `S8` Audio Inventory | `S10` |
| D16 | `S6` Rendering, `S7` Destructible Structures | `S11` |

```pseudo
function requiredSectionsPresent(doc):
    required = ["Purpose","Scope","Dependencies","Data Contracts",
                "Logic & Algorithms","Acceptance Criteria",
                "Edge Cases & Failure Modes","Test Cases","Cross-References"]
    headings = topLevelSectionTitles(doc)      # "## N. Title", in document order

    # Required sections must appear as an ordered SUBSEQUENCE of the headings.
    # Extra sections between them are permitted (R24); missing or reordered are not.
    cursor = 0
    for name in required:
        found = false
        while cursor < headings.length:
            if headings[cursor].title contains name: found = true; cursor += 1; break
            cursor += 1
        assert found : "missing or out-of-order required section: " + name

    # Numbering is sequential from 1, with no gaps and no repeats.
    for i, h in enumerate(headings):
        assert h.number == i + 1
    return true
```

---

<!-- D00-S6 -->## 6. Glossary

**R25.** These are the only permitted meanings for these terms. Where a term has a common alternative meaning in games or graphics, the excluded meaning is listed under *Not*.

<!-- D00-S6.1 -->### 6.1 Core Domain Terms

| Term | Definition | Not |
|---|---|---|
| **Vehicle** | A gameplay entity consisting of exactly one **chassis** part plus zero or more attached parts, simulated as one primary rigid body. | Not a "car" specifically; not a render model. |
| **Chassis** | The single root part of a vehicle. Owns the primary rigid body and the vehicle's slot graph root. Cannot be detached; destroying it destroys the vehicle. | Not "frame mesh"; the chassis is a part. |
| **Part** | A discrete, individually simulated and individually damageable component of a vehicle. Has mass, health, a part type, a slot attachment, and stats. | Not a shard; not a mesh. |
| **Part Type** | The authored, immutable definition of a class of parts, identified by a `PartTypeId`. Instances of a part type are parts. | Not a category (see Part Category). |
| **Part Category** | One of: `chassis`, `armor`, `wheel`, `weapon`, `utility`, `decorative`. Determines slot compatibility and stat contribution rules. | Not a part type. |
| **Slot** | A named attachment point on a part, with a position, orientation, accepted slot type, and capacity of one part. | Not a UI inventory slot. |
| **Slot Graph** | The directed tree of parts rooted at the chassis, where each edge is an occupied slot. | Not the physics constraint graph (which is derived from it). |
| **Assembly** | An authored vehicle definition: a part list plus slot bindings. Serialised as an *assembly manifest*. | Not a runtime vehicle instance. |
| **Stat** | A named scalar performance value (e.g. `maxSpeedMps`, `steerRateRadPerSec`) at either part or vehicle scope. | Not health, not mass (those are their own fields). |
| **Stat Modifier** | A part's contribution to a vehicle stat, expressed as an additive term and/or a multiplicative factor. | |
| **Degradation** | The functional reduction of a part's stat contribution as its health falls, per the degradation curve in D05-S5.4. | Not visual damage. |
| **Damage State** | The discrete gameplay state of a part: `INTACT`, `DAMAGED`, `CRITICAL`, `DESTROYED`, `DETACHED`. Authoritative and replicated. | Not the shape key weight. |
| **Health Fraction** | `currentHP / maxHP`, clamped to `[0,1]`. Drives both degradation and shape key weight. | |
| **Deformation** | Continuous visual mesh change driven by shape keys. Cosmetic unless a document explicitly says otherwise. | Not fracture. |
| **Fracture** | The one-time replacement of a part's single rigid body with its pre-authored **shards**, triggered at `DESTROYED`. | Not procedural runtime mesh cutting. |
| **Shard** | One pre-authored convex piece of a fractured part, produced by the Blender tool. Becomes a debris rigid body at fracture time. | Not a part; shards have no health and no slots. |
| **Debris** | A short-lived, non-gameplay-relevant rigid body in the world (a shard or a detached decorative part). Collides with the world, never deals damage. | Not a part. |
| **Detachment** | The removal of a part (and its subtree) from a vehicle's slot graph and physics body, converting it to debris or to an independent body. | Not fracture (fracture is per-part; detachment is graph surgery). |
| **Shape Key** | A named morph target on a mesh, authored in Blender, exported as a morph target and driven at runtime by a weight in `[0,1]`. | Not a bone, not an animation clip. |
| **Damage Morph** | A shape key specifically representing a damage deformation level (`dmg_25`, `dmg_50`, `dmg_75`, `dmg_100`). | |
| **Hit Zone** | The mapping from a collision contact point on a vehicle to the specific part that receives damage. | Not a hitbox primitive per se. |
| **Damage Type** | One of `KINETIC`, `EXPLOSIVE`, `INCENDIARY`, `ENERGY`, `COLLISION`. Determines armour interaction and propagation. | |
| **Propagation** | The transfer of a fraction of damage from a struck part to adjacent parts along the slot graph. | Not splash radius (that is explosive area damage). |

<!-- D00-S6.2 -->### 6.2 Simulation and Networking Terms

| Term | Definition | Not |
|---|---|---|
| **Tick** | One fixed simulation step. `TICK_DT = 1/60 s`. Ticks are numbered from 0 at match start with a monotonically increasing `TickNumber` (uint32). | Not a rendered frame. |
| **Frame** | One rendered image. Frames and ticks are decoupled. | Not a tick. |
| **Authority** | The process that owns gameplay-authoritative state for a match: the dedicated server, the listen-server host, or the single-player client. | Not "the host machine" generically. |
| **Replication** | Transmission of authoritative state from the authority to clients. | Not synchronisation of cosmetic effects. |
| **Snapshot** | The authority's serialised view of replicated state at a specific tick. | |
| **Delta** | A snapshot encoded as differences against a previously acknowledged snapshot. | |
| **Input Command** | A client-produced, tick-stamped record of player intent (throttle, steer, fire, etc.). | Not a state change. |
| **Prediction** | A client's local application of its own input commands ahead of authoritative confirmation. | |
| **Reconciliation** | A client's correction of predicted state when an authoritative snapshot disagrees, by rewinding and replaying unacknowledged inputs. | Not interpolation. |
| **Interpolation** | Rendering remote entities in the past, between two received snapshots, for smoothness. | Not prediction. |
| **Lag Compensation** | The authority's rewind of hit-relevant state to the shooter's view time when validating a shot. | |
| **Cosmetic** | Client-local, non-replicated, gameplay-irrelevant. Two clients may legitimately disagree about a cosmetic value. | |
| **Authoritative** | Owned by the authority, replicated, gameplay-relevant. All clients must agree, up to network delay. | |

<!-- D00-S6.3 -->### 6.3 Engineering and Tooling Terms

| Term | Definition | Not |
|---|---|---|
| **Entity** | A runtime object identified by an `EntityId`, composed of components. | Not a class instance per se. |
| **Component** | A named block of data attached to an entity. Contains no behaviour. | Not a Java "component" in the UI sense. |
| **System** | A unit of behaviour that reads and writes components of matching entities, executed in a fixed order each tick. | |
| **World** | The container of all entities, components, systems, and the physics world for one match. | Not the level geometry (see Arena). |
| **Arena** | The static level geometry and spawn points for a match. | Not the World. |
| **Match** | One instance of gameplay from lobby to results, with a match seed and a tick counter. | Not a session. |
| **Manifest** | A machine-readable JSON document describing generated asset data (shards, morphs, masses) or an assembly. | Not the game's save file. |
| **Golden Manifest** | A checked-in reference manifest produced by a known tool version and seed, used for regression comparison. | |
| **Check** | One named, declarative verification with an ID, expected value, tolerance, and pass/fail result (D14-S4.3). | Not a JUnit test method necessarily. |
| **Verification Report** | The machine-readable JSON output of a test-environment run (D14-S4.4). | |
| **Blueprint** | One of the 15 documents in `docs/`. Contractual. | |
| **Memory Entry** | A file in `.agent-memory/` recording a decision, discovery, progress state, deviation, or session summary (D13). | Not a blueprint. |
| **Blender Tool** | The headless Blender Python CLI application specified by D09. Also called the *destruction tool*. | Not a Blender addon with UI. |
| **Test Environment** | The libGDX + Bullet verification harness specified by D14. | Not the CI system. |

<!-- D00-S6.4 -->### 6.4 Reserved Constants

**R26.** These constants are defined once here and referenced by name elsewhere. Documents may not restate a different value.

| Constant | Value | Meaning | Defined/used in |
|---|---|---|---|
| `TICK_RATE_HZ` | 60 | Simulation ticks per second | D06-S5.4 |
| `TICK_DT` | 0.0166666667 s | Fixed timestep | D06-S5.4 |
| `MAX_SUBSTEPS` | 4 | Bullet catch-up substep cap | D06-S5.4 |
| `SNAPSHOT_RATE_HZ` | 20 | Authoritative snapshot send rate | D10-S5.3 |
| `MIN_BODY_MASS_KG` | 0.01 | Minimum dynamic body mass | D06-S4.2 |
| `MASS_TOLERANCE_FRAC` | 0.02 | Mass conservation tolerance | D14-S6.4 |
| `MAX_PARTS_PER_VEHICLE` | 64 | Hard cap on parts in an assembly | D05-S4.1 |
| `MAX_SHARDS_PER_PART` | 256 | Hard cap on shards per part | D09-S4.3 |
| `DEBRIS_LIFETIME_S` | 12.0 | Default debris despawn time | D07-S5.8 |
| `MAX_DEBRIS_BODIES` | 256 | Global debris body budget | D07-S5.8 |
| `DAMAGE_STATE_THRESHOLDS` | 0.66 / 0.33 / 0.0 | INTACT→DAMAGED→CRITICAL→DESTROYED | D07-S5.3 |
| `PROPAGATION_FRACTION` | 0.20 | Damage fraction passed to each neighbour | D07-S5.4 |
| `PROPAGATION_MAX_DEPTH` | 2 | Slot-graph hops for propagation | D07-S5.4 |
| `WORLD_GRAVITY` | (0, −9.81, 0) m/s² | Gravity vector | D06-S4.1 |

---

<!-- D00-S7 -->## 7. Document Map

<!-- D00-S7.1 -->### 7.1 `01_product_game_design.md` (D01)

Defines what the player experiences: the genre (arena vehicular combat with persistent structural damage), the core fantasy (piloting a machine that visibly and functionally falls apart around you), the modes (Skirmish vs bots, Deathmatch, Team Deathmatch, Payload Escort, Time Trial), the core loop (select → deploy → fight → results → unlock → iterate), the combat rules (movement model, weapon families, damage types, hit zones), the match structure and scoring, and the progression outline. Every gameplay number that a designer would tune is either given here or explicitly delegated to D05/D07.

<!-- D00-S7.2 -->### 7.2 `02_technical_architecture.md` (D02)

Defines the code shape: a Gradle multi-project build with `shared-models`, `game-core`, `game-client`, `game-server-headless`, `asset-pipeline`, `test-environment`, `memory-system`, and `blender-tool`. Fixes Java 17, libGDX 1.14.2, gdx-bullet, LWJGL3 backend, and the dependency rules between modules (notably: `game-core` must not depend on any rendering API). Specifies package naming, the process model, and build artifacts.

<!-- D00-S7.3 -->### 7.3 `03_runtime_modes.md` (D03)

Defines the four runtime modes — local client, single-player (client with embedded authority and bots), hosted multiplayer (listen server), and dedicated headless server — as compositions of the same core systems with different system sets enabled. Specifies the launch configuration interface (CLI flags, config file, environment), the startup sequence pseudo code, and exactly which systems and resources are disabled in headless mode.

<!-- D00-S7.4 -->### 7.4 `04_entity_component_model.md` (D04)

Chooses a data-oriented component model (Ashley-style, with an explicit fixed system order) and justifies it against alternatives. Catalogues every entity archetype, every component with its fields and types, and every system with its read/write sets and execution slot. Specifies entity ID allocation with generation counters, spawn/destroy lifecycle with deferred destruction, and pooling.

<!-- D00-S7.5 -->### 7.5 `05_vehicle_part_system.md` (D05)

Specifies parts, part categories, slots and slot types, the slot graph, and per-part properties. Defines the degradation model — the exact mapping from health fraction to stat multiplier per category — and the vehicle stat aggregation order. Specifies the five visual damage states, and the physics consequences of detachment (mass removal, compound shape update, centre-of-mass recomputation).

<!-- D00-S7.6 -->### 7.6 `06_physics_simulation.md` (D06)

Specifies the Bullet integration: gdx-bullet native wrapper, world construction, collision shape rules (compound of convex hulls), body parameters, the ray-cast vehicle model (chosen over rigid-body wheels, with justification), constraint usage for attached parts, collision layers and masks, the accumulator-based fixed timestep with deterministic ordering, and mass property computation (volume × density) including runtime centre-of-mass recomputation and debris body configuration.

<!-- D00-S7.7 -->### 7.7 `07_damage_destruction_model.md` (D07)

Specifies the damage pipeline end to end: the authoritative/cosmetic classification table, damage types and armour interaction, hit-zone resolution, the damage propagation graph walk, the health→damage-state machine, the health→shape-key-weight interpolation, the fracture trigger and shard body spawn, detachment and joint-break logic, debris lifetime and budget, and what destruction state is replicated and at what rate.

<!-- D00-S7.8 -->### 7.8 `08_asset_pipeline.md` (D08)

Specifies source art conventions (Blender file structure, naming, scale, orientation), the part definition JSON schema, the assembly manifest schema, the choice of glTF 2.0 (`.glb`) as the export format with justification, how damage morphs and shard meshes are stored and referenced, the runtime import and validation pipeline, and the asset validation rule catalogue with error codes.

<!-- D00-S7.9 -->### 7.9 `09_blender_destruction_tool.md` (D09)

Specifies the headless Blender Python CLI tool: its exact argument schema and exit codes, the Voronoi fracture algorithm with seeded site generation, damage shape key generation, per-shard mass assignment from volume × material density, convex hull collision generation with decimation, the seven-stage self-verification pipeline, the output manifest schema, determinism requirements, and the machine-readable failure report consumed by an AI agent.

<!-- D00-S7.10 -->### 7.10 `10_networking_multiplayer.md` (D10)

Specifies server-authoritative networking with client-side prediction: the transport, the message catalogue, tick and snapshot rates, the delta compression scheme with baseline acknowledgement, the replicated vs non-replicated state tables (including destruction state), prediction and reconciliation pseudo code, lag compensation by rewind, the connection lifecycle state machine, anti-cheat trust boundaries, and listen-server vs dedicated-server differences.

<!-- D00-S7.11 -->### 7.11 `11_ai_bots_and_match_simulation.md` (D11)

Specifies bot AI as a behaviour-tree-driven controller producing the same `InputCommand` structure a human produces, with a sensor model, reaction delay, difficulty parameter table, steering/arrival/obstacle-avoidance driving model, navmesh-based navigation, target selection and weapon choice, and the offline headless match simulation runner used for balance sweeps. Includes the match state machine (lobby → countdown → active → ending → results).

<!-- D00-S7.12 -->### 7.12 `12_testing_validation_ci.md` (D12)

Specifies the test strategy across unit, integration, deterministic physics regression, asset validation, and headless smoke levels; the seed-locked physics test pattern; the CI pipeline stages and their gates (including the doc cross-reference check and the memory lint); performance budgets with measurement method; and the procedure for adding a new regression scenario.

<!-- D00-S7.13 -->### 7.13 `13_persistent_memory_system.md` (D13)

Specifies `.agent-memory/`: the directory layout, the entry file template with required front-matter fields, ID allocation, the `INDEX.md` format and regeneration algorithm, the exact write triggers and read triggers as decision pseudo code, the supersede/resolve lifecycle (entries are never deleted), size discipline, cross-referencing rules, and git integration. This is the coding assistant's long-term memory, subordinate to the blueprints.

<!-- D00-S7.14 -->### 7.14 `14_test_environment.md` (D14)

Specifies the verification harness that bridges the Blender tool and the game: asset-level verification (manifest ↔ mesh agreement, shard mass, morph validity, hull generation, mass conservation), physics-level verification (mass/COM/inertia, force response, resting behaviour), destruction progression verification (intact → morph interpolation → fracture → post-fracture → constraint break), vehicle integration verification, the visual inspection mode with debug overlays, the headless CI mode with exit code mapping, the five canonical test fixtures with golden manifests, the JSON verification report schema, the tolerance table with rationale, and the check registration pattern for extensibility.

<!-- D00-S7.15 -->### 7.15 `15_vehicle_preparation_pipeline.md` (D15)

Specifies how a downloaded whole-vehicle model becomes labelled game parts: the closed part-label taxonomy and the slot role and destruction class each label carries, the four-family cue ensemble (geometric, material-physical, material-nominal, structural) with its confidence model and precedence order, the per-model `parts.json` override keyed by material rather than by shell, connected-shell separation and the grouping rules that replace spatial clustering, the rotational-symmetry test that separates what turns with a wheel from what merely sits inside it, the geometry repair table and the rule that broken symmetry is reported and never repaired, hinge inference for doors and lids, the per-class destruction treatments, and the audio inventory a finished vehicle needs.

<!-- D00-S7.16 -->### 7.16 `16_procedural_arena_generation.md` (D16)

Specifies how an arena's ground and sky are generated at runtime from a seed: the height field and its
layered generation (fractal relief, wind-oriented dune fields whose slip faces stand at the angle of
repose, and a border rise that replaces the arena's invisible walls), road corridors carved from a
spline into cut and fill with a limited grade, the closed surface table and how a surface reaches
wheel grip, tyre audio and bot navigation, the height field collision shape with its two native traps,
the terrain query API, the determinism rules that let terrain be derived on every peer instead of
replicated, chunked rendering with generated tiling textures, an analytic sky that drives the skybox,
image-based lighting, sun and fog from one set of numbers, and destructible structures specified as
assemblies so that the existing damage, fracture and detach systems break them with no new system.

---

<!-- D00-S8 -->## 8. Acceptance Criteria

A conforming documentation set satisfies all of the following. Each is mechanically checkable.

- [ ] **AC-D00-1.** Exactly 17 files exist in `docs/`, named per D00-S4.2.
- [ ] **AC-D00-2.** Every `##`/`###`/`####` header in `docs/` is preceded by a stable ID comment matching the grammar in D00-S4.1.
- [ ] **AC-D00-3.** Every ID's document number matches its containing file's numeric prefix.
- [ ] **AC-D00-4.** All IDs are globally unique.
- [ ] **AC-D00-5.** Every citation of the form `docs/*.md#Dxx-Sy` resolves to a declared ID in the named file.
- [ ] **AC-D00-6.** Every document D01–D16 contains the nine required top-level sections in order (D00-S5.4).
- [ ] **AC-D00-7.** Every glossary term used in another document carries the D00-S6 meaning; no document defines a conflicting meaning for a glossary term.
- [ ] **AC-D00-8.** No document restates a constant from D00-S6.4 with a different value.
- [ ] **AC-D00-9.** No document specifies a unit contradicting D00-S4.3.
- [ ] **AC-D00-10.** Each of G1–G20 is honoured by at least one concrete mechanism specified in a downstream document, and contradicted by none.
- [ ] **AC-D00-11.** `CLAUDE.md` and `JULES.md` exist in the repository root and list all 17 documents.
- [ ] **AC-D00-12.** `.agent-memory/INDEX.md` exists and conforms to D13-S4.3.
- [ ] **AC-D00-13.** Running `validateCrossReferences(docs/)` (D00-S5.3) returns OK.

---

<!-- D00-S9 -->## 9. Edge Cases & Failure Modes

| # | Situation | Required behaviour |
|---|---|---|
| E1 | Two documents define the same term differently | Blocking documentation defect. Resolve by D00-S5.1; the glossary in D00-S6 wins. |
| E2 | A citation points to a retired ID | CI cross-reference check fails; the citing document must be updated. Retired IDs are never silently reused. |
| E3 | A new document is proposed (D17+) | Not permitted without updating D00-S4.2 and both root operational files in the same change. |
| E4 | An implementer needs a constant not in D00-S6.4 | Define it in the owning document, not here. Only *cross-cutting* constants live in D00-S6.4. |
| E5 | A downstream doc needs a different tick rate for a mode | Not permitted. `TICK_RATE_HZ` is global (G2). A mode may change *snapshot* rate only. |
| E6 | Blender authoring convention changes (e.g. Z-up removed) | Only D08/D09 change; R14–R16 remain, because the runtime convention is independent of the authoring tool. |
| E7 | An invariant must be broken to ship | Not permitted silently. Requires a `spec_deviations/` entry (D13-S5.3) plus an amendment to this document in the same commit. |
| E8 | A document grows past readability | Split into subsections, never into a second file, unless D00-S4.2 is amended. |
| E9 | Glossary term needed that collides with a Java/Bullet type name | Prefer the domain term in prose; disambiguate in code with a package or prefix (D02-S4.4). |
| E10 | An ID comment is present but malformed | Treated as missing. CI fails with the file and line number. |

---

<!-- D00-S10 -->## 10. Test Cases

| ID | Scenario | Expected result |
|---|---|---|
| T-D00-1 | Run the cross-reference validator over `docs/` | Exit 0, zero dangling references |
| T-D00-2 | Insert a citation `docs/06_physics_simulation.md#D06-S99.9` | Validator fails, reports file + line |
| T-D00-3 | Duplicate an ID across two files | Validator fails with "IDs are globally unique" |
| T-D00-4 | Place ID `D07-S1` inside `06_physics_simulation.md` | Validator fails with document-number mismatch |
| T-D00-5 | Remove the "Test Cases" section from any doc | `requiredSectionsPresent` fails for that doc |
| T-D00-6 | Grep all docs for `TICK_RATE_HZ` | Every occurrence is either the D00-S6.4 definition or a reference; no alternative value appears |
| T-D00-7 | Grep all docs for "shard" used to mean "part" | No occurrence; terminology lint passes |
| T-D00-8 | Grep for degree-valued angle fields lacking a `_deg` suffix | No occurrence in any schema table |
| T-D00-9 | Confirm each of G1–G20 has at least one downstream citation | Every invariant is referenced by at least one of D01–D14 |
| T-D00-10 | Confirm `docs/` contains no file outside the 15 named | Directory listing matches D00-S4.2 exactly |

---

<!-- D00-S11 -->## 11. Cross-References

| Topic | Authoritative section |
|---|---|
| Game rules and modes | `docs/01_product_game_design.md#D01-S4.1` |
| Module layout and versions | `docs/02_technical_architecture.md#D02-S4.1` |
| Runtime mode matrix | `docs/03_runtime_modes.md#D03-S4.1` |
| Component and system catalogue | `docs/04_entity_component_model.md#D04-S4.2` |
| Entity ID scheme | `docs/04_entity_component_model.md#D04-S6` |
| Degradation curve | `docs/05_vehicle_part_system.md#D05-S5.4` |
| Vehicle stat aggregation | `docs/05_vehicle_part_system.md#D05-S5.6` |
| Fixed timestep and accumulator | `docs/06_physics_simulation.md#D06-S5.4` |
| Mass properties and COM | `docs/06_physics_simulation.md#D06-S5.7` |
| Authoritative vs cosmetic table | `docs/07_damage_destruction_model.md#D07-S4.2` |
| Damage state machine | `docs/07_damage_destruction_model.md#D07-S5.3` |
| Shape key weight mapping | `docs/07_damage_destruction_model.md#D07-S5.5` |
| Part definition schema | `docs/08_asset_pipeline.md#D08-S4.2` |
| Assembly manifest schema | `docs/08_asset_pipeline.md#D08-S4.4` |
| Blender tool CLI contract | `docs/09_blender_destruction_tool.md#D09-S4.1` |
| Fracture manifest schema | `docs/09_blender_destruction_tool.md#D09-S4.4` |
| Replication tables | `docs/10_networking_multiplayer.md#D10-S4.3` |
| Prediction and reconciliation | `docs/10_networking_multiplayer.md#D10-S5.5` |
| Bot decision loop | `docs/11_ai_bots_and_match_simulation.md#D11-S5.3` |
| Match state machine | `docs/11_ai_bots_and_match_simulation.md#D11-S5.7` |
| CI pipeline stages | `docs/12_testing_validation_ci.md#D12-S5.4` |
| Memory entry template | `docs/13_persistent_memory_system.md#D13-S4.2` |
| Memory write/read triggers | `docs/13_persistent_memory_system.md#D13-S5.3` |
| Verification report schema | `docs/14_test_environment.md#D14-S4.4` |
| Tolerance table | `docs/14_test_environment.md#D14-S6.4` |
| Terrain generation order | `docs/16_procedural_arena_generation.md#D16-S5.1` |
| Ground surface table | `docs/16_procedural_arena_generation.md#D16-S4.4` |
| Terrain determinism rules | `docs/16_procedural_arena_generation.md#D16-S5.12` |
| Destructible structures | `docs/16_procedural_arena_generation.md#D16-S7` |

**Sources consulted for this suite:** libGDX release notes and version index ([libgdx.com/dev/versions](https://libgdx.com/dev/versions/)), gdx-bullet artifact listing ([mvnrepository.com](https://mvnrepository.com/artifact/com.badlogicgames.gdx/gdx-bullet)), Bullet physics user manual, Blender Cell Fracture add-on documentation and the `bmesh` / `bpy.types.ShapeKey` API references.
