# DISC-024: jitpack.io is blocked by the sandbox proxy, so game-client cannot be built here

**Date:** 2026-08-12
**Category:** discoveries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.1, docs/12_testing_validation_ci.md#D12-S5.4

**Status:** active

## Summary
`game-client` cannot be compiled in the remote agent sandbox: its one JitPack-only dependency, `gdx-gltf` (DEV-001), is denied by the egress proxy with a 403 on CONNECT, and the artifact is not in the Gradle cache. Every other module builds and tests normally. The workaround is to compile the touched package directly with `javac` against the cached jars, which type-checks the code without resolving the unreachable dependency.

## Details
```
> Could not resolve com.github.mgsx-dev.gdx-gltf:gltf:2.3.0.
   > Could not GET 'https://jitpack.io/.../gltf-2.3.0.pom'.
      > Received status code 403 from server: Forbidden
```

`curl -sS "$HTTPS_PROXY/__agentproxy/status"` confirms it is policy rather than a transient failure:

```json
"recentRelayFailures": [
  { "kind": "connect_rejected",
    "detail": "gateway answered 403 to CONNECT (policy denial or upstream failure)",
    "host": "jitpack.io:443" }
]
```

This is the same shape of problem as DISC-007 (foojay blocked, so the pinned JDK 17 toolchain cannot be provisioned) and it has the same cause: the sandbox's allowlist covers the standard package registries and not the long tail. Unlike DISC-007 it has **no local workaround at the Gradle level** — there is no toolchain to override, the artifact simply is not obtainable.

The session that needed this was an audio session touching `AudioSystem`, `SoundBank` and `LocalPlayer`, none of which import gdx-gltf. Building a classpath by hand out of the cache is enough to type-check them:

```
CP=$(find ~/.gradle/caches/modules-2/files-2.1 \
       -name "gdx-1.14.2.jar" -o -name "gdx-bullet-1.14.2.jar" \
       -o -name "slf4j-api-*.jar" -o -name "gdx-controllers-*.jar" | tr '\n' ':')
javac -proc:none -d /tmp/out -cp "$CP:game-core/build/classes/java/main:shared-models/build/classes/java/main" \
      game-client/src/main/java/dev/syndicate/client/audio/*.java \
      game-client/src/main/java/dev/syndicate/client/LocalPlayer.java
```

That caught three real defects a review had missed: a `Gdx.graphics.getDeltaTime()` where the frame delta was already a parameter, an `AssetId.of("")` that throws rather than resolving to nothing, and a sound id reconstructed with a key for the one sound in the bank that has none.

## Rationale / Context
The trap is believing a client change is verified because `./gradlew check` was green — it is green because the client's tasks never ran, not because they passed. A session that edits `game-client` here and reports success on a whole-project check has verified nothing about the code it wrote.

Whether the client's tests pass is genuinely unknown after such a session, and CI (which can reach JitPack) is the first place it will be established. Say so rather than implying otherwise.

## Impact
- Any session touching `game-client` in this sandbox: use the `javac` recipe above, and state plainly that the module was not built by Gradle and its tests were not run.
- CI is unaffected — it resolves JitPack normally.
- Whether the block is permanent or a policy that can be widened is not known from inside the sandbox.
