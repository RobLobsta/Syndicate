<!-- D13-S0 --># 13 — Persistent Memory System

**Document ID:** D13
**Owns:** `.agent-memory/` layout, entry format, ID allocation, INDEX regeneration, write/read triggers, lifecycle, lint rules.

---

<!-- D13-S1 -->## 1. Purpose

This document specifies a structured, version-controlled memory system that lets the coding assistant (Claude) carry knowledge across sessions: decisions it made, gotchas it discovered, API quirks that contradicted assumptions, performance findings, deviations from these blueprints, and implementation progress.

Sessions are stateless. Without this system, every session re-derives the same conclusions, re-hits the same Bullet quirks, and silently re-decides settled questions. The memory system exists to make the *n*-th session cheaper and more correct than the first.

**This is not a specification of the game.** It is a specification of the assistant's working memory. Memory is **subordinate to the blueprints**: when memory and a blueprint conflict, the blueprint wins and the memory entry is corrected or superseded.

Requirements are numbered `R1..Rn`, cited as `D13-R4`.

---

<!-- D13-S2 -->## 2. Scope

<!-- D13-S2.1 -->### 2.1 In Scope

- The `.agent-memory/` directory structure and its five categories.
- The mandatory entry file template and field semantics.
- Entry ID allocation and uniqueness.
- `INDEX.md` format and its regeneration algorithm.
- Write triggers: exactly when an entry must be created.
- Read triggers: exactly when memory must be consulted before acting.
- Entry lifecycle: `active` → `superseded` / `resolved`. Entries are never deleted.
- Size discipline and splitting rules.
- Cross-referencing rules to blueprint section IDs.
- Git integration and the `memory-system` lint tooling.

<!-- D13-S2.2 -->### 2.2 Non-Goals

- **NG1.** Not a task tracker or sprint board. `progress/` records *state*, not scheduled work.
- **NG2.** Not a replacement for blueprints. A memory entry never becomes normative; if a design change is needed, the blueprint changes.
- **NG3.** Not a changelog. Git history records what changed; memory records *why it matters next time*.
- **NG4.** Not a code documentation system. Javadoc/comments live in code.
- **NG5.** No runtime component. The game never reads `.agent-memory/`. Only tooling (`memory-system`) and the assistant do.
- **NG6.** Not a place for secrets, credentials, tokens, or personal data (D13-R24).

---

<!-- D13-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S4.1` | The stable ID citation format memory entries must use |
| `docs/00_master_index.md#D00-S5.2` | G20 (memory before action) |
| `docs/02_technical_architecture.md#D02-S4.5` | The `memory-system` module that lints and regenerates the index |
| `docs/12_testing_validation_ci.md#D12-S5.4` | CI stage that runs the memory lint |
| Root `CLAUDE.md` | The session workflow that invokes these triggers |
| Root `JULES.md` | The review workflow that reads (never writes) memory |
| External: Git | Persistence across clones and branches |

---

<!-- D13-S4 -->## 4. Data Contracts

<!-- D13-S4.1 -->### 4.1 Directory Structure

**R1.** The memory root is `.agent-memory/` at the repository root. Its structure is exactly:

```
.agent-memory/
├── INDEX.md                   # Master index of all entries, grouped by category (D13-S4.3)
├── decisions/                 # Architectural/design decisions with rationale
│   └── DEC-001.md
├── discoveries/               # Gotchas, API quirks, unexpected behaviours, perf findings
│   └── DISC-001.md
├── progress/                  # Implementation status: done / in progress / blocked
│   └── PROG-001.md
├── spec_deviations/           # Where implementation diverged from a blueprint, and why
│   └── DEV-001.md
└── session_summaries/         # One brief summary per coding session
    ├── SESS-001.md
    └── archive/               # Consolidated-away entries, kept but not read (D13-S4.1.1)
        └── SESS-002.md
```

**R2.** No other directories or files may exist under `.agent-memory/`, with two exceptions: an empty category directory may contain a zero-byte `.gitkeep` so that git tracks it, and any category directory may contain an `archive/` subdirectory as defined in D13-S4.1.1. No further nesting. No binary files, no images, no attachments.

<!-- D13-S4.1.1 -->#### 4.1.1 The archive

