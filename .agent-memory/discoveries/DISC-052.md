# DISC-052: gdx-gltf can be built from source when JitPack is blocked

**Date:** 2026-08-15
**Category:** discoveries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.1, docs/12_testing_validation_ci.md#D12-S5.4

**Status:** active

## Summary
`game-client`'s one JitPack-only dependency is 135 Java files depending on nothing but
`com.badlogicgames.gdx:gdx`, which is on Maven Central. When the egress proxy denies jitpack.io,
the module can be cloned from GitHub, compiled with `javac`, and served from a directory repository
added by a Gradle `--init-script`. No file in the repository changes.

## Details
DISC-024 recorded `game-client` as unbuildable in a blocked sandbox and DISC-046 recorded JitPack
working again; both are properties of a particular sandbox rather than of the project, and the
proxy's answer differs between sessions. This is the way that does not depend on the answer.

    git clone --depth 1 --branch 2.3.0 https://github.com/mgsx-dev/gdx-gltf
    javac -cp <gdx jar from the Gradle cache> -d classes $(find gltf/src -name '*.java')
    cp -r --parents $(find . -type f ! -name '*.java') classes/   # the shaders and the BRDF LUT
    jar cf gltf-2.3.0.jar -C classes .

plus a four-line POM naming the gdx dependency, and an init script whose `beforeSettings` adds the
directory to `dependencyResolutionManagement`. github.com is reachable through the session's git
proxy even when jitpack.io is not.

**The resources matter as much as the classes.** `gltf/src` carries the PBR shaders and
`brdfLUT.png` as non-Java files beside the sources; a jar of classes alone compiles, starts, and
renders every metal in the scene wrongly at grazing angles.

## Rationale / Context
Worth recording because the alternative — believing the client cannot be verified — cost a whole
session's worth of "compiles but unverified" claims. `game-client` has tests, a headless run under
xvfb, and a capture mode; none of it is reachable without this dependency, and all of it is
reachable with twenty minutes of work.

## Impact
- `check` ran green including `game-client` for the first time in this sandbox.
- Every visual claim in SESS-033 is a capture from the real client rather than an inference.
