<!-- D02-S0 --># 02 — Technical Architecture

**Document ID:** D02
**Owns:** Module layout, build system, language/library versions, package structure, process model.

---

<!-- D02-S1 -->## 1. Purpose

This document specifies the physical and logical shape of the codebase: which modules exist, what each may depend on, which language and library versions are fixed, how the build produces artifacts, and how the client, server, tooling, and verification processes relate at runtime. It is the document every other document references when it says "in module X".

Requirements in this document are numbered `R1..Rn` and cited as `D02-R7`.

---

<!-- D02-S2 -->## 2. Scope

<!-- D02-S2.1 -->### 2.1 In Scope

- System overview diagram and data flow.
- Gradle multi-project repository layout.
- Module catalogue with responsibilities and allowed dependencies.
- Fixed toolchain: Java version, libGDX version, Bullet wrapper, key third-party libraries.
- Package naming and code organisation conventions.
- Process model: how the local client, headless server, Blender tool, and test environment interact.
- Build artifacts and packaging.

<!-- D02-S2.2 -->### 2.2 Non-Goals

- **NG1.** Runtime mode behaviour and startup sequencing — see `docs/03_runtime_modes.md#D03-S5.1`.
- **NG2.** The component/system model inside `game-core` — see `docs/04_entity_component_model.md#D04-S4`.
- **NG3.** CI pipeline definition — see `docs/12_testing_validation_ci.md#D12-S5.4`.
- **NG4.** Blender tool internals — see `docs/09_blender_destruction_tool.md#D09-S5`.
- **NG5.** Deployment/hosting infrastructure (cloud provisioning, matchmaking service). Out of scope for v1; the dedicated server is a self-contained runnable JAR.

---

<!-- D02-S3 -->## 3. Dependencies

| Depends on | Why |
|---|---|
| `docs/00_master_index.md#D00-S5.2` | Global invariants, especially G17 (headless parity) and G19 (native ownership). |
| `docs/00_master_index.md#D00-S4.5` | Identifier conventions. |
| External: JDK 17 (LTS) | Language/runtime baseline. |
| External: Gradle 8.x | Build system. |
| External: libGDX 1.14.2 | Engine framework. |
| External: gdx-bullet 1.14.2 | Bullet 2.8x JNI bindings. |
| External: Blender 4.2 LTS | Blender tool host (Python 3.11 embedded). |

---

<!-- D02-S4 -->## 4. Data Contracts

<!-- D02-S4.1 -->### 4.1 Fixed Toolchain Versions

**R1.** These versions are fixed. Changing any of them requires a `spec_deviations/` memory entry (D13-S5.3) and an amendment to this table.

| Component | Version | Rationale |
|---|---|---|
| Java language level | **17** | LTS; sealed interfaces, records, pattern matching for `instanceof`, enhanced switch. libGDX 1.14.x supports running on 17+ for desktop. |
| Java toolchain (build) | **17** | Gradle toolchain pinning; reproducible across machines. |
| Gradle | **8.7+** | Version catalogs, configuration cache, toolchain support. |
| libGDX | **1.14.2** | Latest stable at time of writing. |
| Backend (client) | **LWJGL3** (`gdx-backend-lwjgl3`) | The supported desktop backend; GLFW-based; supports headless-less windowing correctly on Windows. |
| Backend (server) | **`gdx-backend-headless`** | No GL context. Provides `Application` lifecycle without graphics (G17). |
| Physics | **`gdx-bullet` 1.14.2** + native `gdx-bullet-platform` | See D02-S4.2. |
| Math | libGDX `com.badlogic.gdx.math` (`Vector3`, `Matrix4`, `Quaternion`) | Avoids a second math type system. |
| JSON | **Jackson 2.17.x** (`jackson-databind`) | Schema-friendly, streaming for snapshots, well-understood. libGDX's `Json` is used only for internal quick config, never for contract files. |
| JSON Schema validation | **networknt/json-schema-validator 1.4.x** | Validates manifests against checked-in schemas (D08-S6). |
| Networking transport | **KryoNet 2.22.0-RC1** (TCP control + UDP state) — see D02-S4.3 | `2.22.0-RC1` is the only KryoNet build published to Maven Central and is the de-facto release. |
| Logging | **SLF4J 2.x API** + **Logback 1.5.x** (binding, apps only) | Libraries depend on the API only. |
| Testing | **JUnit 5.10+**, **AssertJ 3.25+** | |
| Benchmarking | **JMH 1.37** (optional module tasks) | D12-S5.6. |
| Blender | **4.2 LTS** (Python 3.11) | LTS; `bmesh`, `bpy.types.ShapeKey`, Cell Fracture add-on available. |
| Python tooling | `pytest 8.x`, `ruff`, `jsonschema` | Blender tool unit tests run outside Blender where possible (D09-S12). |
| glTF export/import | Blender's built-in glTF 2.0 exporter; runtime import via **gdx-gltf 2.3.0** | D08-S4.5. Published to **JitPack only** (`com.github.mgsx-dev.gdx-gltf:gltf`), not Maven Central; the JitPack repository is declared with a content filter restricted to that group. Amended from "3.x", which was never released. |

