<!-- D03-S0 --># 03 — Runtime Modes

**Document ID:** D03
**Owns:** Runtime mode definitions, mode capability matrix, launch configuration, startup sequence, main loops.

---

<!-- D03-S1 -->## 1. Purpose

This document specifies the four runtime modes the software can boot into — local client, single-player, hosted multiplayer (listen server), and dedicated headless server — as compositions of one shared simulation. It fixes what runs in each mode, what is disabled, how a mode is selected, and the exact startup and main-loop sequences.

The governing idea: **there is one game, configured four ways.** There is no separate single-player codebase, no "offline mode" fork, and no headless-only reimplementation (G17).

Requirements are numbered `R1..Rn`, cited as `D03-R6`.

---

<!-- D03-S2 -->## 2. Scope

<!-- D03-S2.1 -->### 2.1 In Scope

- The four runtime modes and their capability matrix.
- The mode-selection rules and precedence.
- The launch configuration interface: CLI flags, config file, environment variables.
- Startup sequence for each mode (pseudo code).
- Client render loop vs authority tick loop, and their relationship.
- Which systems, resources, and subsystems are disabled per mode.
- Shutdown sequence.

<!-- D03-S2.2 -->### 2.2 Non-Goals

- **NG1.** Module structure and dependencies — `docs/02_technical_architecture.md#D02-S4.5`.
- **NG2.** The system catalogue itself — `docs/04_entity_component_model.md#D04-S4.4`.
- **NG3.** Network protocol and message flow — `docs/10_networking_multiplayer.md#D10-S5`.
- **NG4.** Match rules — `docs/01_product_game_design.md#D01-S4.2`.
- **NG5.** The verification harness's own modes — `docs/14_test_environment.md#D14-S5.11`/`#D14-S5.13`. The harness is a separate executable and is not a game runtime mode.
- **NG6.** Hosting, orchestration, or container images.

---

<!-- D03-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S5.2` | G2 (fixed tick), G17 (headless parity), G1 (single authority) |
| `docs/02_technical_architecture.md#D02-S5.3` | Process model and transport pairing |
| `docs/02_technical_architecture.md#D02-S5.4` | Bootstrap skeleton this document expands |
| `docs/04_entity_component_model.md#D04-S4.4` | The system list the mode filter selects from |
| `docs/06_physics_simulation.md#D06-S5.4` | The accumulator contract the loops implement |
| `docs/10_networking_multiplayer.md#D10-S5.8` | Connection lifecycle used by client/server modes |

---

<!-- D03-S4 -->## 4. Data Contracts

<!-- D03-S4.1 -->### 4.1 Runtime Mode Matrix

**R1.** Exactly four runtime modes exist (`RuntimeMode` enum). `TEST_RANGE` and the other `GameMode`s (D01-S4.2) are *content* configurations that run inside one of these four.