**R2a.** Each category directory MAY contain an `archive/` subdirectory. An entry moved into it keeps its filename, its ID, and its contents unchanged.

**R2b.** Archived entries are **not indexed**, are **not linted** beyond their location, and are **not read at session start**. They remain in git and on disk so that "why was this done?" can still be answered by searching; they are simply no longer part of what a session must load to be oriented.

**R2c.** An entry may be archived only when a **consolidating entry exists that covers the same ground**, and the consolidating entry names what it consolidates. Archiving is never a way to make an inconvenient record go away: an entry that is *wrong* is superseded (D13-S5.6) and stays in place, because the wrong path must remain visible so it is not re-walked.

**R2d.** In practice only `session_summaries/` accumulates fast enough to need this. A session summary's value decays within a few sessions — its durable content is the decisions, discoveries and progress it links to, all of which live elsewhere. Consolidate them into an era entry when they exceed roughly a dozen, keeping the most recent three verbatim.

**R2e.** ID numbering never restarts and never reuses. An archived `SESS-004` means `SESS-004` is spent.

**R3.** Category semantics — an entry belongs to exactly one:

| Directory | Prefix | Records | Does not record |
|---|---|---|---|
| `decisions/` | `DEC` | A choice the assistant made that the blueprints did not fully determine, with the alternatives considered and the reason. | Choices the blueprints already made (cite the blueprint instead). |
| `discoveries/` | `DISC` | An observed fact about a tool, library, platform, or the codebase that contradicted a reasonable assumption, or that took real effort to find. | Things any competent reader would get from the docs on first look. |
| `progress/` | `PROG` | The current implementation state of a subsystem: what is done, what is partial, what is blocked and on what. | Aspirational plans; per-commit history. |
| `spec_deviations/` | `DEV` | An implementation that knowingly differs from a blueprint requirement, with justification and a disposition for the blueprint. | Bugs. A bug is a defect to fix, not a deviation to record. |
| `session_summaries/` | `SESS` | What one coding session accomplished, in a few lines, with links to the entries it created. | Detailed narration; transcripts. |

<!-- D13-S4.2 -->### 4.2 Entry File Format

**R4.** Every entry file MUST match this template exactly — same headings, same order, all fields present:

```markdown
# [ENTRY-ID]: [Title]

**Date:** YYYY-MM-DD
**Category:** decisions | discoveries | progress | spec_deviations | session_summaries
**Related Docs:** docs/06_physics_simulation.md#D06-S4.2, docs/04_entity_component_model.md#D04-S4.3
**Status:** active | superseded (by [ENTRY-ID]) | resolved

## Summary
[1-3 sentence summary]

## Details
[Full explanation]

## Rationale / Context
[Why this was recorded — what would go wrong if this knowledge was lost?]

## Impact
[What systems or docs this affects]
```

**R5.** Field semantics and constraints:

| Field | Type / format | Constraint |
|---|---|---|
| `ENTRY-ID` | `^(DEC\|DISC\|PROG\|DEV\|SESS)-\d{3,}$` | Matches the filename stem exactly. Zero-padded to at least 3 digits. |
| `Title` | Free text, ≤ 80 chars | Imperative or declarative statement of the finding, not a topic label. `"Bullet compound shape COM must be recentred after child removal"`, not `"COM stuff"`. |
| `Date` | `YYYY-MM-DD` | The date the entry was **created**. Never edited afterwards. |
| `Category` | One of the five literals | Must match the containing directory. |
| `Related Docs` | Comma-separated `docs/<file>#<ID>` citations, or the literal `none` | Every citation must resolve (D00-S5.3). At least one citation unless the entry genuinely touches no blueprint (rare; `SESS` entries may use `none`). |
| `Status` | `active` \| `superseded (by ENTRY-ID)` \| `resolved` | See lifecycle, D13-S5.6. |
| `## Summary` | 1–3 sentences | Must be readable standalone; this text is what lands in `INDEX.md`. |
| `## Details` | Prose, code, tables | The full explanation. Include reproduction steps for discoveries and measured numbers for performance findings. |
| `## Rationale / Context` | Prose | Must answer: *what breaks if a future session does not know this?* An entry that cannot answer this should not exist. |
| `## Impact` | Prose or bullet list | Which modules, systems, or documents this touches. For `DEV` entries, must state the blueprint disposition (D13-R11). |

