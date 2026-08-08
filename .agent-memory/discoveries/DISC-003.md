# DISC-003: bpy module teardown segfaults, overwriting the tool's exit code

**Date:** 2026-08-08
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S4.3

**Status:** active

## Summary
When the tool runs on the `bpy` PyPI module (DEV-002), normal interpreter shutdown after a run that loaded and freed meshes segfaults inside Blender's own teardown. The process then exits 139 regardless of what the tool decided, destroying the exit-code contract D09-S4.3 exists to provide. The entry point calls `os._exit` after flushing instead.

## Details

**Symptom:** every run — successful or not — ended `Segmentation fault`, exit 139. The tool's JSON document was already written, so the failure looked like a crash *after* success, which is exactly the shape that gets ignored.

**Cause:** Blender's data-block teardown at interpreter exit, in the module host. It does not happen inside `blender --background`, which controls its own shutdown, so it is specific to the module invocation.

**Fix:** `__main__` flushes both streams and calls `os._exit(code)`, skipping interpreter shutdown. Nothing observable is lost: the JSON is already on the wire, the temp directory is removed by the pipeline's `finally`, and the OS reclaims everything else.

**Related trap, same class:** `VisualScene` in the harness read Bullet body velocities *after* the LWJGL3 application loop returned, which is after `dispose()` freed those bodies. That is a native use-after-free, and it presents as a JVM-level SIGSEGV in `btVector3_to_Vector3` rather than as any Java exception. Measurements are now snapshotted at capture time, before teardown (G19).

## Rationale / Context
Both bugs produce a crash at the very end of an otherwise correct run, which is the easiest kind to dismiss as environmental. Both destroy the process's exit code, which is the one thing an automation-first tool must get right.

## Impact
`blender-tool/syndicate_fracture/__main__.py`, `test-environment/.../render/VisualScene.java`. Any code holding a native handle past the owner's disposal has the same failure mode.
