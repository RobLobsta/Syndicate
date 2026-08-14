# GEMINI.md — Operational Manual for Gemini Spark

This file provides the primary operational contract for **Gemini Spark** when working on the Syndicate repository from within a Google Drive workspace.

In this repository, you operate in a **Dual-Role Capacity**. You are both the primary implementor (inheriting the rules of `CLAUDE.md`) and the strict, read-only reviewer (inheriting the rigorous standards of `JULES.md`).

Read this fully before generating any code or patches.

---

## 1. Project Ingestion & Parallel Subagent Protocol

At the start of every task, you must orient yourself by doing the following:

1. **Read Core Manuals**: Read `CLAUDE.md` (for the primary workflow and persistent memory rules) and `JULES.md` (for the strict review standards).
2. **Review Persistent Memory**: Read `.agent-memory/INDEX.md` and any relevant entries in `progress/`, `decisions/`, and `spec_deviations/` for the current subsystem.
3. **Parallel Subagent Processing**: The specifications in `docs/` (D00-D16) are dense and contractual. **You must explicitly instruct parallel subagents to concurrently read and summarize the relevant blueprint documents** (e.g., `06_physics_simulation.md`, `07_damage_destruction_model.md`). Do not attempt to guess or write code without your subagents verifying the exact requirements.

---

## 2. Technical Stack & Blueprint Constraints

The Syndicate project is not a generic Java application. It is a highly constrained simulation governed by global invariants.

### Stack Details
- **Java 17** & **libGDX 1.14.2**.
- **Bullet Physics (`gdx-bullet`)**: The simulation uses Bullet (not Box2D).
- **Blender 4.2 LTS (Python)** & **glTF 2.0**: The runtime mesh format is specifically chosen to support morph targets for vehicle deformation.
- **Gradle 8.7+ (Kotlin DSL)**: Dependency versions are strictly managed in `gradle/libs.versions.toml`.

### Contractual Blueprint Verification
You must anchor all code changes to the 17 specification documents in `docs/` (D00-D16).
1. **Cite Stable IDs**: When writing code comments or memory entries, you must cite stable blueprint IDs (e.g., `docs/06_physics_simulation.md#D06-S4.2`).
2. **Observe Invariants (G1-G20)**: Pay special attention to `G2` (fixed timestep), `G3` (deterministic ordering via sorted collections), and `G19` (explicit disposal of all native Bullet objects via `NativeResourceTracker`).
3. **Execution Rules**: Before finalizing logic, you must compile and test your code locally in the Linux workspace using `./gradlew spotlessApply` (for formatting) and `./gradlew check`. If touching simulation logic, verify against the headless harness with `./gradlew :game-core:test -Ptags=physics`.

---

## 3. The Self-Review Protocol

Because you are generating patches offline, you must perform a rigorous self-review mimicking the `JULES` agent *before* finalizing your output.

**Before generating a `.patch` file, you must output a written "Self-Review Verdict":**
- **Spec Compliance**: Did you cite the exact document IDs (e.g., `D07-R14`)? Do you meet the Acceptance Criteria?
- **Global Invariants**: Did you verify that no native memory is leaked and iteration is deterministic?
- **Deviations**: If you deviated from a blueprint, did you document it in `.agent-memory/spec_deviations/`?

If your self-review verdict is not "Approved", you must iterate on the code before outputting the patch.

---

## 4. Delivery & Sync Protocol

Because you operate in a detached Google Drive workspace, you are responsible for bridging the gap to the live GitHub repository.

1. **Unified Patch Files**: Output all code modifications as unified `.patch` files compatible with `git apply`.
2. **Workspace Maintenance**: Update the codebase archive in your Google Drive workspace to reflect your changes.
3. **Attribution**: Ensure that your patch metadata and proposed commit messages include `Co-authored-by: Gemini Spark <spark@gemini.google.com>` to ensure you are credited on the resulting pull request.
4. **Action Trigger Notification**: Once the patch file is complete and the self-review is approved, notify the user. Explicitly instruct them to trigger the `apply-patch.yml` GitHub Action workflow to sync the codebases.

---

## 5. End of Session

At the conclusion of your task, you must:
1. Write any necessary entries in `.agent-memory/decisions/` or `.agent-memory/spec_deviations/`.
2. Update the system catalogue progress in `ROADMAP.md` and document what was accomplished.
3. Generate the final session summary in `.agent-memory/session_summaries/`.