**R6.** Entries are **immutable in spirit**: the `Date`, `Category`, and historical content are not rewritten. The only fields that change after creation are `Status` (and, for `PROG` entries, the status body — see R12). Corrections are made by creating a superseding entry, not by editing history.

**R7.** Additional headings beyond the template are permitted **only** after `## Impact`, and only these: `## Reproduction`, `## Measurements`, `## References`, `## Follow-ups`.

<!-- D13-S4.3 -->### 4.3 `INDEX.md` Format

**R8.** `INDEX.md` is fully generated from the entry files. It is never hand-edited. Its format:

```markdown
# Agent Memory Index

**Generated:** YYYY-MM-DD
**Entries:** <total> (active: <n>, superseded: <n>, resolved: <n>)

> This file is generated by `memory-system`. Do not edit by hand.
> Entry format and triggers: docs/13_persistent_memory_system.md#D13-S4.2

## Decisions
| ID | Title | Date | Status | Related Docs |
|---|---|---|---|---|
| [DEC-001](decisions/DEC-001.md) | Chose ray-cast vehicle model over rigid-body wheels | 2026-08-07 | active | D06-S5.5 |

### DEC-001 — Chose ray-cast vehicle model over rigid-body wheels
Ray-cast vehicles are stable at high speed with low body counts; rigid-body wheels
required constraint tuning that destabilised on part detachment.

## Discoveries
...

## Progress
...

## Spec Deviations
...

## Session Summaries
...

## Blueprint Coverage
| Doc | Entries referencing it |
|---|---|
| docs/06_physics_simulation.md | DEC-001, DISC-003, PROG-002 |
```

**R9.** Each category section contains (a) a table of all entries in that category, ascending by ID, and (b) the one-line summaries. Superseded entries remain listed with their status and a link to the superseding entry.

**R10.** The `Blueprint Coverage` table is an inverted index: for each blueprint document cited by any entry, the list of citing entry IDs. This is what makes "check memory for this domain" (D13-S5.4) a cheap lookup instead of a search.

<!-- D13-S4.4 -->### 4.4 Entry ID Allocation

**R11.** IDs are allocated per category, monotonically, starting at `001`, zero-padded to 3 digits (widening to 4 past 999). Allocation algorithm:

```pseudo
function allocateEntryId(category):
    prefix   = PREFIX_FOR[category]              # DEC | DISC | PROG | DEV | SESS
    existing = listFiles(".agent-memory/" + category + "/")
    maxN     = 0
    for f in existing:
        n = parseInt(stripPrefix(basename(f)))   # tolerant of 3- or 4-digit forms
        maxN = max(maxN, n)
    return prefix + "-" + zeroPad(maxN + 1, max(3, digitsOf(maxN + 1)))
```

**R12.** IDs are never reused, even if an entry is superseded or made irrelevant. There is no deletion (D13-S5.6).

<!-- D13-S4.5 -->### 4.5 Deviation Entry Extra Requirements

**R13.** A `spec_deviations/DEV-nnn.md` entry's `## Impact` section MUST contain a **Blueprint Disposition** line with one of exactly these values:

| Disposition | Meaning | Required follow-up |
|---|---|---|
| `DOC_SHOULD_CHANGE` | The blueprint is wrong or outdated; the implementation is right. | The entry names the section to amend. A blueprint edit is expected in a later change. |
| `IMPL_SHOULD_CHANGE` | The blueprint is right; the deviation is temporary (e.g. a workaround). | The entry states the condition under which the implementation returns to spec. |
| `BOTH_VALID` | The blueprint permits a range and this is one choice within it. | None; but this usually means the entry belongs in `decisions/`, not here. |
| `UNRESOLVED` | Needs human input. | The entry states the question to ask. |

**R14.** A `DEV` entry MUST cite the exact requirement it deviates from, by requirement number where one exists: `docs/06_physics_simulation.md#D06-S5.6 (D06-R14)`.

---

<!-- D13-S5 -->## 5. Logic & Algorithms

<!-- D13-S5.1 -->### 5.1 Writing a New Entry

