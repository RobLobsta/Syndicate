# CLAUDE.md — Operational Manual for the Coding Assistant

This file is read at the start of every Claude Code session in this repository. It tells you what this project is, how the blueprint specifications work, how to use the persistent memory system, and the protocol to follow every session.

Read it fully before doing anything else.

---

## 1. Project Identity

**Syndicate** is a modular vehicular combat game and its companion asset tooling. Players pilot prebuilt vehicles assembled from discrete parts — chassis, armour plates, wheels, weapons, utilities — each with its own mass, health, and performance characteristics. As parts take damage they degrade **both visually** (mesh deformation via glTF morph targets generated from Blender shape keys) **and functionally** (speed, handling, fire rate, and armour all fall off along defined curves). Parts can be destroyed entirely, at which point they fracture into pre-authored Voronoi shards and detach, changing the vehicle's mass, centre of mass, and handling in real time. The game is built in **Java 17 on libGDX 1.14.2 with Bullet physics (gdx-bullet)**, runs as a local client, a single-player session, a listen server, or a dedicated headless server, and supports both AI bots and networked multiplayer.

The companion **destruction tool** is a headless Blender 4.2 Python CLI application that an AI agent invokes to process art: it fractures a clean mesh via seeded Voronoi decomposition, generates damage-state shape keys, assigns per-shard mass from volume × material density, builds collision hulls, exports glTF, and **verifies its own output** before reporting success. A separate libGDX + Bullet **verification harness** then re-checks that output inside the real engine before it ships.

---

## 2. Blueprint Document System

`docs/` contains **18 contractual specification documents**. They are not suggestions or background reading — they are the contract your implementation must satisfy.

| File | What it specifies |
|---|---|
| `docs/00_master_index.md` | Document map, glossary, global invariants G1–G20, ID convention, units, coordinate system |
| `docs/01_product_game_design.md` | Genre, core fantasy, game modes, gameplay loop, weapons, damage types, hit zones, scoring, progression |
| `docs/02_technical_architecture.md` | Gradle modules, dependency rules, fixed library versions, package layout, process model, native ownership |
| `docs/03_runtime_modes.md` | The four runtime modes, launch configuration, startup sequence, client and headless loops |
| `docs/04_entity_component_model.md` | Component-based data model, full component and system catalogues, fixed system order, entity lifecycle, ID schemes |
| `docs/05_vehicle_part_system.md` | Parts, categories, slots and the slot graph, degradation curves, detachment physics, vehicle stat aggregation |
| `docs/06_physics_simulation.md` | Bullet world config, collision shapes and layers, ray-cast vehicle model, constraints, fixed timestep, mass properties, determinism |
| `docs/07_damage_destruction_model.md` | Authoritative vs cosmetic split, damage types and armour, hit resolution, propagation, damage state machine, shape key mapping, fracture, detachment, debris |
| `docs/08_asset_pipeline.md` | Source art conventions, part/assembly/material/arena schemas, glTF export choice, asset index, validation rules and error codes |
| `docs/09_blender_destruction_tool.md` | Tool contract, CLI argument schema, exit codes, fracture manifest schema, Voronoi fracture, shape keys, mass assignment, hulls, self-verification, determinism |
| `docs/10_networking_multiplayer.md` | Authority model, message catalogue, replication tables, snapshots and deltas, prediction and reconciliation, lag compensation, trust boundaries |
| `docs/11_ai_bots_and_match_simulation.md` | Bot behaviour tree, difficulty scaling, sensor model, navigation, decision loop, match state machine, offline match simulation |
| `docs/12_testing_validation_ci.md` | Test levels, deterministic physics regression pattern, CI pipeline and gates, performance budgets, regression catalogue |
| `docs/13_persistent_memory_system.md` | The `.agent-memory/` system: layout, entry format, write and read triggers, lifecycle, lint rules |
| `docs/14_test_environment.md` | Verification harness: asset checks, physics checks, destruction progression, vehicle integration, visual and headless modes, fixtures, report schema, tolerances |
| `docs/15_vehicle_preparation_pipeline.md` | Turning a downloaded vehicle model into labelled parts: taxonomy, cue ensemble, `parts.json` overrides, geometry repair, hinge rigging, per-class destruction authoring, audio inventory |
| `docs/16_procedural_arena_generation.md` | Runtime terrain and sky generation: height field, dunes and slopes, road carving, ground surfaces, structure placement, destructible structures |
| `docs/17_weapon_system.md` | Turning a downloaded weapon model into a multi-part weapon: sub-part taxonomy, bore-axis correction, cue ensemble, seam rule, cosmetic articulation, size-class slot gating, recoil and knockback |

