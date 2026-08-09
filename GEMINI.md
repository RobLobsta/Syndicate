# GEMINI.md — Operational Manual for Gemini Spark

This file provides instructions for **Gemini Spark** when operating as a general-purpose coding assistant on the Syndicate project. When in use, Gemini Spark performs the combined roles of both Claude and Jules, reading specifications, writing code, reviewing against blueprints, and maintaining the project's memory system.

---

## 1. Project Ingestion Protocol

At the start of every task, Gemini Spark must perform the following ingestion steps:
1. **Search for and read key files**: Read `CLAUDE.md`, `JULES.md`, `README.md`, the contents of the `docs/` directory, and any related project documentation hosted in your Google Drive workspace.
2. **Review Persistent Memory**: Check `.agent-memory/INDEX.md`, recent `progress/` entries, and any relevant `decisions/` or `spec_deviations/` entries for the domain you are working in.
3. **Parallel Subagent Processing**: For large or multi-file documentation sets (like the 15 specification documents in `docs/`), explicitly delegate reading tasks to parallel subagents to process the information concurrently and efficiently.

---

## 2. Technical Stack & Execution Constraints

The Syndicate project operates under strict technical constraints and conventions.

### Tech Stack Details
- **Java 17**: The project uses a pinned Java 17 toolchain.
- **libGDX 1.14.2**: The core framework. Note the strict distinction between client, listen server, and headless dedicated server runtime modes (see `docs/03_runtime_modes.md`).
- **Bullet Physics (`gdx-bullet`)**: The simulation relies on the libGDX Bullet wrapper. All instantiated native Bullet objects must be explicitly disposed of and tracked to prevent memory leaks (invariant G19).
- **Gradle 8.7+**: The build system uses the Kotlin DSL (`build.gradle.kts`).
- **Asset & Destruction Pipeline**: Uses Blender 4.2 LTS (Python) and glTF 2.0 (`.glb`).

### Execution Constraints & Testing Rules
Before generating any patches, Gemini Spark must validate code logic locally in the Linux workspace:
- **Formatting**: Always format code using `./gradlew spotlessApply`.
- **Compile and Test**: Ensure the project compiles and all tests pass by running `./gradlew check`. This encompasses layering, headless safety, architectural rules, and unit/integration tests.
- **Physics and Harness Testing**: When simulation logic is modified, run `./gradlew :game-core:test -Ptags=physics`. For the destruction pipeline, run `./gradlew :test-environment:verifyFixtures`.
- **Invariants**: Strictly adhere to the mesh destruction math invariants and global invariants (G1–G20) found in `docs/00_master_index.md`.

---

## 3. Delivery & Sync Protocol

Because Gemini Spark operates within a Google Drive workspace detached from the live GitHub repository, changes must be synchronized via patch files.

1. **Unified Patch Files**: Output all code modifications as unified `.patch` files compatible with `git apply`.
2. **Workspace Update**: Update the codebase archive in the Google Drive workspace as necessary.
3. **Action Trigger Notification**: Once the `.patch` file is ready, notify the user so they can manually trigger the `apply-patch.yml` GitHub Action workflow to sync the codebases.
4. **Attribution**: Ensure that any commit descriptions, PRs, or patch metadata include `Co-authored-by: Gemini Spark <spark@gemini.google.com>` (or an equivalent identifier) so that Gemini Spark is appropriately credited as an author or co-author.

---

## 4. Persistent Memory & Workflow Integration

Gemini Spark assumes the memory maintenance duties defined in `CLAUDE.md`:
- Record new technical choices in `.agent-memory/decisions/`.
- Record any spec deviations in `.agent-memory/spec_deviations/`.
- Update project state in `.agent-memory/progress/` and the master `ROADMAP.md` at the conclusion of tasks.
- Ensure any observations, assumptions, or gaps in the blueprint specifications are explicitly noted rather than silently worked around.
