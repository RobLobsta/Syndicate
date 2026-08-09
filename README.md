# Syndicate

A modular vehicular combat game built on libGDX and Bullet physics, plus the Blender-based
destruction tooling that produces its assets.

Vehicles are prebuilt assemblies of discrete parts — chassis, armour, wheels, weapons, utilities.
Each part carries its own mass, health, and performance stats. As parts take damage they degrade
both visually (morph-target deformation) and functionally (speed, handling, fire rate, armour).
Destroyed parts fracture into pre-authored Voronoi shards and detach, changing the vehicle's mass,
centre of mass, and handling in real time.

## Status

In development. The destruction toolchain works end to end, the simulation spawns vehicles that
drive and come apart correctly, and the dedicated server runs as a process. Two vehicles ship, with
mass, power, braking and aerodynamics derived from published figures for real cars and held to them
by tests — see [`VEHICLES.md`](VEHICLES.md).

What is missing: a renderer, networking, and a headless glTF reader. That last one is why no model
loads yet, and it is the only thing between a `.glb` in a part directory and a car on screen.

[`ROADMAP.md`](ROADMAP.md) is the current plan and an honest account of what does and does not work;
`.agent-memory/progress/` holds the per-subsystem detail.

## Repository contents

| Path | Contents |
|---|---|
| `docs/` | 15 contractual blueprint documents (D00–D14) with stable section IDs |
| `ROADMAP.md` | Phase timeline, what just happened, what is next |
| `VEHICLES.md` | The vehicle roster and every stat, generated from the profiles |
| `assets/` | Shipped content, and [where your models go](assets/README.md) |
| `game-core/` | ECS engine, physics, vehicles, damage, assets — every gameplay system |
| `game-client/`, `game-server-headless/` | The two executables (D03 runtime modes) |
| `blender-tool/`, `test-environment/` | The destruction tool and the harness that re-verifies its output |
| `CLAUDE.md` | Operational manual for Claude, the coding assistant |
| `JULES.md` | Operational manual for Jules, the read-only review assistant |
| `.agent-memory/` | Persistent cross-session memory: decisions, discoveries, progress, deviations, session summaries |

Start with [`docs/00_master_index.md`](docs/00_master_index.md) — it holds the document map, the
glossary, and the global invariants every other document is bound by.

## Building

```bash
./gradlew check          # layering, headless safety, arch rules, unit + integration tests
./gradlew validateDocs   # blueprint cross-references
./gradlew :game-server-headless:run --args="--mode DEDICATED_SERVER"
```

Requires a JDK; the pinned Java 17 toolchain is provisioned automatically.

## Adding a vehicle

1. Add a profile to `VehicleProfiles` in `game-core` with the figures you derived it from.
2. Author its parts and assembly under `assets/` — [`assets/README.md`](assets/README.md) has the
   layout.
3. `./gradlew :game-core:test`. `VEHICLES.md` regenerates; the content tests check the JSON against
   the profile, and the calibration tests drive the vehicle and check it against the real car.

## Planned stack

Java 17 · libGDX 1.14.2 · gdx-bullet · Gradle 8.7+ · Blender 4.2 LTS (Python) · glTF 2.0