`RUNNING_THE_CLIENT.md`, in the repository root, is **not** a blueprint: it is the runbook for
building and running `game-client` headlessly and capturing frames from it. It lives outside `docs/`
precisely because everything in `docs/` is a numbered contract and this is not one.

### ID convention

Every section carries a stable ID in an HTML comment on the header line:

```
<!-- D06-S4.2 -->## 4.2 Rigid Body Configuration
```

- `D##` is the document number, matching the filename prefix.
- `S#.#` is the section path.
- Cite as `docs/06_physics_simulation.md#D06-S4.2`, or bare `D06-S4.2` when the file is unambiguous.
- Numbered requirements within a document are cited as `D06-R14`.
- **IDs are permanent.** They survive renames, and a deleted section's ID is never reused.

Each document also carries **Acceptance Criteria** (`AC-D##-n`), **Edge Cases** (`E1..En`), and **Test Cases** (`T-D##-n`). Those three sections are what you verify your work against.

---

## 3. How to Work With Blueprints

These are operational rules, not advice.

1. **Read before you write.** Before implementing any feature, read the relevant blueprint section(s). Find them via the document map above and `docs/00_master_index.md#D00-S11`, which maps topics to authoritative sections.

2. **Satisfy the Acceptance Criteria.** Section 6 (or whichever section is titled "Acceptance Criteria") of each document is the definition of done. An implementation that compiles and passes its own tests but violates an Acceptance Criterion is not finished.

3. **Respect the global invariants.** `docs/00_master_index.md#D00-S5.2` defines G1–G20. They apply everywhere, always. The ones most often violated by accident:
   - **G2** — simulation advances only in fixed `TICK_DT` steps; frame rate never affects results.
   - **G3** — deterministic ordering: iterate sorted collections, never hash order.
   - **G6** — the authoritative/cosmetic split; cosmetic state must never feed back into gameplay.
   - **G10** — after any part attach/detach, recompute mass, COM, and inertia in the same tick.
   - **G17** — every gameplay system must run headless.
   - **G19** — every Bullet native object has exactly one documented owner.

4. **Use the glossary.** `docs/00_master_index.md#D00-S6` is the single authority for domain terms. A *part* is not a *shard*; *fracture* is not *detachment*; *deformation* is not *fracture*. Using a term loosely in code or comments creates confusion that outlives the session.

5. **Use the constants.** `docs/00_master_index.md#D00-S6.4` holds cross-cutting constants (`TICK_DT`, `MIN_BODY_MASS_KG`, `MASS_TOLERANCE_FRAC`, …). Never redefine one with a different value.

6. **If a spec is ambiguous or incomplete**, do not stall and do not guess silently. Record the gap in `.agent-memory/decisions/`, state the assumption you are making and why, then implement under that assumption. Note the assumption in your reply to the user.

7. **If you must deviate from a spec**, record it in `.agent-memory/spec_deviations/` with the exact requirement ID you are deviating from, the justification, and a **Blueprint Disposition** (`DOC_SHOULD_CHANGE`, `IMPL_SHOULD_CHANGE`, `BOTH_VALID`, or `UNRESOLVED`). See `docs/13_persistent_memory_system.md#D13-S4.5`.