```pseudo
function writeMemoryEntry(category, title, summary, details, rationale, impact, relatedDocs):
    # 1. Validate the entry is warranted (D13-S5.3). Do not write low-value entries.
    assert shouldWrite(category, title) : "trigger not satisfied — do not write"

    # 2. Deduplicate before allocating an ID.
    similar = searchExistingEntries(category, keywordsOf(title) + keywordsOf(summary))
    if similar.isNotEmpty() and similar.best.similarity > 0.8:
        if similar.best.status == "active":
            # Prefer superseding a stale entry over adding a near-duplicate.
            if newInformationContradicts(similar.best):
                return supersede(similar.best, newEntryFrom(...))
            else:
                appendToFollowUps(similar.best, summary)     # no new ID
                return similar.best.id
    # 3. Allocate and write.
    id = allocateEntryId(category)                            # D13-S4.4
    for citation in relatedDocs:
        assert resolvesToDeclaredId(citation) : "dangling blueprint citation"

    body = renderTemplate(id, title, today(), category, relatedDocs, "active",
                          summary, details, rationale, impact)
    assert wordCount(body) <= 500 : "split this entry (D13-S5.7)"
    write(".agent-memory/" + category + "/" + id + ".md", body)

    # 4. Index is derived; regenerate rather than patch.
    regenerateIndex()                                         # D13-S5.5
    return id
```

<!-- D13-S5.2 -->### 5.2 Value Filter (What Not to Write)

```pseudo
function isWorthRecording(candidate):
    # An entry earns its place only if a future session would otherwise pay for its absence.
    if candidate.knowledge is stated plainly in a blueprint:        return false  # cite instead
    if candidate.knowledge is obtainable in <2 minutes from docs:   return false
    if candidate is a transient bug that has been fixed:            return false  # unless root
                                                                                  # cause was non-obvious
    if candidate duplicates an active entry:                        return false
    if candidate is a restatement of a git commit message:          return false
    if candidate is a plan for future work:                         return false  # not a tracker (NG1)
    return true
```

**R15.** Precision beats volume. Twenty sharp entries are more useful than two hundred vague ones. An entry whose `Rationale / Context` cannot articulate a concrete future failure is not written.

<!-- D13-S5.3 -->### 5.3 Write Triggers

**R16.** An entry MUST be written when any of these fire. This is the normative list.

```pseudo
function evaluateWriteTriggers(sessionEvent):
    entries = []

    # W1 — Architectural decision not fully specified by the blueprints.
    if sessionEvent is "made a design choice"
       and blueprintsDoNotDetermineIt(choice)
       and choice.affectsFutureCode:
        entries += write("decisions", choice.title, alternativesConsidered, whyChosen)

    # W2 — API quirk / contradicted assumption / non-obvious platform behaviour.
    if sessionEvent is "observed behaviour"
       and observation contradicts a documented or reasonable assumption:
        entries += write("discoveries", observation.title,
                         details = reproductionSteps + observedVsExpected)

    # W3 — Deviation from a blueprint requirement.
    if sessionEvent is "implemented something differing from a spec requirement":
        entries += write("spec_deviations", deviation.title,
                         mustCite = exactRequirementId,           # D13-R14
                         mustState = blueprintDisposition)        # D13-R13
        # Never deviate silently. This trigger is unconditional.

    # W4 — Significant implementation milestone reached.
    if sessionEvent is "completed a subsystem or a spec-visible capability":
        entries += updateOrWrite("progress", subsystem)           # D13-R18

    # W5 — Non-obvious bug whose root cause was expensive to find.
    if sessionEvent is "fixed a bug"
       and timeToRootCause > 30 minutes
       and rootCause is not evident from the fix diff:
        entries += write("discoveries", bug.title,
                         details = symptom + rootCause + howItWasFound)

    # W6 — Performance finding that constrains future work.
    if sessionEvent is "measured performance"
       and result changes what future code should do:
        entries += write("discoveries", finding.title, measurements = numbersWithMethod)

    # W7 — Unresolvable blueprint conflict (D00-S5.1 Rule 4).
    if sessionEvent is "hit contradictory specs":
        entries += write("decisions", conflict.title,
                         details = bothCitations + chosenReading + "more restrictive")

    # W8 — End of every coding session. Unconditional.
    if sessionEvent is "session ending":
        entries += write("session_summaries", sessionTitle,
                         details = whatWasDone + entriesCreated + nextObviousStep)

    regenerateIndex()
    return entries
```

