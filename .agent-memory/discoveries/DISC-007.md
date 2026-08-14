# DISC-007: the sandbox cannot provision a JDK 17 toolchain; api.foojay.io is blocked

**Date:** 2026-08-08
**Category:** discoveries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.1, docs/12_testing_validation_ci.md#D12-S5.4

**Status:** active

## Summary
In the remote agent sandbox the only JDK is 21, and the foojay resolver that would download the pinned JDK 17 (D02-R1) cannot reach `api.foojay.io` — the egress proxy answers the CONNECT with 403. Every Gradle invocation then fails during configuration with a message about the configuration cache, not about the toolchain.

## Details

**Symptom:**

```
Configuration cache state could not be cached: field `javaCompiler` of task
`:shared-models:compileJava` ... error writing value of type 'DefaultProperty'
> Cannot find a Java installation on your machine matching: {languageVersion=17 ...}
  Some toolchain resolvers had internal failures: foojay (Unable to tunnel through
  proxy. Proxy returns "HTTP/1.1 403 Forbidden").
```

The headline is the configuration cache, which is a red herring: the cache fails because serialising the compile task forces toolchain resolution, and resolution is what actually failed. Turning the configuration cache off does not help — it moves the same failure to task execution.

**Workaround used to verify this session's work** (local only, never committed):

```kotlin
// scratchpad/jdk21.init.gradle.kts — pass with -I
gradle.projectsEvaluated {
    allprojects {
        plugins.withId("java") {
            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
}
```

`projectsEvaluated` matters: `syndicate.java-conventions` sets the toolchain when the plugin is applied, so an init script that configures it earlier is simply overwritten. `options.release` stays 17, so the bytecode target is unchanged — this substitutes the compiler, not the target.

Maven Central is reachable from the sandbox, so dependencies resolve normally. JitPack is not, which is what keeps `test-environment` (gdx-gltf, DEV-001) from compiling here at all.

## Rationale / Context
The error names the configuration cache and a serialisation failure, so the obvious first moves — disable the cache, clear `.gradle/`, re-run with `--no-configuration-cache` — all cost time and none of them work. Without this entry the next session in this environment spends the same twenty minutes before finding that the real problem is one blocked host.

## Impact
Local verification of every JVM module in the remote sandbox. Nothing about the project's own configuration is wrong: CI provisions JDK 17 normally, and D02-R1's pin stays as it is.

**Use the workaround before every push, not only when a JVM module changed.** `:memory-system:lintMemory` is a CI gate (D12-S5.4 stage 0) and it fails on prose — word counts, table shapes, a hand-edited `INDEX.md` — so a session that touched nothing but `.agent-memory/` can still turn CI red. Two consecutive sessions read this entry's headline as "the JVM tasks cannot run here", skipped the lint, and shipped violations; the second one then had to fix the first one's as well. `./gradlew -I <init script> :memory-system:lintMemory :memory-system:regenerateIndex` takes about ninety seconds.