8. **Never silently deviate from a blueprint.** Always record why. An undocumented deviation is the single most expensive thing you can leave behind, because the next session will treat the code as correct and build on it.

9. **When two documents conflict**, resolve using `docs/00_master_index.md#D00-S5.1`: the owning document (per the table in `D00-S4.2`) wins; invariants beat everything; unresolvable conflicts get a `decisions/` entry and the more restrictive reading.

10. **Blueprints are amendable, but deliberately.** If the spec is genuinely wrong, say so, propose the amendment, and change the document in the same commit as the code. Do not let code and spec drift apart quietly.

---

## 4. Persistent Memory System

`.agent-memory/` is your long-term memory across sessions. It is tracked in git so it survives clones, branches, and machines. The full specification is `docs/13_persistent_memory_system.md` — read it before your first write.

```
.agent-memory/
├── INDEX.md              # generated master index; never hand-edit
├── decisions/            # DEC-nnn — choices the blueprints did not fully determine
├── discoveries/          # DISC-nnn — API quirks, gotchas, perf findings, hard-won root causes
├── progress/             # PROG-nnn — current implementation state per subsystem
├── spec_deviations/      # DEV-nnn — where implementation knowingly differs from a spec
└── session_summaries/    # SESS-nnn — one short summary per coding session
```

### When to read it

- **Start of every session** (unconditional): `INDEX.md`, then every active `progress/` entry.
- **Before implementing in a domain**: use the `Blueprint Coverage` table at the bottom of `INDEX.md` to find the handful of entries that cite the documents you are about to work against, and read those `decisions/` and `discoveries/` entries.
- **Before deviating from a spec**: check `spec_deviations/` — the deviation may already be recorded, in which case be consistent with it rather than inventing a second answer.
- **Before marking a task complete**: check that no active `progress/` entry flags it as blocked.
- **Before explaining why existing code looks the way it does**: search `decisions/` and `spec_deviations/`.

### When to write to it

Write an entry when any of these happen (full trigger logic: `docs/13_persistent_memory_system.md#D13-S5.3`):

- You made an architectural or design decision the blueprints did not fully determine → `decisions/`
- You hit an API quirk, a platform behaviour, or anything that contradicted a reasonable assumption → `discoveries/`
- You spent real effort (>30 min) finding a bug's root cause, and the fix diff does not make that cause obvious → `discoveries/`
- You measured performance and the result changes what future code should do → `discoveries/`
- You deviated from a blueprint requirement → `spec_deviations/` (**unconditional**)
- You completed a subsystem or a spec-visible capability → update `progress/`
- **The session is ending** → `session_summaries/` (**unconditional**)

### Discipline

- Every entry uses the exact template in `docs/13_persistent_memory_system.md#D13-S4.2` — five front-matter fields, four required headings.
- One entry = one decision, one discovery, one subsystem's progress, or one session. Split anything over ~500 words.
- Every entry cites blueprint sections by stable ID. Memory references specs; **specs never reference memory**.
- Entries are **never deleted**. Wrong entries are superseded, and the wrong one stays as a record so the same wrong path is not re-walked.
- Regenerate `INDEX.md` after every write (`./gradlew :memory-system:regenerateIndex`, or apply the algorithm in `D13-S5.5` by hand). Never hand-edit it.
- Never put secrets, tokens, or credentials in a memory entry.
- Write fewer, sharper entries. An entry whose "Rationale / Context" cannot name a concrete future failure should not exist.

---

## 5. Workflow Protocol

Follow this every session.

```
 1. Read CLAUDE.md (this file)
 2. Read .agent-memory/INDEX.md
 3. Read .agent-memory/progress/ entries to understand current state
 4. Identify task from user request
 5. Read relevant blueprint doc sections for the task domain
 6. Check .agent-memory/decisions/ and .agent-memory/discoveries/ for the domain
 7. Implement the task
 8. Verify against Acceptance Criteria in the relevant docs
 9. If deviations occurred, record in .agent-memory/spec_deviations/
10. Record any new decisions or discoveries
11. Update .agent-memory/progress/ with completed work
12. Write a session summary in .agent-memory/session_summaries/
13. Update .agent-memory/INDEX.md
14. Update ROADMAP.md
```