**R17.** W3 and W8 are unconditional. A session that deviated from a spec and did not write a `DEV` entry is non-conforming. A session that ended without a `SESS` entry is non-conforming.

<!-- D13-S5.4 -->### 5.4 Read Triggers

**R18.** Memory MUST be consulted at these points, before acting.

```pseudo
function evaluateReadTriggers(sessionPhase, task):

    # RD1 — Start of every session. Unconditional.
    if sessionPhase == "session start":
        read(".agent-memory/INDEX.md")
        for e in entriesInCategory("progress") where status == "active":
            read(e)                                   # current state of the world

    # RD2 — Before implementing in a domain.
    if sessionPhase == "about to implement" :
        domainDocs = blueprintDocsFor(task.domain)            # e.g. D06, D07
        candidates = INDEX.blueprintCoverage[domainDocs]      # inverted index, D13-R10
        for id in candidates where category in {decisions, discoveries}:
            read(entry(id))
        # Reading is cheap because the coverage table narrows it to a handful.

    # RD3 — Before deviating from a spec.
    if sessionPhase == "about to deviate":
        for e in entriesInCategory("spec_deviations"):
            if e.citesRequirement(task.requirementId):
                if e.status == "active":
                    followExistingDeviation(e)        # do not re-litigate; be consistent
                    return
        # No prior deviation: proceed, and W3 will require a new entry.

    # RD4 — Before marking a task complete.
    if sessionPhase == "about to report done":
        for e in entriesInCategory("progress") where status == "active":
            assert not e.flagsAsBlocked(task) : "task is recorded blocked: " + e.id
            # If blocked and now unblocked, update the PROG entry in the same breath.

    # RD5 — Before answering a "why is it like this?" question.
    if task.kind == "explain existing code":
        search(["decisions", "spec_deviations"], keywordsOf(task))

    # RD6 — Before changing anything a discovery warned about.
    if task.touches(subsystem):
        for e in entriesInCategory("discoveries") where e.impactMentions(subsystem):
            read(e)
```

<!-- D13-S5.5 -->### 5.5 INDEX Regeneration

```pseudo
function regenerateIndex():
    entries = []
    for category in [decisions, discoveries, progress, spec_deviations, session_summaries]:
        for file in sortedByName(listFiles(".agent-memory/" + category)):
            e = parseEntry(file)
            assertValid(e)                          # D13-S5.8 lint rules
            entries.append(e)

    counts = tally(entries by status)
    out = header(generated = today(), total = entries.size, counts = counts)

    for category in CATEGORY_ORDER:
        out += "## " + displayName(category) + "\n"
        out += renderTable(entries[category], columns =
                           [ID(link), Title, Date, Status, RelatedDocs(shortIds)])
        for e in entries[category]:
            out += "### " + e.id + " — " + e.title + "\n" + e.summary + "\n"

    # Inverted index: blueprint doc -> citing entries (D13-R10)
    coverage = {}
    for e in entries:
        for c in e.relatedDocs:
            coverage[c.file].add(e.id)
    out += "## Blueprint Coverage\n" + renderTable(coverage)

    write(".agent-memory/INDEX.md", out)

# Regeneration is idempotent: running it twice with no entry changes produces an
# identical file except the Generated date. CI asserts the committed INDEX.md
# matches a freshly generated one (D13-S6, AC-D13-7).
```

**R19.** `INDEX.md` regeneration is implemented in the `memory-system` module (`dev.syndicate.memory`) and exposed as `./gradlew :memory-system:regenerateIndex` and `:memory-system:lintMemory`. The assistant may also regenerate it by hand-applying the same algorithm; the CI check is what enforces correctness either way.

<!-- D13-S5.6 -->### 5.6 Entry Lifecycle

