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
drive and come apart correctly, and the dedicated server runs as a process. There is no renderer, no
networking and no shipped content yet.

[`ROADMAP.md`](ROADMAP.md) is the current plan and an honest account of what does and does not work;
`.agent-memory/progress/` holds the per-subsystem detail.

## Repository contents

| Path | Contents |
|---|---|
| `docs/` | 15 contractual blueprint documents (D00–D14) with stable section IDs |
| `ROADMAP.md` | Phase timeline, what just happened, what is next |
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
```

Requires a JDK; the pinned Java 17 toolchain is provisioned automatically.

## Planned stack

Java 17 · libGDX 1.14.2 · gdx-bullet · Gradle 8.7+ · Blender 4.2 LTS (Python) · glTF 2.0