Steps 1–3 and 12–14 are unconditional. Steps 5–6 are what make the middle of the session cheap; skipping them is how a session re-derives a decision that was settled three sessions ago.

**Step 14 in full.** `ROADMAP.md` in the repository root is the project's single forward-looking document: a phase timeline from where the work stands to a production-ready game, the choices the user has not yet made, and a plain-language account of what the project actually is right now. It is written for a human reading it cold, not for you — so it carries no blueprint IDs it does not need and explains rather than cites.

Update it at the end of **every** session, before or alongside the session summary, following the checklist in its own §6: move the "we are here" marker and the system-catalogue progress bar, replace §2 with what this session did, re-cut §3's "what is next", add any choice you deliberately left to the user, and rewrite §5 only if the honest answer changed. Restructure the file freely as the work demands — reorder phases, split them, delete ones that stopped making sense. Unlike `docs/`, it is a convenience rather than a contract.

A session that skipped it leaves the next one, and the user, reading a plan for a project that no longer exists.

**Looking at it counts as a check.** `game-client` builds, runs headlessly under `xvfb`, and
captures PNGs, and `RUNNING_THE_CLIENT.md` is the runbook — including the workaround for the
sandbox that blocks the one dependency it needs. Any claim about how the game *looks* should be a
capture from the real client rather than an inference from the code: a style pass shipped a car
with black discs for wheels past eleven passing unit tests (`DISC-051`), and the garage drew every
wheel a fifth of a metre inside its own arch for three sessions, because nobody had looked.

**Blender is available. Install it.** The sandbox has no `blender` on PATH, and several notes in
this repository read that as a fixed constraint deferring fracture manifests, structures and fixture
verification. It is not one — it is an untested assumption. `bash blender-tool/tools/install-blender.sh`
fetches headless Blender 4.2 LTS and puts it on PATH in about ninety seconds. The sandbox is
ephemeral, so run it once per session, in any session that touches `blender-tool/`, `assets/` or the
fixture pipeline. Read `DISC-064` and `DISC-065` first: the executable host needs a `sys.path` insert inside the
`--python-expr` and `--python-exit-code 1`, because Blender's bundled Python ignores `PYTHONPATH`
and exits 0 on an uncaught exception.

Before reporting a task complete, run the checks the change touches:

```bash
./gradlew spotlessApply              # format first, always
./gradlew check                      # layering, headless safety, arch rules, unit + integration
./gradlew validateDocs               # doc cross-references and required sections
./gradlew :memory-system:lintMemory  # memory entry lint
./gradlew :game-core:test -Ptags=physics          # physics regression, if you touched simulation
./gradlew :test-environment:verifyFixtures        # if you touched the tool or asset pipeline
```

---

## 6. Coding Conventions

Full detail is in `docs/02_technical_architecture.md`; this is the working summary.

| Topic | Convention | Reference |
|---|---|---|
| Java version | 17 (toolchain-pinned) | `D02-S4.1` |
| Root package | `dev.syndicate` | `D02-S4.7` |
| Module packages | `.model`, `.core`, `.client`, `.server`, `.pipeline`, `.verify`, `.memory` | `D02-S4.7` |
| `game-core` sub-packages | `ecs`, `component`, `system`, `physics`, `vehicle`, `damage`, `asset`, `net`, `ai`, `match`, `util` | `D02-S4.7` |
| Component classes | `<Noun>Component`, data only — no behaviour | `D02-S4.7`, `D04-S4.1` |
| System classes | `<Noun>System`, stateless with respect to gameplay | `D04-S4.1` |
| Units in field names | Always suffixed: `massKg`, `maxSpeedMps`, `dtSeconds`, `pitchDeg` | `D00-S4.3` |
| Booleans | `is`/`has`/`can` prefix | `D02-S4.7` |
| Asset IDs | lowercase snake, `^[a-z][a-z0-9_]{2,63}$` | `D00-S4.5` |
| Formatting | Spotless + palantir-java-format, 4 spaces, 120-col soft limit | `D02-S4.7` |
| Dependency versions | Only in `gradle/libs.versions.toml` — never inline | `D02-S5.5` |
| Native Bullet objects | One documented owner per object; disposal order constraints → bodies → shapes | `D02-S5.7` |
| Randomness | Only from the seeded `RandomSource` streams; never unseeded in `game-core` | `D06-S5.8` |
| Time | Never read wall-clock time in simulation code | `D00-S5.2` (G5) |
| Iteration | Sorted collections only, in any code that affects simulation output | `D00-S5.2` (G3) |
| Tests | One level tag per test; cite the blueprint test case id in a comment | `D12-S4.1`, `D12-S4.2` |

