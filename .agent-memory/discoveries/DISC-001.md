# DISC-001: Configuration cache rejects onlyIf lambdas reading a script-level val

**Date:** 2026-08-08
**Category:** discoveries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.6, docs/12_testing_validation_ci.md#D12-S5.4

**Status:** active

## Summary
With `org.gradle.configuration-cache=true`, an `onlyIf { }` spec in a `.gradle.kts` file fails to serialise if its body reads a top-level `val` or an unqualified `logger`. The fix is to copy the value into a local inside the task's configure block; the diagnostic never says this.

## Details

Both forms below fail with "cannot serialize Gradle script object references":

```kotlin
val ruffAvailable: Boolean = onPath("ruff")          // script-level property

tasks.register<Exec>("lint") {
    onlyIf { ruffAvailable }                          // FAILS
    onlyIf { logger.warn("..."); ruffAvailable }      // FAILS
}
```

The working form:

```kotlin
tasks.register<Exec>("lint") {
    val available = ruffAvailable                     // local in the configure block
    onlyIf { task -> if (!available) task.logger.warn("..."); available }
}
```

Cause: a script-level `val` compiles to a field on the generated script class, so a lambda reading it captures the script instance. `logger` resolves to the script's `Project.logger` for the same reason. A local declared inside the configure block is captured by value, and the configure block itself is never serialised — only the `onlyIf` spec is.

The error names the task and the type, never the captured symbol, so with several `onlyIf` blocks the offending line has to be found by bisection. `doLast` blocks hit the same rule; those in `blender-tool` already used locals and never failed, which is what isolated the cause.

## Rationale / Context
`blender-tool` is specified as `Exec` tasks with availability checks for Blender, ruff, and pytest (D02-S4.6, D02-E4), so this pattern recurs every time a tool-availability guard is added. Without this entry the next session loses time to a message that points at the task rather than at the capture, and the tempting fix — disabling the configuration cache — would slow every build in the repository to solve a three-character problem.

## Impact
`blender-tool/build.gradle.kts` and any future `Exec` task guarded by tool availability. Gradle 8.14.3.
