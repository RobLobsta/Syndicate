# Syndicate

A modular vehicular combat game built on libGDX and Bullet physics, plus the Blender-based
destruction tooling that produces its assets.

Vehicles are prebuilt assemblies of discrete parts — chassis, armour, wheels, weapons, utilities.
Each part carries its own mass, health, and performance stats. As parts take damage they degrade
both visually (morph-target deformation) and functionally (speed, handling, fire rate, armour).
Destroyed parts fracture into pre-authored Voronoi shards and detach, changing the vehicle's mass,
centre of mass, and handling in real time.

## Status

Specification stage. The repository currently contains the complete blueprint suite and the
operational files for the assistants that will implement it. No implementation code exists yet —
see `.agent-memory/progress/PROG-001.md`.

## Repository contents

| Path | Contents |
|---|---|
| `docs/` | 15 contractual blueprint documents (D00–D14) with stable section IDs |
| `CLAUDE.md` | Operational manual for Claude, the coding assistant |
| `JULES.md` | Operational manual for Jules, the read-only review assistant |
| `.agent-memory/` | Persistent cross-session memory: decisions, discoveries, progress, deviations, session summaries |

Start with [`docs/00_master_index.md`](docs/00_master_index.md) — it holds the document map, the
glossary, and the global invariants every other document is bound by.

## Planned stack

Java 17 · libGDX 1.14.2 · gdx-bullet · Gradle 8.7+ · Blender 4.2 LTS (Python) · glTF 2.0