Write code that reads like the surrounding code. Match its comment density, naming, and idiom.

---

## 7. Jules Integration

A second assistant, **Jules**, reviews this repository. Jules is defined by `JULES.md` in the repository root and is **strictly read-only**: it never writes, stages, commits, or runs anything that modifies the workspace. It reads the blueprints, the memory system, and the code, and produces written review feedback citing specific blueprint section IDs.

What this means for you:

- **Write reviewable code.** Jules will check your implementation against blueprint Acceptance Criteria and cite section IDs when it disagrees. Code whose relationship to the spec is obvious is faster to review and less likely to attract a false finding.
- **Keep memory entries accurate.** Jules reads `.agent-memory/spec_deviations/` and `decisions/` to understand *why* code looks the way it does. An unrecorded deviation will be reported as a spec violation, and it will be right to do so.
- **Jules cannot fix anything.** If Jules identifies a real problem, you apply the fix. If Jules is wrong about an entry or a piece of code, you correct the entry by superseding it (`D13-S5.6`), not by editing history.
- **Reference the same IDs Jules does.** When you respond to a review, cite blueprint sections by stable ID so the conversation stays anchored to the contract rather than to opinion.

---

## 8. CI and GitHub Usage

GitHub Actions minutes are a metered, finite resource on this repository. Treat a
runner-hour the same way you would treat a paid API call.

1. **One run per commit.** Workflows trigger on `pull_request` for every branch and on
   `push` only for the default branch. Do not add a `push: ["**"]` trigger alongside a
   `pull_request` trigger — that runs the entire pipeline twice for the same SHA. See
   `.agent-memory/decisions/DEC-010.md`.

2. **Stages are steps, not jobs.** `D12-S5.4` orders the stages as sequential gates. Because
   they are strictly sequential there is no parallelism to win by splitting them across
   runners — extra jobs only buy extra checkouts, JDK installs and cache restores. Keep them
   as steps in one job unless a stage genuinely runs independently of the others.

3. **Only run stages that can pass.** Stages whose subsystems are unfinished stay out of the
   workflow until they exist. Stage 7 (package/distribution) is deferred; it is the one stage
   `D12-S5.4` does not mark as a gate.

4. **Don't push to test.** Reproduce CI locally first, in a tracked-files-only tree
   (`git ls-files -z | xargs -0 tar …`), not just in your working directory. A working tree
   that is green proves nothing about what was committed — that is exactly how DISC-005
   happened. §8.1 below is the procedure; follow it literally.

5. **Always monitor CI after a push.** A push is not finished when it lands; it is finished
   when the run it triggers is green, or when you have reported the failure. Poll the run
   with the GitHub MCP tools (`mcp__github__actions_*`); direct GitHub API access is blocked
   in this environment. Do not end a turn with a run left unwatched.

<!-- not a blueprint section; this file has no stable ids -->
### 8.1 Verifying CI before you push

**Run this before every push. Not before every pull request — before every push**, because
`pull_request` fires on each one and a broken commit costs a full pipeline either way.

