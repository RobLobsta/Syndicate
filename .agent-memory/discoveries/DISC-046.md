# DISC-046: game-client builds, tests and *runs* in the sandbox, under xvfb

**Date:** 2026-08-14
**Category:** discoveries
**Related Docs:** docs/02_technical_architecture.md#D02-S4.1, docs/12_testing_validation_ci.md#D12-S5.4, docs/03_runtime_modes.md#D03-S5.1

**Status:** active

Supersedes: DISC-024

## Summary
`game-client` compiles, its tests run, and the real client renders and writes a PNG — all inside the
development sandbox. DISC-024 recorded that none of this was possible because JitPack was blocked;
JitPack now resolves, and `xvfb-run` supplies the display the client was assumed not to have. The
project's largest standing structural risk is gone.

## Details
`./gradlew check validateDocs` is green with every `game-client` task executing, gdx-gltf resolving
from JitPack normally.

For anything visual, run the real client against a virtual display and photograph it:

```
xvfb-run -a -s "-screen 0 1600x900x24" ./gradlew :game-client:run \
  --args="--capture /tmp/shot.png --capture-frame 30 --start-screen GARAGE \
          --assets /absolute/path/to/assets"
```

`--start-screen` opens on `MAIN_MENU`, `GARAGE` or `MATCH` without a human navigating there;
`--auto-start` skips the menu entirely. GL is software (llvmpipe), so a match runs at about 4 fps —
that is the renderer being emulated, **not** a performance measurement, and no frame-rate claim may
be made from this environment.

## Rationale / Context
DISC-024's conclusion was load-bearing in a way a stale discovery usually is not: `ROADMAP.md` named
the environment — not the algorithm — as the largest risk in terrain rendering, and deferred the
project's biggest remaining piece of client work on that basis. A session that reads DISC-024 and
believes it will defer the same work again, and will report client changes as unverified when they
could have been photographed in a minute.

The general lesson is worth more than the specific fact: **a recorded environmental limitation has a
shelf life, and re-testing one costs a single command.** Check before planning around it.

## Impact
- DISC-024 is superseded. Client work is verifiable here; say so plainly rather than hedging.
- Terrain rendering (PROG-030 stage 2) is no longer environment-blocked.
- CI can capture the client's screens and fail on a visual regression; that is not yet wired up.