```pseudo
STATES: active -> superseded(by NEW-ID)
        active -> resolved

function supersede(oldEntry, newContent):
    newId = allocateEntryId(oldEntry.category)
    newEntry = write(oldEntry.category, newContent)
    newEntry.body.prepend("Supersedes: " + oldEntry.id)      # in ## Details
    oldEntry.status = "superseded (by " + newId + ")"
    # The old entry's body is NOT rewritten. Its content stays as a historical record
    # of what was believed and why — that history is often the useful part.
    save(oldEntry); regenerateIndex()
    return newId

function resolve(entry, resolutionRef):
    # Used when a discovery's underlying problem is genuinely gone (library upgraded,
    # workaround removed) or a deviation returned to spec.
    entry.status = "resolved"
    entry.body.appendToImpact("Resolved: " + resolutionRef)  # commit, doc section, or entry ID
    save(entry); regenerateIndex()

# R20. Entries are NEVER deleted. Not when wrong, not when obsolete, not to tidy up.
#      A wrong entry is superseded so a future session sees both the error and the fix.
# R21. A superseded entry's ID is never reused (D13-R12).
# R22. Only PROG entries may have their body updated in place, and only in the
#      "current state" portion; the History section is append-only (D13-S5.9).
```

<!-- D13-S5.7 -->### 5.7 Size Discipline and Splitting

**R23.** One entry = one decision, one discovery, one subsystem's progress, or one session. Hard limits:

| Limit | Value | Action if exceeded |
|---|---|---|
| Soft word count | 400 words | Tighten prose. |
| Hard word count | 500 words (excluding fenced code blocks and tables) | **Split.** |
| Distinct topics per entry | 1 | Split. |
| `Summary` length | 3 sentences | Rewrite. |

```pseudo
function splitEntry(entry):
    topics = identifyDistinctTopics(entry)          # >1 means it should never have been one entry
    newIds = []
    for t in topics:
        newIds += write(entry.category, t.title, t.summary, t.details, t.rationale, t.impact)
    entry.status = "superseded (by " + join(newIds, ", ") + ")"
    save(entry); regenerateIndex()
```

<!-- D13-S5.8 -->### 5.8 Lint Rules

**R24.** `:memory-system:lintMemory` enforces the following. Each failure names the file and line.

