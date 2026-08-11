# DISC-021: a guardrail reading project.rootDir fails only when it finds a violation

**Date:** 2026-08-11
**Category:** discoveries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.6, docs/12_testing_validation_ci.md#D12-S5.4

**Status:** active

## Summary
`HeadlessSafetyCheckTask` formatted its violation paths with `file.relativeToOrSelf(project.rootDir)`. With the configuration cache on, reading `project` at execution time is a build failure — and that line only runs when the task has a violation to report, so the defect would have surfaced for the first time on the commit that broke the rule the task exists to guard.

## Details
Reproduced by adding a deliberate violation and running the check:

```
> Invocation of 'Task.project' by task ':game-core:checkCosmeticIsolation'
  at execution time is unsupported.
```

The green path never touches `rel()`, so every run since the task was written had been passing while carrying a latent failure in its reporting. The new `CosmeticIsolationCheckTask` was written with the same shape and hit it immediately, which is the only reason it was found at all.

Both tasks now take a `reportRoot: DirectoryProperty` set from the build script at configuration time. Neither reads `project` in a `@TaskAction`.

The generalisation is the useful part: **a guardrail's failure path is code that only ever runs on a bad day, so it is the code least likely to have been exercised.** Every check task in `build-logic/` should be run once against a deliberate violation before it is trusted, which is now how the two in `game-core` were verified.

## Rationale / Context
A check that cannot report is worse than no check: it converts a clear "you imported a banned package" into an obscure Gradle configuration-cache error, at exactly the moment somebody is trying to understand what they broke. And the failure is invisible to every green build, so it survives indefinitely.

## Impact
- `build-logic` `HeadlessSafetyCheckTask`, `CosmeticIsolationCheckTask`.
- `game-core/build.gradle.kts` wires `reportRoot` for both.
- Applies to `ValidateDocsTask`, `LayeringCheckTask`, `PackageRootCheckTask`, `SourceTrackingCheckTask`, `VersionCatalogCheckTask` — none currently reads `project` in its action, and none should start.
