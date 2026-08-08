# DISC-005: an unanchored `build/` in .gitignore silently untracked build-logic

**Date:** 2026-08-08
**Category:** discoveries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.4, docs/12_testing_validation_ci.md#D12-S5.4

**Status:** active

## Summary
`.gitignore` carried a bare `build/`. Git matches an unanchored pattern against a directory of that name at **any depth**, and `build-logic`'s guardrail tasks live in package `dev.syndicate.build` — so `build-logic/src/main/kotlin/dev/syndicate/build/` was ignored and all seven task classes were never committed. The working tree built perfectly; a clean clone failed to configure with `Unresolved reference: dev`.

## Details

**Why it is invisible.** `git add -A` skips ignored files without a word. `git status` does not list them. `git status --porcelain` shows nothing. The files are on disk, the build works locally, and every local check passes — including the checks implemented by the missing files. Only a fresh clone, or CI, sees the absence, and by then the symptom is a Kotlin compile error in a `.gradle.kts` file that has nothing to do with `.gitignore`.

**It happened twice.** SESS-002 recorded the six guardrail tasks as done and the build as green; they were untracked. The session that "fixed" that wrote the same files into the same package, `git add`-ed them, saw a clean `git status`, and pushed — reproducing the identical failure. Both sessions verified locally and both were wrong about what the commit contained.

**Fix.**
1. `.gitignore` build-output patterns are now anchored: `/build/` and `/*/build/`. `.gradle/` stays unanchored because a leading dot cannot begin a package name.
2. `:checkSourcesTracked` (`SourceTrackingCheckTask`) runs `git check-ignore --stdin` over every file under every module's `src/` and fails if any is excluded. Wired into `check` and into `fastChecks`, so it gates CI stage 0.

The check was verified by reintroducing the bad pattern: it reports all seven files by path with the cause. A guard that has not been seen to fail is not a guard.

## Rationale / Context
The lesson generalises past this one pattern: **a local green build proves nothing about what was committed.** Any `.gitignore` entry that could also name a package or directory inside a source tree is a live version of this bug, and nothing in an ordinary workflow surfaces it.

## Impact
`.gitignore`, `build-logic/.../SourceTrackingCheckTask.kt`, `syndicate.root-conventions.gradle.kts`. Supersedes the explanation in SESS-003, which recorded the missing classes as simply "never committed" without knowing why.
