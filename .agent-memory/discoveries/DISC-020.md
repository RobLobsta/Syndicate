# DISC-020: assets/ was not a test input, so content changes never re-ran the tests

**Date:** 2026-08-10
**Category:** discoveries
**Related Docs:** docs/12_testing_validation_ci.md#D12-S5.4, docs/08_asset_pipeline.md#D08-S5.3

**Status:** active

## Summary
`assets/` is on no source set, so Gradle had no reason to believe it affects `test`. Regenerating
the shipped meshes left every test task `UP-TO-DATE`, and the suite reported green against the
meshes it had seen on some earlier run. CI, which has no such cache, failed six tests on the same
commit.

## Details

**The failure it hid.** A dissection change moved the Stampede's lug nuts, spoke details and valve
stem out of the wheel. Six tests would have caught it — wheel diameter, chassis floor-pan height,
half-track, 0-100 time, and the A311 centre-of-mass assertion. Locally none of them ran. The build
said `BUILD SUCCESSFUL`, and it was telling the truth about the question it had been asked.

**Why it is easy to walk into.** Every other input to these tests *is* on a source set, so the
up-to-date check is trustworthy for code changes and silently wrong for content changes. The one
place the difference shows is exactly where the project has been doing most of its work lately.

**The fix.** `syndicate.java-conventions.gradle.kts` declares the repository's `assets/` directory
as an optional input on every `Test` task, with `PathSensitivity.RELATIVE`. Verified both ways: a
`touch` with unchanged content stays `UP-TO-DATE`, and a one-character edit re-runs the task.

**Declared on every module**, not only the two that read assets today. A directory hash costs
nothing and the failure mode is silent.

**The habit this replaces.** `--rerun-tasks` had been used earlier in the same session for exactly
this reason and then not used again. A flag that has to be remembered is not a fix; it is a
rehearsal for forgetting.

## Rationale / Context
CLAUDE.md §8 rule 4 already says to reproduce CI in a tracked-files-only tree before pushing, on the
strength of DISC-005. This is the same lesson from the other direction: that rule catches *untracked*
files, and it would not have caught this, because the file was tracked and committed — it was the
task's notion of staleness that was wrong. Both checks are needed.

## Impact
`build-logic/src/main/kotlin/syndicate.java-conventions.gradle.kts`, and every `Test` task in the
build. Directly relevant to any future session that regenerates content and runs `check`.
