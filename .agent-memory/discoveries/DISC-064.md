# DISC-064: Blender installs in the sandbox, and the executable host has never run

**Date:** 2026-08-17
**Category:** discoveries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.6, docs/09_blender_destruction_tool.md#D09-S5.2, docs/14_test_environment.md#D14-S7.3

**Status:** active

## Summary
Blender 4.2 LTS **installs in the development sandbox**, headless, in about ninety seconds
(`blender-tool/tools/install-blender.sh`). Every note saying a task "needs Blender, which the
sandbox does not have" was an untested assumption. Installing it exposed that the `blender`
**executable** host — the one D09-R1 specifies and `build.gradle.kts` prefers — cannot import the
tool at all, and fails **silently with exit 0**.

## Details
`download.blender.org` is reachable through the proxy, the sandbox is root with ~30 GB free, and the
tarball extracts to a working `blender --background`. The sandbox is ephemeral, so this is once per
session — hence a script, not a paragraph.

Three verified facts about the executable host:

1. **Blender's bundled Python ignores `PYTHONPATH` and the working directory.** `os.environ` shows
   the variable set; `sys.path` does not contain it — Blender builds its path from `PYTHONHOME`.
   `build.gradle.kts` relies on exactly this: `processFixtures` sets `PYTHONPATH` on the
   ProcessBuilder, `registerPreparation` relies on the working directory. Neither reaches
   `sys.path`, so `import syndicate_fracture` raises `ModuleNotFoundError`. The package path must
   be injected inside the `--python-expr` itself.

2. **`blender --background --python-expr` exits 0 on an uncaught exception.** Measured: a script
   whose only statement is `raise SystemError` exits 0. `--python-exit-code 1` makes it exit
   non-zero, and no invocation in `build.gradle.kts` passes it.

3. Together those make a **green build that produces nothing**. With Blender on PATH,
   `processFixtures` runs five fixtures, each fails to import, each exits 0, Gradle's
   `if (code != 0)` never fires, and the task reports success having written no output.
   `:test-environment:verifyFixtures` then reads an empty directory.

This stayed invisible because `blenderAvailable` was always false, so the tasks always skipped — and
because the `bpy` PyPI host (DEV-002), invoked as `python3 -m`, respects `sys.path` normally and is
what every successful fracture here was actually produced by. The shipped glass `shards.glb` record
`"blenderVersion": "4.2.0"`, the PyPI wheel's version, not a release tarball's.

The tool itself is fine. With the path injected it fractures `test_cube_1m` in 1.4 s, 12 shards,
`verificationPassed: true`.

## Rationale / Context
Two sessions of roadmap text treat "no Blender in the sandbox" as a fixed constraint deferring
structures, fracture manifests and fixture verification. It is not a constraint, and the cost of
testing it was one `curl`. The exit-0 behaviour is the dangerous half: a skipped task announces
itself, a task that runs, fails and reports success does not.

## Impact
- `blender-tool/tools/install-blender.sh` — pinned 4.2.13 LTS, idempotent, ~90 s.
- `build.gradle.kts` needs `--python-exit-code 1` and an in-expression `sys.path` insert on both
  `fractureCommand` and `prepareCommand` before the executable host is usable.
- CI may raise `SYNDICATE_REQUIRE_BLENDER` to 1 only after that, or a silent skip becomes a red
  build for the wrong reason.
