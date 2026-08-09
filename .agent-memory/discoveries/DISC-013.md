# DISC-013: a test resolving LaunchConfig from System.getenv asserts the machine

**Date:** 2026-08-09
**Category:** discoveries
**Related Docs:** docs/03_runtime_modes.md#D03-S4.2, docs/12_testing_validation_ci.md#D12-S5.4

**Status:** active

## Summary
CI exports `SYNDICATE_STRICT_ASSETS=1` for the whole job. `ServerMain.run` resolved its configuration
from `System.getenv()`, so two `ServerMainTest` cases that assert the non-strict asset path passed
locally and failed on CI, expecting `OK` and getting `ASSETS_INVALID`.

## Details
**Reproduction**, from a clean tree:

```
SYNDICATE_STRICT_ASSETS=1 ./gradlew :game-server-headless:test --rerun-tasks
```

D03-R5 fixes the precedence as defaults < config file < environment < CLI flags. `strictAssets` is a
boolean flag with no negative form, so a test cannot override an inherited `SYNDICATE_STRICT_ASSETS`
from the command line — there is no `--no-strict-assets`. The environment wins, and the test asserts
whatever the machine exports.

**The fix** is a third `run(args, tickLimit, environment)` overload. The process passes
`System.getenv()`; every test passes `Map.of()`. `LaunchConfigResolver` already took the environment
as a constructor argument for exactly this reason — `ServerMain` was the only thing hard-coding it.

**Why it was invisible locally.** The sandbox exports neither variable, so the ambient environment
was empty and the tests were accidentally hermetic. Every environment-sensitive test in this
repository has the same property until something sets the variable, which makes CI the first place
it can fail and the last place it is convenient to debug.

**The wider point.** Any test that exercises a code path fed by `LaunchConfig` must supply its own
environment. There are seven environment keys in D03-S4.2 (`SYNDICATE_MODE`, `SYNDICATE_HEADLESS`,
`SYNDICATE_PORT`, `SYNDICATE_SEED`, `SYNDICATE_ASSETS`, `SYNDICATE_STRICT_ASSETS`,
`SYNDICATE_LOG_LEVEL`, `SYNDICATE_CONFIG`), and any of them set on a developer's machine or a runner
will silently change what such a test measures.

**Related, and correct.** A strict-mode server run against the real `assets/` tree now genuinely
exits 67, because the shipped parts reference `mesh.glb` files no reader can load yet (DEV-010) and
each reports A503. That is the intended behaviour of strict mode and not a bug; it is worth knowing
before someone sets `SYNDICATE_STRICT_ASSETS=1` in a launch script and concludes the content is
broken.

## Rationale / Context
The failure message is `expected: OK but was: ASSETS_INVALID` on a test whose name says it asserts
the degrade path — which reads as a bug in the degrade path, and the first instinct is to go looking
at `ServerRuntime.loadAssets`. Nothing there is wrong. Without this entry the next
environment-sensitive test fails the same way and costs the same detour.

## Impact
`game-server-headless` (`ServerMain`, `ServerMainTest`). Applies to every future test over
`LaunchConfig`, and to `game-client` when `ClientMain` grows past its bootstrap.