| Capability | `LOCAL_CLIENT` (joining) | `SINGLE_PLAYER` | `HOSTED_MULTIPLAYER` | `DEDICATED_SERVER` |
|---|---|---|---|---|
| Executable | `syndicate-client` | `syndicate-client` | `syndicate-client` | `syndicate-server` |
| Window + GL context | Yes | Yes | Yes | **No** |
| Rendering systems (D04-S4.4 #22–26) | Yes | Yes | Yes | **No** |
| Audio | Yes | Yes | Yes | **No** |
| Input devices | Yes | Yes | Yes | **No** (console commands only) |
| Is authority (G1) | **No** | Yes | Yes | Yes |
| Physics simulation | Yes (predicted) | Yes (authoritative) | Yes (authoritative) | Yes (authoritative) |
| Damage/fracture/detach systems | **No** (applies replicated state) | Yes | Yes | Yes |
| AI bots | No | Yes | Yes | Yes |
| Client prediction + reconciliation | Yes | No (loopback, zero latency) | Yes for remote peers only | No |
| Interpolation of remote entities | Yes | No | Yes | No |
| Accepts network connections | No | No | Yes | Yes |
| Transport | `KryoClientTransport` | `LoopbackTransport` | `KryoServerTransport` + loopback | `KryoServerTransport` |
| Loads render assets (textures, shaders, morph geometry) | Yes | Yes | Yes | **No** |
| Loads collision/gameplay assets | Yes | Yes | Yes | Yes |
| Main loop driver | Render loop (vsync-bounded) | Render loop | Render loop | Tick loop (sleep-bounded) |
| Tick source | Local prediction clock, corrected by server | Local authoritative clock | Local authoritative clock | Local authoritative clock |
| Cosmetic systems (effects, morph visuals) | Yes | Yes | Yes | **No** (G6: cosmetic state has no authoritative effect, so omitting it changes nothing) |

**R2.** In every mode, the simulation advances only at `TICK_RATE_HZ` in steps of `TICK_DT` (G2). Rendering rate is independent and never influences simulation results.

**R3.** `SINGLE_PLAYER` is `HOSTED_MULTIPLAYER` with zero remote peers and a loopback transport. It is not a distinct code path (D02-R19). This is what guarantees single-player exercises the replication code that multiplayer depends on.

<!-- D03-S4.2 -->### 4.2 Launch Configuration Schema

**R4.** `LaunchConfig` is the single configuration object produced at startup. Every field is listed; there is no hidden configuration.

| Field | Type | Default | CLI flag | Env var | Meaning |
|---|---|---|---|---|---|
| `mode` | `RuntimeMode` | derived (D03-S5.2) | `--mode` | `SYNDICATE_MODE` | Runtime mode |
| `headless` | bool | true for server exe | `--headless` | `SYNDICATE_HEADLESS` | Disable all rendering |
| `serverHost` | string | — | `--connect <host>` | — | Join target; implies `LOCAL_CLIENT` |
| `serverPort` | int | 27015 | `--port` | `SYNDICATE_PORT` | Listen or connect port |
| `maxPlayers` | int | 12 | `--max-players` | — | Server capacity |
| `gameMode` | `GameMode` | `DEATHMATCH` | `--game-mode` | — | D01-S4.2 |
| `arenaId` | AssetId | `arena_scrapyard_01` | `--arena` | — | Arena to load |
| `botCount` | int | 0 (7 in `SINGLE_PLAYER`) | `--bots` | — | Bots to add |
| `botDifficulty` | `BotDifficulty` | `NORMAL` | `--bot-difficulty` | — | D11-S4.2 |
| `matchSeed` | long | random | `--seed` | `SYNDICATE_SEED` | Gameplay RNG seed (G4) |
| `assetRoot` | path | `./assets` | `--assets` | `SYNDICATE_ASSETS` | Asset directory |
| `strictAssets` | bool | true in CI, false locally | `--strict-assets` | `SYNDICATE_STRICT_ASSETS` | Asset validation severity (D08-S5.4) |
| `tickRateHz` | int | 60 | — | — | **Read-only.** Present for logging; changing it is prohibited by G2. |
| `snapshotRateHz` | int | 20 | `--snapshot-rate` | — | Server send rate (D10-S5.3) |
| `logLevel` | enum | INFO | `--log-level` | `SYNDICATE_LOG_LEVEL` | TRACE..ERROR |
| `logFile` | path | none | `--log-file` | — | Also log to file |
| `vsync` | bool | true | `--vsync` | — | Client only |
| `maxFps` | int | 0 (unbounded) | `--max-fps` | — | Client only |
| `windowWidth`/`windowHeight` | int | 1600 / 900 | `--width`/`--height` | — | Client only |
| `fullscreen` | bool | false | `--fullscreen` | — | Client only |
| `configFile` | path | `./syndicate.conf` if present | `--config` | `SYNDICATE_CONFIG` | See precedence |
| `profile` | bool | false | `--profile` | — | Enable per-system profiling |
| `deterministicMode` | bool | false | `--deterministic` | — | Disables all unseeded RNG and wall-clock reads; used by tests (D12-S5.2) |
| `autoStart` | bool | true for server | `--auto-start` | — | Start the match without waiting in LOBBY |
| `timeLimitSeconds` | int | mode default | `--time-limit` | — | Overrides `MatchRules.timeLimitTicks` |
| `adminConsole` | bool | true for server | `--console` | — | stdin command console |

**R5. Precedence** (later wins): built-in defaults → config file → environment variables → CLI flags. Every effective value is logged at startup at INFO, with its source, so a misconfiguration is always diagnosable from the log alone.

**R6.** Unknown CLI flags are a **fatal error**, not a warning. A typo'd flag silently ignored is how a server ends up running with the wrong tick rate for a week.

<!-- D03-S4.3 -->### 4.3 Configuration File Format

```
# syndicate.conf — key=value, '#' comments, keys match LaunchConfig field names
mode=DEDICATED_SERVER
port=27015
max-players=12
game-mode=TEAM_DEATHMATCH
arena=arena_scrapyard_01
bots=4
bot-difficulty=HARD
snapshot-rate=20
log-level=INFO
strict-assets=true
```

**R7.** Keys use the CLI flag name without `--`. An unknown key is fatal (same rationale as R6).

<!-- D03-S4.4 -->### 4.4 Exit Codes

**R8.** The game executables use these exit codes. They are distinct from the harness's (D14-S4.2) and the Blender tool's (D09-S4.3), because they are different programs; the ranges do not overlap in meaning.

| Code | Name | Cause |
|---|---|---|
| 0 | `OK` | Clean shutdown |
| 64 | `USAGE` | Unknown flag, bad value, contradictory flags |
| 66 | `ASSETS_NOT_FOUND` | Asset root missing or unreadable |
| 67 | `ASSETS_INVALID` | Asset validation failed in strict mode |
| 69 | `MODE_UNAVAILABLE` | Rendering mode requested with no display |
| 74 | `PORT_IN_USE` | Server could not bind |
| 76 | `CONNECT_FAILED` | Client could not reach or handshake with the server |
| 78 | `NATIVES_MISSING` | Bullet natives unavailable for this platform |
| 70 | `INTERNAL_ERROR` | Unhandled exception during startup or shutdown |

---

<!-- D03-S5 -->## 5. Logic & Algorithms

<!-- D03-S5.1 -->### 5.1 Startup Sequence

```pseudo
function main(argv):
    # ---- 1. Configuration -------------------------------------------------
    try:
        config = LaunchConfig.resolve(argv)          # defaults < file < env < flags (R5)
    catch UnknownOption | BadValue as e:
        printUsage(e); exit(64)                      # USAGE

    Logging.configure(config.logLevel, config.logFile)
    log.info("effective configuration:"); for (k, v, source) in config.entries(): log.info(...)

    # ---- 2. Mode resolution and validation --------------------------------
    config.mode = resolveMode(config)                # D03-S5.2
    validateModeCombination(config)                  # e.g. --connect with --bots is USAGE
    if config.mode.requiresDisplay and not displayAvailable():
        log.error("no display available; use --mode DEDICATED_SERVER or --headless")
        exit(69)                                     # MODE_UNAVAILABLE

    # ---- 3. Native initialisation ------------------------------------------
    try: Bullet.init(useRefCounting = false)         # exactly once (D02-R3)
    catch UnsatisfiedLinkError: exit(78)             # NATIVES_MISSING
    if config.profile or isDebugBuild(): NativeResourceTracker.install()

    # ---- 4. Application shell ----------------------------------------------
    # The libGDX Application is created BEFORE assets, because asset loading needs
    # Gdx.files. Headless mode uses HeadlessApplication, which provides files and
    # the app lifecycle with no GL context (G17).
    listener = new SyndicateApplicationListener(config)
    app = config.headless
            ? new HeadlessApplication(listener, headlessConfig(config.tickRateHz))
            : new Lwjgl3Application(listener, lwjgl3Config(config))
    return app.exitCode()

function SyndicateApplicationListener.create():
    # ---- 5. Assets ----------------------------------------------------------
    assetIndex = AssetIndexLoader.load(config.assetRoot)         # exit 66 if missing
    result = AssetValidator.validate(assetIndex, renderAssets = not config.headless)
    if result.hasErrors:
        if config.strictAssets: log.error(result.report()); exit(67)
        else: log.warn(result.report()); substituteFallbacks(result)   # G18

    # ---- 6. World and systems ----------------------------------------------
    world   = WorldFactory.create(config, assetIndex)             # D04-S5.4
    systems = SystemSetFactory.forMode(config.mode)               # D03-S5.2
    world.registerSystems(systems)                                # fixed order (D04-S4.4)

    # ---- 7. Transport -------------------------------------------------------
    transports = buildTransportPair(config.mode)                  # D02-S5.3
    if config.mode.isAuthority:
        try: transports.serverSide.bind(config.serverPort, config.maxPlayers)
        catch BindException: exit(74)                             # PORT_IN_USE
    if config.mode == LOCAL_CLIENT:
        try: transports.clientSide.connect(config.serverHost, config.serverPort,
                                           timeout = 10 s)        # D10-S5.8 handshake
        catch ConnectFailed | ProtocolMismatch: exit(76)          # CONNECT_FAILED

    # ---- 8. Match bootstrap -------------------------------------------------
    if config.mode.isAuthority:
        MatchFactory.configure(world, config)                     # rules, arena, seed
        if config.botCount > 0: BotFactory.fill(world, config.botCount, config.botDifficulty)
        if config.autoStart: world.match.requestPhase(COUNTDOWN)
    if not config.headless:
        RenderContext.initialize(world, config)                   # camera, HUD, debug draw

    # ---- 9. Loop ------------------------------------------------------------
    loop = config.headless ? new HeadlessLoop(world, transports)  # D03-S5.4
                           : new ClientLoop(world, transports)    # D03-S5.3
    loop.start()
```

<!-- D03-S5.2 -->### 5.2 Mode Selection and System Sets

```pseudo
function resolveMode(config):
    if config.mode was explicitly set:               return config.mode      # explicit wins
    if config.serverHost != null:                    return LOCAL_CLIENT
    if executableIs("syndicate-server"):             return DEDICATED_SERVER
    if config.maxPlayers > 1 and config.listen:      return HOSTED_MULTIPLAYER
    return SINGLE_PLAYER                             # the client's default

function validateModeCombination(config):
    fatalIf(config.mode == LOCAL_CLIENT and config.botCount > 0,
            "--bots is meaningless when joining a server; bots are the authority's")
    fatalIf(config.mode == DEDICATED_SERVER and not config.headless,
            "dedicated server cannot render")
    fatalIf(config.mode != LOCAL_CLIENT and config.serverHost != null,
            "--connect implies LOCAL_CLIENT")
    fatalIf(config.headless and config.fullscreen, "contradictory display options")
    # every fatalIf exits 64 (USAGE) with the message

function SystemSetFactory.forMode(mode):
    # Start from the full ordered catalogue (D04-S4.4) and filter. Order is preserved;
    # systems are ABSENT, not disabled (D04-R8), so a mode cannot accidentally run one.
    s = []
    if mode.hasInput:      s += [InputCollectionSystem]                       # 1
    if mode.isAuthority:   s += [InputReceiveSystem, BotDecisionSystem,       # 2,3
                                 MatchFlowSystem, SpawnSystem]                # 4,5
    s += [VehicleStatsSystem, VehicleControlSystem]                           # 6,7
    if mode.isAuthority:   s += [WeaponSystem, ProjectileSystem]              # 8,9
    else:                  s += [PredictedWeaponSystem, PredictedProjectileSystem]
                                # client-side prediction variants; they never author
                                # authoritative damage (G15) — they only spawn local
                                # visual projectiles that are reconciled on confirmation
    s += [PhysicsSystem]                                                      # 10
    if mode.isAuthority:   s += [CollisionEventSystem, DamageSystem,          # 11,12
                                 FractureSystem, DetachSystem]                # 13,14
    s += [MassPropertySystem, LifetimeSystem]                                 # 15,16
    if mode.isAuthority:   s += [ScoreSystem, NetworkSendSystem]              # 17,18
    if mode.isClient:      s += [NetworkReceiveSystem, ReconciliationSystem]  # 19,20
    s += [TransformSystem]                                                    # 21
    if mode.renders:       s += [InterpolationSystem, DamageVisualSystem,     # 22,23
                                 EffectSystem, AudioSystem, RenderSystem]     # 24,25,26
    s += [EntityDestroySystem]                                                # 27
    assert isSubsequenceOf(s, FULL_CATALOGUE_ORDER)   # G3: filtering never reorders
    return s

MODE PROPERTIES:
    LOCAL_CLIENT        : hasInput=Y isAuthority=N isClient=Y renders=Y headless=N
    SINGLE_PLAYER       : hasInput=Y isAuthority=Y isClient=Y renders=Y headless=N
    HOSTED_MULTIPLAYER  : hasInput=Y isAuthority=Y isClient=Y renders=Y headless=N
    DEDICATED_SERVER    : hasInput=N isAuthority=Y isClient=N renders=N headless=Y
```

**R9.** In `SINGLE_PLAYER` and `HOSTED_MULTIPLAYER`, the local player is both authority and client. `ReconciliationSystem` is present but is a no-op for loopback peers (zero latency, nothing to reconcile); it still runs for remote peers in `HOSTED_MULTIPLAYER`.

<!-- D03-S5.3 -->### 5.3 Client Loop (Rendering Modes)

```pseudo
class ClientLoop:
    double accumulator = 0
    TickNumber tick = 0
    double MAX_FRAME_DT = 0.25          # clamp: never try to catch up more than 15 ticks

    function render(frameDeltaSeconds):          # called by libGDX once per frame
        dt = min(frameDeltaSeconds, MAX_FRAME_DT)
        accumulator += dt

        transports.pump()                        # drain sockets into message queues

        steps = 0
        while accumulator >= TICK_DT and steps < MAX_CATCHUP_TICKS (15):
            world.tick(tick)                     # D04-S5.3: all non-PRESENT systems
            accumulator -= TICK_DT
            tick += 1
            steps += 1

        if accumulator >= TICK_DT:               # still behind after the cap
            log.warn("dropping {} ticks of simulation debt", floor(accumulator / TICK_DT))
            accumulator = accumulator mod TICK_DT     # G2 preserved: we drop time,
                                                      # we never lengthen a step

        alpha = accumulator / TICK_DT            # [0,1) render interpolation factor
        runPresentSystems(world, alpha)          # systems 22–26, once per frame
        transports.flush()

    # R10. The simulation NEVER runs with a variable dt. Frame time only decides how
    #      many fixed steps happen, never how long a step is. This is the whole point
    #      of G2 and is what makes client and server agree.
    # R11. `alpha` is used only by PRESENT systems for visual interpolation. No
    #      gameplay system may read it.
```

<!-- D03-S5.4 -->### 5.4 Headless Loop (Dedicated Server)

```pseudo
class HeadlessLoop:
    function start():
        nextTickNanos = nanoTime()
        while running:
            now = nanoTime()
            if now < nextTickNanos:
                sleepUntil(nextTickNanos)        # coarse sleep + short spin for accuracy
                continue

            transports.pump()
            world.tick(tick)                     # identical call to the client's
            tick += 1
            nextTickNanos += TICK_DT_NANOS

            if adminConsole.hasCommand(): adminConsole.execute(world)

            # Overload handling: if we are more than OVERLOAD_TICKS (30) behind,
            # skip forward rather than spiral. Clients see it as a lag spike, which
            # is recoverable; a death spiral is not.
            behind = (nanoTime() - nextTickNanos) / TICK_DT_NANOS
            if behind > OVERLOAD_TICKS:
                log.error("server overloaded: {} ticks behind, resyncing clock", behind)
                metrics.increment("server.overload")
                nextTickNanos = nanoTime() + TICK_DT_NANOS

            metrics.record("tick.duration", tickDurationNanos)

    # R12. The server never renders, never loads a texture, and never creates a GL
    #      context. Systems 22–26 are absent from its schedule (D03-S5.2), so this is
    #      structurally guaranteed rather than merely intended (G17).
```

<!-- D03-S5.5 -->### 5.5 Disabled Resources in Headless Mode

**R13.** The following are not loaded, created, or referenced when `headless = true`. `AssetIndexLoader` is told which classes of asset to skip; skipping is explicit, not incidental.

| Resource | Headless behaviour |
|---|---|
| Textures, materials, shaders | Not loaded. Part records carry the asset *path* but no handle. |
| Render meshes (visual LODs) | Not loaded. |
| **Morph target geometry** | Not loaded. Morph weights are cosmetic (G6), so the server has nothing to drive. |
| Collision geometry | **Loaded.** Required for simulation. |
| Shard meshes' hulls | **Loaded.** Required for fracture bodies. |
| Audio banks | Not loaded. |
| Particle definitions | Not loaded. |
| Fonts, UI atlases | Not loaded. |
| Arena visual geometry | Not loaded; arena collision geometry is loaded. |

**R14.** An asset record that is missing its render handle in headless mode must never be dereferenced. `RenderModelComponent` does not exist in `game-core`, so this is enforced by module boundaries (D02-S4.5) rather than by null checks.

<!-- D03-S5.6 -->### 5.6 Shutdown Sequence

```pseudo
function shutdown(reason):
    log.info("shutting down: {}", reason)
    if isAuthority:
        broadcast(DisconnectMessage(reason))     # tell clients why, before closing
        transports.serverSide.flushAndClose(timeout = 2 s)
    else:
        transports.clientSide.sendDisconnect(reason); close()

    world.match.requestPhase(RESULTS) if inProgress    # emit final scores to logs/metrics
    loop.running = false

    # Teardown order matters for natives (D02-S5.7).
    world.disposeSystems()          # each system releases what it owns, reverse order
    world.disposeEntities()         # runs the destroy queue to completion
    PhysicsWorld.dispose()          # constraints -> bodies -> shapes -> world
    ShapeCache.disposeAll()
    if not headless: RenderContext.dispose(); AssetRegistry.disposeRenderAssets()
    assert NativeResourceTracker.outstanding() == 0     # else log the census at ERROR
    Logging.flush()
    exit(codeFor(reason))

TRIGGERS:
    - Window close / Ctrl-C (SIGINT)  -> reason = USER_REQUESTED, code 0
    - Admin console "quit"            -> reason = ADMIN, code 0
    - Fatal startup failure           -> reason-specific code (D03-S4.4)
    - Unhandled exception in a system -> log the tick number, entity, and system;
                                         attempt a clean shutdown; code 70
```

<!-- D03-S5.7 -->### 5.7 Admin Console (Dedicated Server)

```pseudo
COMMANDS (read from stdin, one per line; all are authority-side and audited):
    status                      players, tick, phase, uptime, ticks-behind
    players                     list peers with ping, packet loss, score
    kick <playerId> [reason]
    say <text>                  broadcast a server message
    map <arenaId>               end the match and load a new arena
    mode <gameMode>             set the next match's mode
    bots <n> [difficulty]       add/remove bots
    restart                     restart the current match with a new seed
    seed                        print the current match seed (for reproducing a bug)
    profile on|off              toggle per-system timing output
    tickstats                   min/mean/p99 tick duration over the last 600 ticks
    quit                        graceful shutdown

R15. Console commands take effect at a tick boundary, never mid-tick. They are queued
     and applied at the start of the next tick's PRE_SIM phase, so they cannot corrupt
     simulation state mid-step.
R16. Every command is logged with a timestamp and the resulting state change.
```

---

<!-- D03-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D03-1.** All four `RuntimeMode` values boot successfully with default configuration.
- [ ] **AC-D03-2.** `DEDICATED_SERVER` boots and runs a full match on a host with no display, no GL driver, and no audio device.
- [ ] **AC-D03-3.** The system schedule for each mode is a subsequence of the full catalogue order (D04-S4.4), verified by a test per mode.
- [ ] **AC-D03-4.** No rendering system appears in the `DEDICATED_SERVER` schedule.
- [ ] **AC-D03-5.** Configuration precedence follows R5 exactly; every effective value is logged with its source.
- [ ] **AC-D03-6.** An unknown CLI flag or config key exits 64 with a message naming it.
- [ ] **AC-D03-7.** Every exit code in D03-S4.4 is produced by its stated cause, verified by a test per code.
- [ ] **AC-D03-8.** The simulation advances only in `TICK_DT` steps in all modes; no code path passes a variable dt to a gameplay system (grep + test).
- [ ] **AC-D03-9.** At 30 FPS and at 240 FPS, an identical scripted input sequence produces identical simulation state at tick 600.
- [ ] **AC-D03-10.** A frame stall of 2 s produces at most `MAX_CATCHUP_TICKS` steps, then drops the remaining debt with a warning.
- [ ] **AC-D03-11.** Headless mode loads no texture, shader, audio, or morph-geometry resource (verified by instrumenting the asset loader).
- [ ] **AC-D03-12.** `SINGLE_PLAYER` and `HOSTED_MULTIPLAYER` with one local player produce identical state for identical inputs and seed.
- [ ] **AC-D03-13.** Shutdown leaves `NativeResourceTracker.outstanding() == 0` in every mode.
- [ ] **AC-D03-14.** Admin console commands apply at tick boundaries only.
- [ ] **AC-D03-15.** Server overload beyond `OVERLOAD_TICKS` resyncs the clock rather than spiralling, and logs the event.

---

<!-- D03-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | Client requests rendering with no display | Exit 69 with guidance to use `--headless`/`--mode DEDICATED_SERVER`. |
| E2 | Frame time spikes to 2 s (alt-tab, GC pause) | Clamp to `MAX_FRAME_DT`, run at most `MAX_CATCHUP_TICKS`, drop the rest, warn once per occurrence. Never lengthen `TICK_DT` (G2). |
| E3 | Server cannot keep up sustainedly | Log at ERROR each overload, expose `server.overload` metric, resync the clock. Do not silently reduce tick rate. |
| E4 | `--connect` given alongside `--bots` | Exit 64: bots belong to the authority. |
| E5 | Port already bound | Exit 74 naming the port. |
| E6 | Handshake protocol mismatch | Exit 76 with both protocol hashes (D10-S5.8). |
| E7 | Asset validation errors, `strictAssets = false` | Log warnings, substitute fallback assets, continue (G18). Fallbacks are visually obvious (magenta) so they are never mistaken for content. |
| E8 | Asset validation errors, `strictAssets = true` | Exit 67 with the full validation report. |
| E9 | Bullet natives missing | Exit 78 naming the platform triple. |
| E10 | Unhandled exception inside a system mid-tick | Log tick number, system name, entity ID, and stack; attempt clean shutdown; exit 70. Never continue with a half-stepped world. |
| E11 | All players disconnect from a dedicated server | Server stays up, returns to LOBBY, keeps bots if configured. Never exits on its own. |
| E12 | Client's tick clock drifts from the server's | Reconciliation corrects it (D10-S5.5); a drift beyond `MAX_TICK_DRIFT` (120 ticks) triggers a full resync. |
| E13 | `--deterministic` set but a system reads wall-clock time | Debug assertion fires naming the call site (G5). |
| E14 | Headless mode dereferences a render handle | Impossible: render components are absent from `game-core` (R14). A compile error, not a runtime one. |
| E15 | SIGINT during match | Graceful shutdown path (D03-S5.6): clients are told, natives are released, exit 0. |
| E16 | Config file present but unreadable | Exit 64 naming the path and the IO error. A silently ignored config file is worse than a failure. |
| E17 | `vsync` on with `maxFps` set | `maxFps` wins; log the resolution. Not an error. |
| E18 | Two `--mode` flags given | Last wins (standard CLI semantics), logged at WARN. |

---

<!-- D03-S8 -->## 8. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D03-1 | Boot each of the four modes with defaults | All reach `ACTIVE` phase (or LOBBY where applicable) |
| T-D03-2 | Dedicated server in a container with no X11/GL/audio | Full 60 s bot match completes, exit 0 |
| T-D03-3 | Assert schedule per mode against D03-S5.2 | Exact match; subsequence property holds |
| T-D03-4 | Run client at capped 30 FPS and at 240 FPS, same scripted inputs, same seed | Identical state at tick 600 |
| T-D03-5 | Inject a 2 s frame stall | ≤ 15 ticks stepped, warning logged, no `TICK_DT` change |
| T-D03-6 | Pass `--nonsense` | Exit 64, message names `--nonsense` |
| T-D03-7 | Set the same key in file, env, and flag | Flag wins; log shows source `CLI` |
| T-D03-8 | Bind an occupied port | Exit 74 |
| T-D03-9 | Connect to a nonexistent host | Exit 76 after the 10 s timeout |
| T-D03-10 | Corrupt one part asset, `--strict-assets` | Exit 67 with the validation report |
| T-D03-11 | Same, without strict | Warning + magenta fallback; match runs |
| T-D03-12 | Instrument the asset loader in headless mode | Zero texture/shader/audio/morph loads; collision loads present |
| T-D03-13 | Single-player vs hosted-with-one-player, same seed and inputs | Identical authoritative state at every tick |
| T-D03-14 | Ctrl-C mid-match on the server | Clients receive a disconnect reason; exit 0; zero outstanding natives |
| T-D03-15 | Issue `bots 4` on the console mid-tick-heavy load | Applied at the next tick's PRE_SIM; no mid-tick mutation |
| T-D03-16 | Force 40 ticks of server overload | Clock resync, ERROR logged, metric incremented, no spiral |
| T-D03-17 | `--deterministic` with an artificial `System.currentTimeMillis()` call in a system | Assertion fires naming the call site |
| T-D03-18 | Boot all modes 50 times | `NativeResourceTracker.outstanding() == 0` after each shutdown |

---

<!-- D03-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| Fixed tick and headless parity invariants | `docs/00_master_index.md#D00-S5.2` (G2, G17) |
| Reserved constants (`TICK_DT`, `TICK_RATE_HZ`) | `docs/00_master_index.md#D00-S6.4` |
| Game modes hosted by these runtime modes | `docs/01_product_game_design.md#D01-S4.2` |
| Process model and transport pairing | `docs/02_technical_architecture.md#D02-S5.3` |
| Bootstrap skeleton | `docs/02_technical_architecture.md#D02-S5.4` |
| Native disposal ordering | `docs/02_technical_architecture.md#D02-S5.7` |
| Full system catalogue and order | `docs/04_entity_component_model.md#D04-S4.4` |
| Tick loop internals | `docs/04_entity_component_model.md#D04-S5.3` |
| World construction | `docs/04_entity_component_model.md#D04-S5.4` |
| Accumulator and physics stepping | `docs/06_physics_simulation.md#D06-S5.4` |
| Asset index loading | `docs/08_asset_pipeline.md#D08-S5.3` |
| Asset validation severity | `docs/08_asset_pipeline.md#D08-S5.4` |
| Snapshot rate | `docs/10_networking_multiplayer.md#D10-S5.3` |
| Prediction and reconciliation | `docs/10_networking_multiplayer.md#D10-S5.5` |
| Connection lifecycle and handshake | `docs/10_networking_multiplayer.md#D10-S5.8` |
| Bot difficulty values | `docs/11_ai_bots_and_match_simulation.md#D11-S4.2` |
| Deterministic test harness usage | `docs/12_testing_validation_ci.md#D12-S5.2` |
| Headless smoke test | `docs/12_testing_validation_ci.md#D12-S5.5` |