The trap this exists for is not "I forgot to run the tests". It is subtler and has now caught
this project twice: **the last thing you change is the thing you do not re-check.** A memory
entry written after the last `regenerateIndex`, a doc edited after the last `validateDocs`, a
file created after the last `git add` — each leaves a tree that was green five minutes ago and
is not green now. Reproducing from a fresh copy of the *staged* tree is what catches it, because
that copy is built after the last edit by construction.

```bash
# 1. Stage everything first. What is not staged is not what CI will see.
git add -A

# 2. Regenerate anything generated, then re-stage. INDEX.md is the one that bites (D13-R8).
./gradlew :memory-system:regenerateIndex && ./gradlew spotlessApply && git add -A

# 3. Copy the TRACKED tree somewhere clean. Not a `cp -r` of the working directory:
#    build outputs, .gradle caches and untracked scratch files all mask real failures.
rm -rf /tmp/ci && mkdir -p /tmp/ci
git ls-files -z | xargs -0 tar -cf - | tar -xf - -C /tmp/ci

# 4. Run the four stages .github/workflows/ci.yml runs, in its order, with its environment.
cd /tmp/ci
export SYNDICATE_REQUIRE_BLENDER=0 SYNDICATE_STRICT_ASSETS=1
./gradlew fastChecks                      # stage 0 — format, layering, docs, memory, ruff
./gradlew assemble                        # stage 1 — every module on the Java 17 toolchain
./gradlew test -Ptags=unit,integration    # stage 2 — the tagged suites
python3 -m pytest blender-tool/tests/unit -q
```

Only when all four are green, push. Then poll the run (§8 rule 5).

**The run status you read may be badly stale.** The `mcp__github__actions_*` responses are
cached, and the cache has been observed fifty minutes behind: a job that finished in two
minutes still reported `in_progress` on its last step, on both the jobs and the check-runs
endpoints, long after it was green. So do not read a long-looking step as a hang. Compare
against what this pipeline actually costs — the whole run is **two to three minutes**, and
`git log` will tell you what a normal one took — and if a step looks stuck, re-check by
running the same stage locally rather than by cancelling the run. Cancelling a green run
spends the minutes twice.

**Read the workflow rather than trusting this list.** `.github/workflows/ci.yml` is the
authority on what runs and in what order; if it has changed, this block is stale and the
workflow is not.

**In a sandbox that blocks JitPack**, stage 1 needs the gdx-gltf workaround in
`RUNNING_THE_CLIENT.md` and its `--init-script` on every Gradle line above. CI has no such
problem, so a stage-1 failure that is only about resolving `com.github.mgsx-dev.gdx-gltf` is
about your machine and not about the commit.

### 8.2 Being cheap with the rest of it

Runner minutes are the metered resource; your own time is the other one. Four habits, in
descending order of how much they save:

- **Read `.agent-memory/` before writing anything.** `INDEX.md` and the active `progress/`
  entries unconditionally, then the `decisions/` and `discoveries/` that cite the documents you
  are about to work against. The whole point of the system is that re-deriving a settled
  decision is the most expensive thing you can do, and it does not look expensive while you are
  doing it.
- **Run the targeted task, not `check`.** `:game-core:test -Ptags=physics` when you touched
  simulation, `:test-environment:verifyFixtures` when you touched the tool or the asset
  pipeline, `:blender-tool:unitTest` when you touched Python, `--tests '*SomeTest*'` when you
  are iterating on one. Save the full `check` for the pre-push run above, where it belongs.
- **Cite stable blueprint IDs in code comments and memory entries.** `D06-S4.2`, not "the
  physics doc". Jules reviews against those IDs (§7), and a comment that names the requirement
  it implements turns a review question into a review confirmation — and stops a correct
  deviation being reported as a violation.
- **Batch the checks you know will fail together.** `spotlessApply` before `check`, not after
  the failure; `regenerateIndex` in the same breath as any memory write. Both of this
  session's CI failures were a second run that a first ordering would have avoided.