| Rule | Check |
|---|---|
| L1 | Filename stem matches the `# [ENTRY-ID]:` heading. |
| L2 | ID matches `^(DEC\|DISC\|PROG\|DEV\|SESS)-\d{3,}$` and the prefix matches the directory. |
| L3 | All five front-matter fields present, in order, with valid values. |
| L4 | `Date` parses as `YYYY-MM-DD` and is not in the future. |
| L5 | All four required `##` headings present, in order; only permitted extra headings (D13-R7) appear, and only after `## Impact`. |
| L6 | Every `Related Docs` citation resolves to a declared blueprint ID (reuses D00-S5.3's declared-ID table). |
| L7 | Word count ≤ 500 (excluding code fences and tables). |
| L8 | `Status: superseded (by X)` — `X` exists and is in the same category. |
| L9 | No orphan supersession: if entry A says it supersedes B, B's status is `superseded (by A)`. |
| L10 | `DEV` entries contain a `Blueprint Disposition:` line with a permitted value, and cite a requirement number. |
| L11 | No duplicate IDs anywhere under `.agent-memory/`. |
| L12 | No files outside the permitted structure (D13-R2). |
| L13 | Committed `INDEX.md` equals a freshly regenerated one (ignoring the `Generated:` line). |
| L14 | Secret scan: no strings matching credential patterns (API keys, tokens, private keys, passwords, emails other than in a `References` section). |
| L15 | `PROG` entries have a `Status of Work:` table (D13-S5.9). |

<!-- D13-S5.9 -->### 5.9 Progress Entry Structure

**R25.** `PROG` entries are the only living documents in the system, because they describe *current state*. They carry an extra required structure inside `## Details`:

```markdown
## Details

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| ECS core (D04-S4.5) | done | Families, pooling, deferred destroy all implemented |
| VehicleControlSystem (D04-S4.4 #7) | in_progress | Steering done; brake balance pending |
| FractureSystem (D07-S5.6) | blocked | Needs shard manifest schema finalised (D09-S4.4) |

**History (append-only):**
- 2026-08-07: initial state recorded
- 2026-08-14: ECS core → done
```

**R26.** `State` is one of `not_started`, `in_progress`, `done`, `blocked`. A `blocked` row MUST name what it is blocked on. Read trigger RD4 (D13-S5.4) consults exactly these rows.

**R27.** One `PROG` entry per major subsystem, not one per session. The natural granularity is roughly one per blueprint document domain.

<!-- D13-S5.10 -->### 5.10 Cross-Referencing Rules

**R28.** Memory entries reference blueprint sections by stable ID (`docs/06_physics_simulation.md#D06-S5.5`). Blueprints **never** reference memory entries — memory is subordinate to specs, not the reverse. A blueprint containing a path starting `.agent-memory/` in a normative statement is a documentation defect (with the single exception of D13 itself and the root operational files, which describe the system).

**R29.** Memory entries may reference each other by ID (`See DISC-004`). They may reference code by path and symbol (`game-core/src/main/java/dev/syndicate/core/physics/PhysicsWorld.java#createVehicle`) and commits by short SHA.

<!-- D13-S5.11 -->### 5.11 Git Integration

**R30.** `.agent-memory/` is tracked in version control. It is **not** in `.gitignore`. It is committed alongside the code changes it describes, in the same commit where practical, so `git log -- .agent-memory/` reads as a decision history aligned with the diff history.

**R31.** Merge conflicts in `INDEX.md` are resolved by regenerating, never by hand-merging. Merge conflicts in entry files should not occur (entries are append-only and ID-unique); if two branches allocated the same ID, the later-merged branch renumbers its entry and regenerates.

```pseudo
function resolveMemoryMergeConflict(conflict):
    if conflict.file == "INDEX.md":
        take either side; run regenerateIndex(); done
    if conflict is "same ID allocated on two branches":
        keep = branchWithEarlierCommitDate.entry
        rename other entry to allocateEntryId(category)     # new, unused ID
        update any references to the renamed ID
        regenerateIndex()
```

---

<!-- D13-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D13-1.** `.agent-memory/` exists with exactly the five category directories and `INDEX.md`.
- [ ] **AC-D13-2.** Every entry file passes lint rules L1–L15 (D13-S5.8).
- [ ] **AC-D13-3.** Every entry uses the exact template of D13-S4.2 — all five front-matter fields and all four required headings, in order.
- [ ] **AC-D13-4.** Every `Related Docs` citation resolves to a real blueprint section ID.
- [ ] **AC-D13-5.** No entry exceeds 500 words excluding code blocks and tables.
- [ ] **AC-D13-6.** No two entries share an ID; no ID is ever reused after supersession.
- [ ] **AC-D13-7.** `INDEX.md` equals a freshly regenerated index (modulo the `Generated:` line) — checked in CI.
- [ ] **AC-D13-8.** `INDEX.md` contains a `Blueprint Coverage` table listing every doc cited by any entry.
- [ ] **AC-D13-9.** Every `spec_deviations` entry cites a specific requirement ID and declares a Blueprint Disposition.
- [ ] **AC-D13-10.** Every session that produced commits has a corresponding `SESS` entry (checked by comparing commit authorship dates to `SESS` entry dates in the memory lint's advisory mode).
- [ ] **AC-D13-11.** No entry has been deleted: the set of IDs in git history is a subset of the IDs present at HEAD (checked by `lintMemory --history`).
- [ ] **AC-D13-12.** Secret scan (L14) finds nothing.
- [ ] **AC-D13-13.** No blueprint document contains a normative reference to a memory entry (D13-R28), verified by grep, excluding `13_persistent_memory_system.md`.
- [ ] **AC-D13-14.** Every `PROG` entry has a `Status of Work` table with valid states, and every `blocked` row names its blocker.

---

<!-- D13-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | Two sessions on different branches allocate `DEC-007` | Merge-time renumber per D13-S5.11; regenerate index. Never merge two files into one ID. |
| E2 | A memory entry contradicts a blueprint | Blueprint wins (D00-S5.1). Supersede the entry with a corrected one that cites the blueprint. Do not edit the blueprint to match memory without a deliberate spec change. |
| E3 | An entry's cited section ID is retired by a doc change | Lint L6 fails. Fix by superseding the entry with one citing the current section, explaining the migration. |
| E4 | The assistant is unsure whether something warrants an entry | Apply D13-S5.2. If still unsure and it took real effort to learn, write it — under-recording expensive knowledge is worse than one extra entry. |
| E5 | An entry grows past 500 words mid-writing | Split before saving (D13-S5.7), not after. |
| E6 | A `PROG` entry says "blocked" but the blocker is resolved | The unblocking session updates the row and appends to History in the same change that unblocks it (RD4). |
| E7 | Session ends abruptly (crash, interruption) with no `SESS` entry | The next session writes a `SESS` entry for the prior session's work, titled `(reconstructed)`, based on git log. |
| E8 | `.agent-memory/` deleted or missing | Treat as an empty memory: recreate the structure, write `SESS` recording the loss, and proceed. Never fabricate entries for lost history. |
| E9 | An entry contains a secret | L14 fails the build. Remove the secret, rotate it, and record the *fact* of the incident without the value. |
| E10 | INDEX.md hand-edited | L13 fails. Regenerate. |
| E11 | A discovery turns out to be wrong (misdiagnosed) | Supersede with the correct finding; the wrong entry stays as a record so the same wrong path is not re-walked. |
| E12 | Hundreds of entries make reading at session start expensive | Session start reads `INDEX.md` (summaries only) plus active `PROG` entries — bounded work. Deeper reads are narrowed by the Blueprint Coverage table. |
| E13 | Jules (read-only reviewer) wants to correct an entry | Jules describes the correction in its review; it never writes. Claude applies it via supersession. |
| E14 | A deviation entry sits at `UNRESOLVED` indefinitely | Surfaced in `INDEX.md`; the lint emits a warning for `UNRESOLVED` deviations older than 30 days. |

---

<!-- D13-S8 -->## 8. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D13-1 | Create a valid `DEC` entry, run `lintMemory` | Passes |
| T-D13-2 | Omit the `Status` field | L3 fails, naming file and missing field |
| T-D13-3 | Put a `DISC-002.md` file in `decisions/` | L2 fails (prefix/directory mismatch) |
| T-D13-4 | Cite `docs/06_physics_simulation.md#D06-S99.9` | L6 fails (dangling citation) |
| T-D13-5 | Write a 700-word entry | L7 fails; splitting it produces two passing entries |
| T-D13-6 | Mark `DEC-001` superseded by a nonexistent `DEC-099` | L8 fails |
| T-D13-7 | Supersede `DEC-001` with `DEC-004` but leave `DEC-001` active | L9 fails |
| T-D13-8 | Hand-edit `INDEX.md` | L13 fails; `regenerateIndex` restores |
| T-D13-9 | Run `regenerateIndex` twice with no changes | Byte-identical output apart from `Generated:` |
| T-D13-10 | Add an entry citing D07 | `Blueprint Coverage` gains the entry under `docs/07_damage_destruction_model.md` |
| T-D13-11 | `DEV` entry without a Blueprint Disposition | L10 fails |
| T-D13-12 | Insert an AWS-key-shaped string into an entry | L14 fails |
| T-D13-13 | Delete `DISC-002.md` and run `lintMemory --history` | Fails: entry present in history, absent at HEAD |
| T-D13-14 | `PROG` entry with a `blocked` row and no blocker named | L15 fails |
| T-D13-15 | Simulate RD2 for a physics task | Coverage table returns the D06-citing entries only — a small, bounded set |
| T-D13-16 | Two branches each add `DEC-005`, then merge | Conflict resolution renumbers one to `DEC-006`; index regenerates cleanly |

---

<!-- D13-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| Stable ID and citation grammar | `docs/00_master_index.md#D00-S4.1` |
| Cross-reference validator (reused by lint L6) | `docs/00_master_index.md#D00-S5.3` |
| G20 — memory before action | `docs/00_master_index.md#D00-S5.2` |
| Conflict resolution (memory vs spec) | `docs/00_master_index.md#D00-S5.1` |
| `memory-system` module definition | `docs/02_technical_architecture.md#D02-S4.5` |
| CI stage running `lintMemory` | `docs/12_testing_validation_ci.md#D12-S5.4` |
| Session workflow that fires these triggers | Root `CLAUDE.md`, section "Workflow Protocol" |
| Read-only review protocol that consults memory | Root `JULES.md`, section "Review Protocol" |
| Typical decision domain: physics model choice | `docs/06_physics_simulation.md#D06-S5.5` |
| Typical discovery domain: Bullet API behaviour | `docs/06_physics_simulation.md#D06-S8` |
| Typical deviation domain: tool output contract | `docs/09_blender_destruction_tool.md#D09-S4.4` |
