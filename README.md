# Syndicate

A modular vehicular combat game built on libGDX and Bullet physics, plus the Blender-based
destruction tooling that produces its assets.

Vehicles are prebuilt assemblies of discrete parts — chassis, panels, wheels, weapons, utilities.
Each part carries its own mass, health, and performance stats. As parts take damage they degrade
both visually (morph-target deformation) and functionally (speed, handling, fire rate, armour).
Destroyed parts fracture into pre-authored Voronoi shards and detach, changing the vehicle's mass,
centre of mass, and handling in real time.

## Status

**Playable end to end, and not yet balanced.** The client opens on a main menu, leads to a garage
where you pick one of two vehicles and look at it, and deploys you into a match against seven bots
that drive, hunt and fight. Cars take damage, shed parts, throw sparks and shatter their glass; a
chase camera follows, a HUD reports, a scoreboard keeps score, and a runtime-synthesised engine makes
each car sound like the car it was derived from.

What that sentence does not say, and should:

- **There are no weapons in the shipped content.** Every system that fires, tracks and scores a
  weapon hit is implemented and tested, and `assets/parts/` holds two chassis and four wheels.
  Combat in a real match is collisions.
- **Nobody has tuned anything.** Handling is a real supercar's published figures; damage numbers are
  blueprint defaults; the bots ship at a difficulty nobody has lost to.
- **The arena you play in is a flat box.** A 600 m generated desert exists, collides, and has never
  been drawn — that is the next piece of work.
- **Multiplayer replicates over loopback only.** There is no socket transport yet.

[`ROADMAP.md`](ROADMAP.md) is the sequential plan and an honest account of what does and does not
work; `.agent-memory/progress/` holds the per-subsystem detail.

## Running it

```bash
./gradlew :game-client:run                       # menu → garage → match
./gradlew :game-client:run --args="--auto-start" # skip the menu, straight into a match
./gradlew :game-server-headless:run --args="--mode DEDICATED_SERVER"
```

Requires a JDK; the pinned Java 17 toolchain is provisioned automatically.

## Building something you can hand to a player

```bash
./gradlew :game-client:distZip        # portable zip: bin/, lib/, assets/ — needs a JRE 17+
./gradlew :game-client:packageWindows # self-contained Syndicate.exe — must be run ON Windows
```

`distZip` builds the same on every host, so a Linux machine can produce what a Windows player
unzips. `packageWindows` uses `jpackage`, which only ever targets the machine it runs on.

## Repository contents

| Path | Contents |
|---|---|
| `docs/` | 17 contractual blueprint documents (D00–D16) with stable section IDs |
| `ROADMAP.md` | The sequential plan: where the work stands, and what is next |
| `VEHICLES.md` | The vehicle roster and every stat, generated from the profiles |
| `assets/` | Shipped content, and [where your models go](assets/README.md) |
| `game-core/` | ECS engine, physics, vehicles, damage, assets, AI, networking, terrain |
| `game-client/` | The window: shell, render, HUD, input, audio |
| `game-server-headless/` | The dedicated server |
| `blender-tool/` | `syndicate_fracture` and `syndicate_prepare` — headless Blender CLIs |
| `test-environment/` | The harness that re-verifies tool output inside the real engine |
| `CLAUDE.md`, `JULES.md`, `GEMINI.md` | Operational manuals for the assistants that work here |
| `.agent-memory/` | Persistent cross-session memory: decisions, discoveries, progress, deviations |

Start with [`docs/00_master_index.md`](docs/00_master_index.md) — it holds the document map, the
glossary, and the global invariants every other document is bound by.

## Checks

```bash
./gradlew check                      # layering, headless safety, arch rules, unit + integration
./gradlew validateDocs               # blueprint cross-references
./gradlew :memory-system:lintMemory  # memory entry format
```

## Adding a vehicle

Either author it by hand:

1. Add a profile to `VehicleProfiles` in `game-core` with the figures you derived it from.
2. Author its parts and assembly under `assets/` — [`assets/README.md`](assets/README.md) has the
   layout.
3. `./gradlew :game-core:test`. `VEHICLES.md` regenerates; the content tests check the JSON against
   the profile, and the calibration tests drive the vehicle and check it against the real car.

Or drop a downloaded model into `art-source/vehicles/` and run `syndicate_prepare`, which writes
about twenty-five named parts and an assembly the game loads. It prepares **cars**; a tank comes out
as one immobile lump, for reasons recorded in `.agent-memory/progress/PROG-029.md`.

## Stack

Java 17 · libGDX 1.14.2 · gdx-bullet · gdx-gltf · Gradle 8.7+ · Blender 4.2 LTS (Python) · glTF 2.0