<!-- D02-S4.2 -->### 4.2 Bullet Wrapper Choice

**R2.** The project uses **`gdx-bullet`** (libGDX's SWIG-generated JNI wrapper around native Bullet), not JBullet.

| Option | Verdict | Reasoning |
|---|---|---|
| `gdx-bullet` (native Bullet via JNI) | **CHOSEN** | Current, maintained with libGDX; native performance for many-body debris; exposes `btRaycastVehicle`, `btCompoundShape`, `btConvexHullShape`, `btFixedConstraint` and the full constraint set; ships prebuilt natives for Windows/Linux/macOS. |
| JBullet (pure Java port of Bullet 2.72) | Rejected | Unmaintained since ~2010; missing modern constraint types and `btRaycastVehicle` improvements; markedly slower with hundreds of debris bodies. |
| Custom physics | Rejected | Cost far exceeds benefit; determinism gains illusory at this scale. |
| jolt-java / PhysX bindings | Rejected for v1 | Would decouple us from libGDX's supported path and its debug drawer; revisit only via a `decisions/` memory entry. |

**R3.** Native library initialisation (`Bullet.init()`) happens exactly once per process, in the bootstrap layer (D03-S5.1), before any physics type is touched. `Bullet.init()` is called with `useRefCounting=false`; ownership is manual and explicit per G19.

**R4.** Determinism caveat (record it, don't fight it): native Bullet is deterministic **within a single binary + platform + build** given identical inputs and ordering, but is **not guaranteed bit-identical across platforms**. Consequences:

- Deterministic physics regression tests (D12-S5.2) assert on tolerances, not bit equality, and are pinned to the CI platform for the tight-tolerance tier.
- Networking never relies on cross-machine lockstep determinism; it is state-replicated (G15, D10-S5.1).

<!-- D02-S4.3 -->### 4.3 Networking Library Choice

**R5.** Transport is **KryoNet** with the split responsibilities below. It is wrapped behind a `Transport` interface in `game-core` so it can be replaced without touching gameplay code.

| Channel | Protocol | Carries |
|---|---|---|
| Control | TCP | Handshake, match config, assembly definitions, chat, disconnect reason |
| State | UDP | Input commands (client→server), snapshots/deltas (server→client) |

**R6.** `game-core` defines `Transport`, `TransportListener`, and the message DTOs in `shared-models`. The KryoNet implementation lives in `game-core/net/kryo/` and is the only place the KryoNet types appear. Rationale: message schemas are contractual (D10-S4.2); the wire library is not.

<!-- D02-S4.4 -->### 4.4 Repository Layout

```
Syndicate/
├── settings.gradle.kts               # includes all subprojects
├── build.gradle.kts                  # common config, toolchain 17, repositories
├── gradle/
│   └── libs.versions.toml            # version catalog — single source of dependency versions
├── gradle.properties
├── CLAUDE.md                         # operational manual for the coding assistant
├── JULES.md                          # operational manual for the read-only reviewer
├── README.md
├── docs/                             # 15 blueprint documents (D00..D14)
├── .agent-memory/                    # persistent assistant memory (D13)
│   ├── INDEX.md
│   ├── decisions/  discoveries/  progress/  spec_deviations/  session_summaries/
├── schemas/                          # JSON Schemas for all contract files (D08-S6.1)
│   ├── part_definition.schema.json
│   ├── assembly_manifest.schema.json
│   ├── fracture_manifest.schema.json
│   └── verification_report.schema.json
├── assets/                           # runtime-loadable game assets (glb + json)
│   ├── parts/  vehicles/  arenas/  materials/
├── art-source/                       # .blend authoring files (not shipped)
├── fixtures/                         # canonical test assets + golden manifests (D14-S7)
│   ├── meshes/  golden/
├── shared-models/                    # pure data: DTOs, schemas-as-code, enums
├── game-core/                        # simulation: ECS, physics, damage, net, AI
├── game-client/                      # rendering, input, UI, audio; depends on game-core
├── game-server-headless/             # dedicated authoritative server; depends on game-core
├── asset-pipeline/                   # JVM-side asset validation + packing CLI
├── test-environment/                 # verification harness (D14)
├── memory-system/                    # .agent-memory lint/index tooling (D13-S5.5)
└── blender-tool/                     # Python; not a Gradle JVM project (see D02-S4.6)
    ├── pyproject.toml
    ├── syndicate_fracture/
    │   ├── __main__.py  cli.py  fracture.py  shapekeys.py  mass.py
    │   ├── hulls.py  export.py  verify.py  manifest.py  determinism.py
    └── tests/
```

**R7.** `assets/` holds only *processed, game-loadable* data. `art-source/` holds `.blend` files and is never read at runtime. `fixtures/` is read only by `test-environment` and CI.

<!-- D02-S4.5 -->### 4.5 Module Catalogue and Dependency Rules

**R8.** Dependencies flow strictly downward in this table. A module may depend only on modules listed in its "May depend on" cell. Cycles are prohibited and enforced in CI (D12-S5.4).

| Module | Type | Responsibility | May depend on | MUST NOT depend on |
|---|---|---|---|---|
| `shared-models` | Java library | Immutable DTOs, enums, IDs, JSON schema bindings, wire message records. No behaviour beyond validation. | (nothing internal) | libGDX graphics, Bullet, KryoNet |
| `game-core` | Java library | Entities/components/systems, physics world, damage model, vehicle assembly, AI, networking logic, match flow. Headless-safe. | `shared-models`, `gdx` (core), `gdx-bullet`, KryoNet, Jackson, SLF4J | `gdx-backend-lwjgl3`, any `com.badlogic.gdx.graphics.g3d` *rendering* class, any UI toolkit |
| `game-client` | Java application | Window, GL context, render systems, camera, HUD, input mapping, audio, client prediction glue, main menu. | `game-core`, `shared-models`, `gdx-backend-lwjgl3`, `gdx-gltf`, `gdx-controllers`, Jackson | `game-server-headless`, `test-environment` |
| `game-server-headless` | Java application | Dedicated authoritative server: mode boot, tick loop, connection management, admin console. | `game-core`, `shared-models`, `gdx-backend-headless` | any rendering module |
| `asset-pipeline` | Java application | CLI that validates `assets/` against schemas, resolves references, and produces the asset index consumed at startup. | `shared-models`, Jackson, json-schema-validator | `game-client` |
| `test-environment` | Java application | Verification harness (D14): asset checks, physics checks, destruction progression, visual mode, headless mode, JSON report. | `game-core`, `shared-models`, `gdx-backend-lwjgl3` (visual), `gdx-backend-headless` (headless) | `game-server-headless`, `gdx-gltf` |
| `memory-system` | Java application | `.agent-memory` tooling: index regeneration, entry lint, link check (D13-S5.5). | `shared-models` (optional) | everything else |
| `blender-tool` | Python package | Headless Blender fracture/morph/mass/export/verify tool (D09). | Blender 4.2 `bpy`, `mathutils`, stdlib | the JVM modules (communication is by file + exit code only) |

> **Note on `test-environment` and `gdx-gltf`.** The harness reads `.glb` with its own reader and must not import gdx-gltf. The importer builds libGDX `Mesh` objects, which are GPU buffers requiring a GL context, and D14-S5.13 requires the headless runner to create none — so a dependency that cannot be used in the mode the harness runs in CI would only invite a check to reach for it. `game-client`, which has a GL context by definition, keeps it.

**R9.** `game-core` headless safety is enforced by an automated check: no class in `game-core` may import from a banned package list (`com.badlogic.gdx.graphics.g3d.*` renderers, `com.badlogic.gdx.scenes.scene2d.*`, `com.badlogic.gdx.backends.*`). Mesh *data* types needed for collision shape construction are permitted through a narrow allowlist documented in the check's configuration. This satisfies G17.

**R10.** `game-client` and `game-server-headless` contain **no gameplay rules**. If a rule is needed in both, it belongs in `game-core`.

<!-- D02-S4.6 -->### 4.6 Blender Tool Integration with Gradle

**R11.** `blender-tool` is a Python project, not a Gradle JVM project, but is wired into the build as a Gradle project with `Exec` tasks so that CI has one entry point:

| Gradle task | Effect |
|---|---|
| `:blender-tool:lint` | `ruff check syndicate_fracture tests` |
| `:blender-tool:unitTest` | `pytest tests/unit` (pure-Python units, no Blender) |
| `:blender-tool:blenderTest` | `blender --background --python-expr "..."` running `tests/blender/` inside Blender |
| `:blender-tool:processFixtures` | Runs the tool over `fixtures/meshes/` producing `build/fixtures-out/` |
| `:test-environment:verifyFixtures` | Runs the harness (D14-S5.8) over `build/fixtures-out/` and compares to `fixtures/golden/` |

**R12.** The Blender executable is located via, in order: `--blender-exe` CLI flag → `SYNDICATE_BLENDER_EXE` environment variable → `blender` on `PATH`. If not found, tasks fail with a clear, machine-readable message and exit code `70` (D09-S4.3).

<!-- D02-S4.7 -->### 4.7 Package Naming

**R13.** Root package: `dev.syndicate`. Module roots:

| Module | Root package |
|---|---|
| `shared-models` | `dev.syndicate.model` |
| `game-core` | `dev.syndicate.core` |
| `game-client` | `dev.syndicate.client` |
| `game-server-headless` | `dev.syndicate.server` |
| `asset-pipeline` | `dev.syndicate.pipeline` |
| `test-environment` | `dev.syndicate.verify` |
| `memory-system` | `dev.syndicate.memory` |

**R14.** `game-core` sub-packages (fixed, referenced by other documents):

```
dev.syndicate.core
├── ecs          Engine, Entity, Component base, family/query, system scheduler   (D04)
├── component    All component types                                             (D04-S4.2)
├── system       All systems                                                     (D04-S4.3)
├── physics      Bullet world, shapes, vehicle controller, layers, stepping      (D06)
├── vehicle      Assembly, slot graph, stat aggregation                          (D05)
├── damage       Damage pipeline, propagation, state machine, fracture           (D07)
├── arena        Terrain generation, height field, surfaces, drivability         (D16)
├── asset        Runtime loading + validation of parts/assemblies/arenas         (D08)
├── net          Transport iface, replication, prediction, reconciliation        (D10)
│   └── kryo     KryoNet implementation (only place KryoNet types appear)
├── ai           Bot controllers, sensors, navigation                            (D11)
├── match        Match state machine, scoring, rules                             (D01, D11)
└── util         Seeded RNG, pools, math helpers, id allocation
```

**R15.** Naming conventions:

| Kind | Convention | Example |
|---|---|---|
| Component class | `<Noun>Component` | `RigidBodyComponent`, `DamageStateComponent` |
| System class | `<Noun>System` | `PhysicsSystem`, `DamageSystem` |
| DTO / wire record | `<Noun>Dto` or `<Noun>Message` | `PartDefinitionDto`, `SnapshotMessage` |
| Enum constant | `SCREAMING_SNAKE` | `DAMAGE_TYPE_KINETIC` → declared as `KINETIC` in `DamageType` |
| Constant | `static final` `SCREAMING_SNAKE` | `TICK_DT` |
| Asset ID literal | lowercase snake (D00-R19) | `armor_plate_medium_01` |
| Units in field names | suffix the unit | `massKg`, `maxSpeedMps`, `dtSeconds`, `pitchDeg` |
| Boolean field | `is`/`has`/`can` prefix | `isDetached`, `hasFractureData` |

**R16.** Files are formatted with **Spotless + palantir-java-format**, 4-space indent, 120-column soft limit. `./gradlew spotlessApply` before commit; `spotlessCheck` gates CI.

<!-- D02-S4.8 -->### 4.8 Build Artifacts

| Artifact | Produced by | Contents |
|---|---|---|
| `syndicate-client-<ver>.zip` | `:game-client:distZip` | Fat JAR + natives + `assets/` + launcher scripts |
| `syndicate-server-<ver>.jar` | `:game-server-headless:shadowJar` | Runnable authoritative server, no client assets beyond collision/gameplay data |
| `syndicate-verify-<ver>.jar` | `:test-environment:shadowJar` | Runnable verification harness (both modes) |
| `syndicate-pipeline-<ver>.jar` | `:asset-pipeline:shadowJar` | Asset validation CLI |
| `syndicate_fracture-<ver>.zip` | `:blender-tool:package` | Python package for `blender --background --python -m syndicate_fracture` |
| `asset-index.json` | `:asset-pipeline:buildIndex` | Resolved catalogue of all parts/vehicles/arenas (D08-S5.3) |

**R17.** Version is a single value in `gradle.properties` (`version=0.1.0`) and is stamped into every artifact and into the `toolVersion` field of every manifest the Blender tool writes (D09-S4.4), so a manifest can always be traced to a build.

---

<!-- D02-S5 -->## 5. Logic & Algorithms

<!-- D02-S5.1 -->### 5.1 System Overview Diagram

```
                          ┌──────────────────────────────────────────────┐
   ART SOURCE             │              AUTHORING / TOOLING             │
   art-source/*.blend ───►│  blender-tool (Python, headless Blender)     │
                          │   fracture → shape keys → mass → hulls →     │
                          │   export .glb  → write fracture_manifest.json│
                          │   → self-verify (D09-S7)                     │
                          └───────────────┬──────────────────────────────┘
                                          │ .glb + manifest.json
                                          ▼
                          ┌──────────────────────────────────────────────┐
                          │  asset-pipeline (JVM CLI)                    │
                          │   schema validation, reference resolution,   │
                          │   asset-index.json                           │
                          └───────────────┬──────────────────────────────┘
                                          │ assets/ (validated)
                         ┌────────────────┴──────────────────┐
                         ▼                                   ▼
        ┌─────────────────────────────┐        ┌──────────────────────────────┐
        │ test-environment (D14)      │        │        RUNTIME               │
        │  asset checks + physics     │        │                              │
        │  checks + destruction       │        │  ┌────────────────────────┐  │
        │  progression + report.json  │        │  │      game-core         │  │
        └─────────────────────────────┘        │  │  ecs │ physics │ damage│  │
                                               │  │  vehicle │ ai │ match  │  │
                                               │  │  net (authority+client)│  │
                                               │  └───────┬────────┬───────┘  │
                                               │          │        │          │
                                               │  ┌───────▼──┐  ┌──▼────────┐ │
                                               │  │game-client│ │game-server│ │
                                               │  │ render/UI │ │ headless  │ │
                                               │  │ input     │ │ authority │ │
                                               │  └───────────┘ └───────────┘ │
                                               └──────────────────────────────┘
                                                        ▲          ▲
                                                        └── UDP/TCP ┘
```

<!-- D02-S5.2 -->### 5.2 Data Flow: Art to Gameplay

```pseudo
1. Artist authors clean mesh in art-source/parts/armor_plate_medium_01.blend
   with material slots named per D08-S4.1 and scale in metres, Z-up.

2. Agent invokes:
     blender --background --factory-startup \
             --python -m syndicate_fracture -- \
             --input art-source/parts/armor_plate_medium_01.blend \
             --out   assets/parts/armor_plate_medium_01/ \
             --seed  1337 --shards 24 --damage-morphs 4

3. Tool writes:
     assets/parts/armor_plate_medium_01/mesh.glb          # intact mesh + damage morphs
     assets/parts/armor_plate_medium_01/shards.glb        # shard meshes
     assets/parts/armor_plate_medium_01/fracture_manifest.json
   and exits 0 only if its self-verification passed (D09-S7).

4. Human/agent authors assets/parts/armor_plate_medium_01/part.json  (D08-S4.2),
   referencing the manifest and declaring slots, health, stats.

5. asset-pipeline validates part.json + fracture_manifest.json against schemas/,
   checks referential integrity, emits assets/asset-index.json.

6. test-environment verifies the processed asset in a real Bullet world (D14) and
   emits build/verify/<asset>.report.json; CI gates on exit code 0.

7. game-core's AssetRegistry loads asset-index.json at startup (D08-S5.3),
   builds PartType records, collision shapes, and morph bindings.

8. At match start, VehicleFactory instantiates assemblies into entities (D05-S5.2).
```

<!-- D02-S5.3 -->### 5.3 Process Model

**R18.** Four process roles exist. Any given OS process plays exactly one role.

| Process role | Executable | Rendering | Authority | Networking |
|---|---|---|---|---|
| Local client (single-player) | `syndicate-client` | Yes | Yes (embedded, loopback) | Loopback transport, no sockets |
| Local client (joining) | `syndicate-client` | Yes | No | Client transport |
| Listen-server host | `syndicate-client` | Yes | Yes | Server transport + loopback for local player |
| Dedicated server | `syndicate-server` | No | Yes | Server transport |
| Blender tool | `blender` | No (headless) | n/a | None (file I/O only) |
| Verification harness | `syndicate-verify` | Optional | Yes (isolated test world) | None |

**R19.** The authority and the local client, when co-located (single-player, listen server), communicate through an in-process `LoopbackTransport` that implements the same `Transport` interface as KryoNet. **There is no separate "single-player code path."** This guarantees single-player exercises the same replication logic as multiplayer (supports G15, G17).

```pseudo
function buildTransportPair(mode):
    switch mode:
        case SINGLE_PLAYER:
            pair = LoopbackTransport.createPair()     # zero-copy, zero-latency, in-process
            return (serverSide=pair.a, clientSide=pair.b)
        case HOSTED_MULTIPLAYER:
            server = KryoServerTransport(port)
            local  = LoopbackTransport.createPair()
            server.attachLocalPeer(local.a)           # host's own player uses loopback
            return (serverSide=server, clientSide=local.b)
        case DEDICATED_SERVER:
            return (serverSide=KryoServerTransport(port), clientSide=NONE)
        case CLIENT_JOIN:
            return (serverSide=NONE, clientSide=KryoClientTransport(host, port))
```

<!-- D02-S5.4 -->### 5.4 Module Bootstrap Sequence

```pseudo
function bootstrap(args):
    config = LaunchConfig.parse(args)                 # D03-S4.2
    Logging.configure(config.logLevel, config.logFile)

    if config.requiresPhysics:                        # true for every mode
        Bullet.init(useRefCounting = false)           # D02-R3; exactly once per process
        NativeResourceTracker.install()               # G19: leak detection in debug builds

    assetIndex = AssetIndexLoader.load(config.assetRoot)   # D08-S5.3
    AssetValidator.validateOrFail(assetIndex, config.strictAssets)

    world = WorldFactory.create(config, assetIndex)   # D04-S5.4
    systems = SystemSetFactory.forMode(config.mode)   # D03-S5.2 decides which systems exist
    world.registerSystems(systems)                    # fixed order per D04-S5.3

    transports = buildTransportPair(config.mode)      # D02-S5.3
    runner = config.headless ? new HeadlessLoop(world) : new ClientLoop(world)
    runner.run()                                      # D03-S5.3 / D03-S5.4
```

<!-- D02-S5.5 -->### 5.5 Gradle Configuration Skeleton

```pseudo
# settings.gradle.kts
rootProject.name = "syndicate"
include("shared-models", "game-core", "game-client", "game-server-headless",
        "asset-pipeline", "test-environment", "memory-system", "blender-tool")

# gradle/libs.versions.toml  (single source of dependency versions — D02-R1)
[versions]
gdx = "1.14.2"; jackson = "2.17.2"; kryonet = "2.22.0"
junit = "5.10.2"; assertj = "3.25.3"; slf4j = "2.0.13"; logback = "1.5.6"

[libraries]
gdx-core            = { module = "com.badlogicgames.gdx:gdx",                  version.ref = "gdx" }
gdx-backend-lwjgl3  = { module = "com.badlogicgames.gdx:gdx-backend-lwjgl3",   version.ref = "gdx" }
gdx-backend-headless= { module = "com.badlogicgames.gdx:gdx-backend-headless", version.ref = "gdx" }
gdx-bullet          = { module = "com.badlogicgames.gdx:gdx-bullet",           version.ref = "gdx" }
gdx-bullet-natives  = { module = "com.badlogicgames.gdx:gdx-bullet-platform",  version.ref = "gdx" }
...

# build.gradle.kts (root)
subprojects {
    java.toolchain.languageVersion = JavaLanguageVersion.of(17)
    tasks.withType<Test> { useJUnitPlatform(); systemProperty("syndicate.seed", "1337") }
    spotless { java { palantirJavaFormat(); licenseHeaderFile(rootProject.file("gradle/HEADER")) } }
}

# game-core/build.gradle.kts
dependencies {
    api(projects.sharedModels)
    api(libs.gdx.core); api(libs.gdx.bullet)
    runtimeOnly(libs.gdx.bullet.natives)      # natives resolved for the host platform
    implementation(libs.kryonet); implementation(libs.jackson.databind); implementation(libs.slf4j.api)
}
tasks.register("checkHeadlessSafety", HeadlessSafetyCheck::class) {  # D02-R9
    bannedPackages = ["com.badlogic.gdx.backends", "com.badlogic.gdx.scenes.scene2d",
                      "com.badlogic.gdx.graphics.g3d.shaders", "com.badlogic.gdx.graphics.g3d.ModelBatch"]
    allowedGraphicsTypes = ["com.badlogic.gdx.graphics.g3d.model.MeshPart",   # collision extraction only
                            "com.badlogic.gdx.graphics.VertexAttributes"]
}
tasks.named("check") { dependsOn("checkHeadlessSafety") }
```

<!-- D02-S5.6 -->### 5.6 Dependency Cycle and Layering Enforcement

```pseudo
function checkLayering(projectGraph):
    layers = { shared-models: 0, game-core: 1,
               game-client: 2, game-server-headless: 2,
               asset-pipeline: 1, test-environment: 2, memory-system: 0 }
    for (a, b) in projectGraph.edges:                     # a depends on b
        assert layers[a] > layers[b] : "layering violation: " + a + " -> " + b
    assert projectGraph.isAcyclic()
    for module in projectGraph.nodes:
        for banned in DEPENDENCY_RULES[module].mustNotDependOn:
            assert not projectGraph.reaches(module, banned)
    return OK
```

Run as `:checkLayering`; wired into `check`; gates CI (D12-S5.4).

<!-- D02-S5.7 -->### 5.7 Native Resource Ownership Policy (G19)

```pseudo
# Every Bullet native object (btCollisionShape, btRigidBody, btTypedConstraint,
# btCollisionWorld, btMotionState) is owned by exactly one Java object.

RULES:
  1. The allocating call site names the owner in a comment: // OWNER: PhysicsWorld
  2. Shapes are owned by ShapeCache and shared by reference (immutable, stateless).
     Bodies never dispose shapes.
  3. Bodies and motion states are owned by the entity's RigidBodyComponent; disposal
     happens in the component's onRemove, which runs in the deferred-destroy phase (D04-S5.5).
  4. Constraints are owned by PhysicsWorld and disposed before either endpoint body.
  5. Order of disposal on world teardown:
         constraints -> bodies -> motion states -> shapes -> world -> broadphase/solver/config
  6. Debug builds install NativeResourceTracker: counts allocations vs disposals per type,
     asserts zero outstanding at world teardown (D12-S5.1).

function disposeWorld(world):
    for c in world.constraints.reverse():  world.removeConstraint(c); c.dispose()
    for b in world.bodies.reverse():
        world.removeRigidBody(b); b.getMotionState()?.dispose(); b.dispose()
    ShapeCache.disposeAll()                  # only after all bodies are gone
    world.dispose(); solver.dispose(); broadphase.dispose(); collisionConfig.dispose()
    assert NativeResourceTracker.outstanding() == 0
```

---

<!-- D02-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D02-1.** `./gradlew build` succeeds from a clean clone with only a JDK 17 installed (Gradle wrapper provisions the rest).
- [ ] **AC-D02-2.** `settings.gradle.kts` includes exactly the eight modules in D02-S4.5.
- [ ] **AC-D02-3.** `:checkLayering` passes; no module depends on a module in its MUST NOT column; the project graph is acyclic.
- [ ] **AC-D02-4.** `:game-core:checkHeadlessSafety` passes; `game-core` compiles and its tests run with no display available (`-Djava.awt.headless=true`, no `DISPLAY`).
- [ ] **AC-D02-5.** All dependency versions come from `gradle/libs.versions.toml`; no hard-coded version strings exist in any `build.gradle.kts` (grep check in CI).
- [ ] **AC-D02-6.** `Bullet.init()` appears in exactly one place per executable module (grep check).
- [ ] **AC-D02-7.** Each artifact in D02-S4.8 is produced by its named task and carries the version from `gradle.properties`.
- [ ] **AC-D02-8.** `spotlessCheck` passes on all Java sources; `ruff check` passes on `blender-tool`.
- [ ] **AC-D02-9.** A dedicated server JAR starts and runs a full match with no graphics libraries present on the host (verified by D12-S5.5 smoke test).
- [ ] **AC-D02-10.** `NativeResourceTracker.outstanding() == 0` after world teardown in every integration test.
- [ ] **AC-D02-11.** Package roots match D02-S4.7 for every source file (enforced by a package-name check task).
- [ ] **AC-D02-12.** KryoNet types appear only under `dev.syndicate.core.net.kryo` (grep check).

---

<!-- D02-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | gdx-bullet natives missing for the host platform | Bootstrap fails immediately with `FATAL: bullet natives unavailable for <os>/<arch>`, exit code 78. Never fall back to a stub physics implementation. |
| E2 | JDK newer than 17 in use | Permitted for running; the toolchain still targets 17 bytecode. Build fails if source/target drift is detected. |
| E3 | `Bullet.init()` called twice | Second call is a no-op in the wrapper but indicates a bootstrap bug; debug builds assert and fail fast. |
| E4 | Blender not installed on a build agent | `:blender-tool:blenderTest` and `:processFixtures` are **skipped with a warning** on developer machines, **fail** on CI (CI declares `SYNDICATE_REQUIRE_BLENDER=1`). |
| E5 | A new dependency introduces a transitive libGDX backend into `game-core` | `checkHeadlessSafety` fails. Fix by `exclude`, not by relaxing the check. |
| E6 | Asset directory missing at startup | Client shows a fatal error dialog; server exits 66 with `ASSETS_NOT_FOUND`. |
| E7 | Version catalog and manifest `toolVersion` disagree | Asset pipeline emits a warning; strict mode (`--strict`) makes it an error (D08-S8). |
| E8 | Native memory leak detected at teardown | Debug/test builds fail the test with the per-type outstanding counts. Release builds log at ERROR and continue. |
| E9 | Two processes on one machine both bind the server port | Second process exits 74 with `PORT_IN_USE` and the port number. |
| E10 | Gradle configuration cache invalidated by an `Exec` task capturing environment | `blender-tool` tasks declare their inputs/outputs explicitly and mark `SYNDICATE_BLENDER_EXE` as an input property. |
| E11 | Circular dependency introduced between `game-core` sub-packages | Not detected by Gradle (same module); ArchUnit rules in `game-core` tests enforce the sub-package layering of D02-S4.7 (`component` may not import `system`, etc.). |
| E12 | Shipping build accidentally includes `art-source/` | Distribution task explicitly enumerates `assets/`; a CI check asserts `art-source` and `fixtures` are absent from the client zip. |

---

<!-- D02-S8 -->## 8. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D02-1 | Clean clone, `./gradlew build` on Windows and Linux | BUILD SUCCESSFUL both platforms |
| T-D02-2 | Add `implementation(libs.gdx.backend.lwjgl3)` to `game-core` | `checkHeadlessSafety` / `checkLayering` fails |
| T-D02-3 | Add a dependency from `shared-models` to `game-core` | `checkLayering` fails with "layering violation" |
| T-D02-4 | Run `game-server-headless` on a container with no X11/GL | Server boots, runs a 60-second bot match, exits 0 |
| T-D02-5 | Run `game-core` unit tests with `DISPLAY` unset | All pass |
| T-D02-6 | Instantiate and dispose a physics world 1000 times | `NativeResourceTracker.outstanding() == 0`; RSS growth < 5% |
| T-D02-7 | Build all artifacts, inspect versions | Every artifact filename and `MANIFEST.MF` `Implementation-Version` matches `gradle.properties` |
| T-D02-8 | Set `SYNDICATE_BLENDER_EXE` to a bogus path, run `:blender-tool:blenderTest` | Fails with exit code 70 and message naming the path tried |
| T-D02-9 | Grep the built client zip for `art-source` | No matches |
| T-D02-10 | Introduce a `dev.syndicate.core.component` → `dev.syndicate.core.system` import | ArchUnit test fails |
| T-D02-11 | Run single-player and multiplayer with the same assembly and identical scripted inputs | Identical authoritative state at tick 600 within physics tolerance (proves the loopback path is the same code path, D02-R19) |
| T-D02-12 | Remove `gdx-bullet-platform` from the runtime classpath | Startup fails with E1's message, exit 78 |

---

<!-- D02-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| Global invariants (G17 headless parity, G19 native ownership) | `docs/00_master_index.md#D00-S5.2` |
| Identifier conventions | `docs/00_master_index.md#D00-S4.5` |
| Runtime mode matrix and system sets | `docs/03_runtime_modes.md#D03-S4.1` |
| Launch configuration schema | `docs/03_runtime_modes.md#D03-S4.2` |
| Startup sequence detail | `docs/03_runtime_modes.md#D03-S5.1` |
| ECS engine and system ordering | `docs/04_entity_component_model.md#D04-S5.3` |
| Deferred destruction phase | `docs/04_entity_component_model.md#D04-S5.5` |
| Bullet world construction | `docs/06_physics_simulation.md#D06-S5.1` |
| Fixed timestep loop | `docs/06_physics_simulation.md#D06-S5.4` |
| Asset index and loading | `docs/08_asset_pipeline.md#D08-S5.3` |
| JSON schema locations | `docs/08_asset_pipeline.md#D08-S6.1` |
| Blender tool CLI contract and exit codes | `docs/09_blender_destruction_tool.md#D09-S4.3` |
| Transport interface and message catalogue | `docs/10_networking_multiplayer.md#D10-S4.2` |
| CI pipeline stages | `docs/12_testing_validation_ci.md#D12-S5.4` |
| Memory tooling module behaviour | `docs/13_persistent_memory_system.md#D13-S5.5` |
| Verification harness module | `docs/14_test_environment.md#D14-S5.1` |
