<!-- D11-S0 --># 11 — AI Bots and Match Simulation

**Document ID:** D11
**Owns:** Bot controller architecture, difficulty scaling, sensor model, navigation, decision loop, target and weapon selection, match state machine, offline headless match simulation.

---

<!-- D11-S1 -->## 1. Purpose

This document specifies the bots that fill single-player and under-populated multiplayer matches, and the match-flow machinery they run inside. It fixes how a bot perceives, decides, drives, aims, and fires; how difficulty scales; how navigation works in arena environments; the match phase state machine; and the offline headless simulation runner used for balance sweeps and regression testing.

The central constraint: **a bot produces exactly the same `InputCommand` a human produces** (D10-S4.2). Bots have no privileged access to state, no direct force application, and no special-case handling anywhere in the simulation.

Requirements are numbered `R1..Rn`, cited as `D11-R9`.

---

<!-- D11-S2 -->## 2. Scope

<!-- D11-S2.1 -->### 2.1 In Scope

- Bot controller architecture and its justification.
- Difficulty levels and the parameters they scale.
- Sensor model: what a bot perceives, with what delay and error.
- Navigation: navmesh generation, pathfinding, local steering, obstacle avoidance.
- The bot decision loop.
- Target selection and weapon selection, including damage-aware targeting.
- Match state machine: lobby → countdown → active → ending → results.
- Offline headless match simulation for balance and regression.

<!-- D11-S2.2 -->### 2.2 Non-Goals

- **NG1.** Machine-learned or trained agents. v1 uses an authored behaviour tree; ML is out of scope.
- **NG2.** Cooperative squad tactics beyond simple focus-fire and spacing.
- **NG3.** Bot chat, personality, or emotes.
- **NG4.** Navmesh *authoring* tools. The navmesh is generated offline from arena collision geometry by the asset pipeline.
- **NG5.** Dynamic navmesh rebuilding as debris accumulates — debris is avoided by local steering, not by re-pathing.
- **NG6.** Difficulty that cheats. Bots never get extra damage, extra health, or information a player could not have (D11-R6).

---

<!-- D11-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S5.2` | G3 (deterministic ordering), G4 (seeded randomness) |
| `docs/01_product_game_design.md#D01-S4.2` | Game modes the match machine implements |
| `docs/01_product_game_design.md#D01-S5.5` | Win condition evaluation |
| `docs/04_entity_component_model.md#D04-S4.4` | `BotDecisionSystem` (slot 3), `MatchFlowSystem` (slot 4) |
| `docs/05_vehicle_part_system.md#D05-S4.3` | Slot graph, used for part-aware targeting |
| `docs/06_physics_simulation.md#D06-S5.8` | The `BOT_DECISION` random stream |
| `docs/08_asset_pipeline.md#D08-S4.7` | Arena definition and navmesh asset |
| `docs/10_networking_multiplayer.md#D10-S4.2` | `InputCommand` structure bots produce |

---

<!-- D11-S4 -->## 4. Data Contracts

<!-- D11-S4.1 -->### 4.1 Bot Architecture

**R1.** Bots use a **behaviour tree** driving a **steering-based driving model**, with perception delivered through a delayed sensor snapshot.

| Option | Verdict | Reasoning |
|---|---|---|
| **Behaviour tree + steering** | **CHOSEN** | Behaviour trees are inspectable and tunable by designers, compose well for the small set of tactical decisions this game needs, and their state is a small serialisable struct — which matters because bot state must be deterministic and replayable (G3/G4). Steering behaviours map naturally onto throttle/steer/brake, which is exactly the output we need. |
| Finite state machine | Rejected | Adequate for v1's behaviour set, but transition count grows quadratically as behaviours are added, and this game will add behaviours. |
| Utility AI (scored actions) | Rejected for v1 | Better emergent behaviour, harder to make deterministic and to debug when a bot does something odd — and "why did the bot do that?" is a question we will ask constantly. |
| GOAP / planner | Rejected | Cost far exceeds the tactical depth of an arena vehicle fight. |
| Learned policy | Rejected | NG1. |

**R2.** The bot's only output is an `InputCommand`. `BotDecisionSystem` writes `PlayerInputComponent`; every downstream system is unaware the input came from a bot.

```
   PERCEIVE                DECIDE                     ACT
 ┌───────────┐      ┌──────────────────┐      ┌──────────────────┐
 │ Sensor    │─────►│ Behaviour tree   │─────►│ Steering solver  │──► InputCommand
 │ snapshot  │      │  (tactical goal) │      │  + aim solver    │    throttle/steer/
 │ (delayed) │      │                  │      │                  │    brake/aim/fire
 └───────────┘      └──────────────────┘      └──────────────────┘
      ▲                      │                         ▲
      │                      ▼                         │
   world state         Path request ────► Navmesh A* ──┘
   (delayed copy)
```

<!-- D11-S4.2 -->### 4.2 Difficulty Levels

**R3.** Four `BotDifficulty` values. Every difference is a *perception* or *execution* parameter — never a damage, health, or speed bonus (R6).

| Parameter | `EASY` | `NORMAL` | `HARD` | `BRUTAL` | Meaning |
|---|---|---|---|---|---|
| `reactionDelayS` | 0.60 | 0.35 | 0.18 | 0.08 | Delay before responding to a new stimulus |
| `sensorUpdateHz` | 4 | 8 | 15 | 30 | How often the sensor snapshot refreshes |
| `aimErrorRad` (base) | 0.070 | 0.035 | 0.015 | 0.005 | Standard deviation of aim offset |
| `aimSettleRate` (rad/s) | 1.2 | 2.2 | 3.5 | 5.0 | How fast aim converges on target |
| `leadPredictionQuality` | 0.30 | 0.65 | 0.90 | 1.00 | Fraction of correct target lead applied |
| `throttleAggression` | 0.55 | 0.75 | 0.90 | 1.00 | Fraction of available throttle used |
| `avoidanceLookaheadS` | 0.5 | 0.9 | 1.4 | 1.8 | Obstacle avoidance horizon |
| `targetSwitchCooldownS` | 4.0 | 2.5 | 1.2 | 0.6 | Minimum time before re-targeting |
| `usesPartTargeting` | no | no | yes | yes | Aims at specific parts (wheels/weapons) rather than centre of mass |
| `retreatHealthFraction` | 0.15 | 0.30 | 0.35 | 0.40 | Vehicle-integrity level that triggers retreat |
| `firingDisciplineRange` | 1.30 | 1.10 | 1.00 | 0.95 | Multiplier on effective range before it will fire |
| `usesCover` | no | yes | yes | yes | Paths toward cover when retreating |
| `focusFireCoordination` | no | no | yes | yes | Prefers a target a teammate is already engaging |

**R4.** Difficulty parameters live in `assets/balance/bot_difficulty.json`, not in code, so tuning is content.

<!-- D11-S4.3 -->### 4.3 Sensor Model

**R5.** A bot perceives the world only through a `SensorSnapshot`, refreshed at `sensorUpdateHz` and offset by `reactionDelayS`.

```pseudo
record SensorSnapshot:
    TickNumber      capturedTick        # when this view was taken
    PerceivedTarget[] targets           # only what passed the perception test
    Vector3         selfPosition, selfVelocity
    float           selfIntegrity       # own vehicle's aggregate health fraction
    Vector3[]       nearbyObstacles     # from a short-range ray fan
    Vector3[]       incomingProjectiles # only if within PROJECTILE_NOTICE_M

record PerceivedTarget:
    EntityId  entity
    Vector3   position, velocity        # as of capturedTick, with error applied
    float     integrity                 # 0..1, aggregate health
    int       teamId
    boolean   hasLineOfSight
    float     lastSeenTick
    float     threatScore
```

**R6.** **Bots never read the world directly.** Perception rules, all enforced:

| Rule | Detail |
|---|---|
| Line of sight | A single ray on `LAYER_SENSOR_RAY` from the bot's sensor origin to the target's COM. Blocked ⇒ not currently perceived. |
| Memory | A target lost from sight is remembered for `TARGET_MEMORY_S = 3.0`, with its last known position extrapolated by its last known velocity. After that it is forgotten. |
| Range | Beyond `sensorRangeM` (from the vehicle's stats, D05-S4.5) a target is not perceived at all. |
| Delay | `capturedTick = world.tick − round(reactionDelayS × TICK_RATE_HZ)`. Positions are the *delayed* positions, not current ones. |
| Error | Position error is a seeded Gaussian scaled by distance and difficulty. |
| No hidden state | A bot cannot see health values it could not infer; `integrity` is derived from *visible* damage states, which is exactly what a player reads off the silhouette (P4). |

```pseudo
function updateSensors(bot, world):
    if world.tick % ticksPerUpdate(bot.difficulty) != 0: return    # refresh at sensorUpdateHz

    rng = world.random.stream(BOT_DECISION)
    snapshot = new SensorSnapshot(capturedTick = world.tick - delayTicks(bot))
    historical = world.stateHistory.at(snapshot.capturedTick)      # reuse the lag-comp
                                                                    # history buffer (D10-S5.7)
    for other in historical.vehicles.sortedBy(entityId):            # deterministic (G3)
        if other == bot.vehicle: continue
        d = distance(bot.position, other.position)
        if d > bot.stats.sensorRangeM: continue
        los = rayClear(bot.sensorOrigin, other.com, mask = MASK_SENSOR_RAY)

        if los:
            err = rng.nextGaussian3() * positionErrorSigma(bot.difficulty, d)
            snapshot.targets.append(PerceivedTarget(
                entity = other.id, position = other.position + err,
                velocity = other.velocity, integrity = visibleIntegrity(other),
                teamId = other.team, hasLineOfSight = true, lastSeenTick = world.tick))
            bot.memory.remember(other.id, other.position, other.velocity, world.tick)
        else if bot.memory.has(other.id, within = TARGET_MEMORY_S):
            m = bot.memory.get(other.id)
            snapshot.targets.append(PerceivedTarget(
                entity = other.id,
                position = m.position + m.velocity * elapsed(m.tick),   # dead reckoning
                hasLineOfSight = false, lastSeenTick = m.tick, ...))

    snapshot.nearbyObstacles = rayFan(bot, count = 7, spreadDeg = 100,
                                      length = bot.speed * avoidanceLookaheadS(bot))
    bot.perceivedWorld = snapshot
```

---

<!-- D11-S5 -->## 5. Logic & Algorithms

<!-- D11-S5.1 -->### 5.1 Behaviour Tree

```pseudo
# Root is a priority selector: the first child whose precondition passes runs.
# Ordering encodes tactical priority, and it is fixed (G3).

Selector "root"
├── Sequence "survive"
│     ├── Condition: selfIntegrity < retreatHealthFraction
│     ├── Action:    SelectRetreatDestination        (cover if usesCover, else spawn side)
│     └── Action:    DriveTo(destination, aggression = 1.0)
│                    + FireAtNearestThreat(opportunistic)
├── Sequence "unstick"
│     ├── Condition: speed < 0.5 m/s for > STUCK_TICKS (90) while throttle > 0.5
│     └── Action:    UnstickManoeuvre                (reverse + counter-steer, 1.5 s)
├── Sequence "objective"                              (PAYLOAD mode only)
│     ├── Condition: match.mode == PAYLOAD
│     └── Action:    DriveTo(payloadEscortPoint) + EngageOpportunistically
├── Sequence "engage"
│     ├── Condition: hasTarget()
│     ├── Action:    MaintainEngagementRange(target)  (approach or back off)
│     ├── Action:    AimAt(target)
│     └── Action:    FireWhenSolutionValid()
├── Sequence "hunt"
│     ├── Condition: hasRememberedTarget()
│     └── Action:    DriveTo(lastKnownPosition)
└── Action "patrol"
      └── DriveTo(nextPatrolPoint)                    (arena points of interest)

# R7. Every leaf action returns RUNNING/SUCCESS/FAILURE and writes into a shared
#     BlackBoard (desired destination, desired aim, desired fire mask). The steering
#     and aim solvers convert the blackboard into an InputCommand. The tree never
#     writes throttle/steer directly — that separation is what keeps the driving model
#     consistent regardless of which behaviour is active.
```

<!-- D11-S5.2 -->### 5.2 Target Selection

```pseudo
function selectTarget(bot, snapshot):
    if bot.targetEntity != 0
       and ticksSince(bot.lastTargetSwitch) < targetSwitchCooldownTicks(bot.difficulty)
       and stillValid(bot.targetEntity, snapshot):
        return bot.targetEntity                        # commitment prevents twitchy swapping

    best = null; bestScore = -INF
    for t in snapshot.targets.sortedBy(entity):        # deterministic (G3)
        if t.teamId == bot.teamId and not friendlyFire: continue

        score = 0
        score += 100 * (1.0 - clamp(distance(bot, t) / bot.stats.sensorRangeM, 0, 1))
        score +=  80 * (1.0 - t.integrity)             # finish wounded targets
        score +=  40 if t.hasLineOfSight else 0
        score +=  30 if t.entity == bot.lastAttacker else 0        # answer whoever hit us
        score +=  25 if focusFireCoordination(bot)
                        and teammatesTargeting(t) > 0 else 0
        score -=  50 if angleOffBow(bot, t) > 120_deg else 0       # behind us is awkward
        if score > bestScore: bestScore = score; best = t.entity

    if best != bot.targetEntity: bot.lastTargetSwitch = world.tick
    return best

# Part-level targeting (HARD/BRUTAL only, D11-R3).
function selectAimPoint(bot, target):
    if not usesPartTargeting(bot.difficulty): return target.comPosition

    # Prefer parts whose loss most degrades the target, weighted by how exposed they are.
    candidates = target.visibleParts.sortedBy(slotPath)            # deterministic
    best = argmax(candidates, key = part ->
              partValueWeight(part.category)          # wheel 1.0, weapon 0.9, panel 0.3
            * exposureFactor(part)                    # uncovered parts score higher
            * (1.0 - part.healthFraction * 0.5)       # nearly-dead parts are worth finishing
            * hitProbability(bot, part))              # small parts at range score lower
    return best.worldPosition
```

<!-- D11-S5.3 -->### 5.3 Bot Decision Loop

```pseudo
function BotDecisionSystem.update(world, dt, tick):          # slot 3, authority only
    for bot in world.family(BotController, PlayerInput, VehicleChassis).iterate():
                                                              # ascending EntityId (G3)
        # ---- 1. PERCEIVE -------------------------------------------------------
        updateSensors(bot, world)                             # D11-S4.3; respects sensorUpdateHz
        snapshot = bot.perceivedWorld

        # ---- 2. DECIDE ---------------------------------------------------------
        bot.blackboard.clear()
        bot.behaviorTreeState = tickBehaviourTree(bot, snapshot, bot.blackboard)
        bot.targetEntity = bot.blackboard.target

        # ---- 3. NAVIGATE -------------------------------------------------------
        if bot.blackboard.destination != bot.currentPathGoal
           or bot.path.isExhausted()
           or ticksSince(bot.lastRepath) > REPATH_INTERVAL_TICKS (60):
            bot.path = Navigation.findPath(world.arena.navmesh,
                                           bot.position, bot.blackboard.destination)
            bot.currentPathGoal = bot.blackboard.destination
            bot.lastRepath = tick
            if bot.path == null:                              # unreachable
                bot.blackboard.destination = nearestReachablePoint(bot.blackboard.destination)

        steerTarget = bot.path != null
                      ? followPath(bot.path, bot.position, lookaheadM = 6.0)
                      : bot.blackboard.destination

        # ---- 4. STEER (drive) --------------------------------------------------
        desired = seek(bot.position, steerTarget)
        desired = applyObstacleAvoidance(desired, snapshot.nearbyObstacles,
                                         lookaheadS = avoidanceLookaheadS(bot))
        desired = applySeparation(desired, nearbyAllies(snapshot), minDistM = 6.0)

        (throttle, steer, brake) = solveVehicleControls(bot, desired)
        throttle *= throttleAggression(bot.difficulty)

        # ---- 5. AIM ------------------------------------------------------------
        if bot.targetEntity != 0:
            target    = snapshot.targetById(bot.targetEntity)
            aimPoint  = selectAimPoint(bot, target)
            lead      = computeLead(aimPoint, target.velocity, bot.position,
                                    bot.activeWeapon.projectileSpeedMps)
            aimPoint += lead * leadPredictionQuality(bot.difficulty)

            # Persistent, slowly-varying error, not per-tick jitter: jitter looks like a
            # bad aimbot; drift looks like a human who is slightly off.
            aimPoint += bot.aimErrorOffset            # updated by a seeded random walk
            (yaw, pitch) = anglesTo(aimPoint, from = bot.weaponOrigin)
            bot.aimYaw   = moveToward(bot.aimYaw,   yaw,   aimSettleRate(bot) * dt)
            bot.aimPitch = moveToward(bot.aimPitch, pitch, aimSettleRate(bot) * dt)

        # ---- 6. FIRE -----------------------------------------------------------
        fireMask = 0
        if bot.targetEntity != 0 and target.hasLineOfSight:
            for group in bot.weaponGroups:
                w = bestWeaponInGroup(bot, group, target)      # D11-S5.5
                if w == null: continue
                inRange   = distance(bot, target) <= w.effectiveRangeM
                                                     * firingDisciplineRange(bot.difficulty)
                aimedWell = angleBetween(bot.aimDirection, directionTo(aimPoint))
                            < w.spreadRad + aimToleranceRad(bot.difficulty)
                clearShot = not friendlyInLineOfFire(bot, target)   # never shoot a teammate
                if inRange and aimedWell and clearShot and w.cooldownRemainingS <= 0:
                    fireMask |= bit(group)

        # ---- 7. EMIT (identical structure to a human's input) -------------------
        bot.PlayerInput.set(throttle, steer, brake, bot.aimYaw, bot.aimPitch,
                            fireMask, commandTick = tick)

function solveVehicleControls(bot, desiredDirection):
    forward   = bot.transform.forwardAxis()
    angleOff  = signedAngle(forward, desiredDirection, WORLD_UP)
    steer     = clamp(angleOff / bot.stats.maxSteerRad, -1, 1)

    # Slow down for sharp turns and when close to the destination — otherwise bots
    # oscillate around waypoints at speed.
    turnFactor  = 1.0 - clamp(abs(angleOff) / (90_deg), 0, 0.8)
    arriveFactor= clamp(distanceToGoal / ARRIVE_RADIUS_M (12.0), 0.2, 1.0)
    throttle    = turnFactor * arriveFactor

    brake = 0
    if abs(angleOff) > 120_deg and bot.speed > 6.0:
        brake = 1.0; throttle = 0            # too sharp to steer; stop, then turn
    if bot.speed > bot.stats.maxSpeedMps * 0.95: throttle = 0
    return (throttle, steer, brake)
```

<!-- D11-S5.4 -->### 5.4 Navigation

```pseudo
# NAVMESH GENERATION (offline, asset-pipeline; produces arenas/<id>/navmesh.bin)
function generateNavmesh(arenaCollisionMesh):
    # Voxelise, then extract walkable regions, in the Recast style. Parameters are
    # vehicle-scaled, not character-scaled — that is the only real difference from a
    # standard character navmesh.
    params = { cellSizeM: 0.5, cellHeightM: 0.25,
               agentRadiusM: 2.0,          # half the widest vehicle
               agentHeightM: 2.5,
               agentMaxClimbM: 0.45,       # what a wheel can mount
               agentMaxSlopeDeg: 35.0,     # what a vehicle can climb
               regionMinAreaM2: 12.0 }
    heightfield = voxelise(arenaCollisionMesh, params)
    markWalkable(heightfield, params.agentMaxSlopeDeg)
    regions   = watershedPartition(heightfield, params.regionMinAreaM2)
    contours  = traceContours(regions)
    polyMesh  = triangulate(contours)
    annotate(polyMesh, cost = terrainCostFrom(materialUnderPolygon))
    write(polyMesh, "navmesh.bin")

# PATHFINDING (runtime): A* over navmesh polygons, then string-pulling.
function findPath(navmesh, from, to):
    startPoly = navmesh.nearestPoly(from, searchRadius = 8.0)
    endPoly   = navmesh.nearestPoly(to,   searchRadius = 8.0)
    if startPoly == null or endPoly == null: return null

    open = PriorityQueue(); open.push(startPoly, 0)
    cameFrom = {}; gScore = { startPoly: 0 }
    while open.isNotEmpty():
        current = open.pop()
        if current == endPoly: break
        for neighbour in current.neighbours.sortedBy(polyId):     # deterministic (G3)
            tentative = gScore[current] + edgeCost(current, neighbour)
            if tentative < gScore.getOrDefault(neighbour, INF):
                cameFrom[neighbour] = current
                gScore[neighbour]   = tentative
                open.push(neighbour, tentative + heuristic(neighbour, endPoly))
    if endPoly not in cameFrom and endPoly != startPoly: return null

    corridor = reconstruct(cameFrom, startPoly, endPoly)
    return stringPull(corridor, from, to)     # funnel algorithm -> straight-line waypoints

# R8. Pathfinding is BUDGETED: at most MAX_PATH_NODES (512) expanded per call, and at
#     most MAX_PATHS_PER_TICK (2) calls across all bots per tick, queued round-robin by
#     ascending EntityId. A bot waiting one extra tick for a path is invisible;
#     a 12-bot path storm on one tick is a frame spike.
# R9. Debris is NOT in the navmesh (NG5). Bots avoid it through local obstacle
#     avoidance, which is also what handles other vehicles.

function applyObstacleAvoidance(desired, obstacles, lookaheadS):
    steer = desired
    for o in obstacles.sortedBy(distance):
        ttc = timeToCollision(self, o)
        if ttc < lookaheadS:
            away    = normalize(self.position - o.position)
            urgency = 1.0 - (ttc / lookaheadS)
            steer  += away * urgency * AVOIDANCE_WEIGHT (2.0)
    return normalize(steer)
```

<!-- D11-S5.5 -->### 5.5 Weapon Selection

```pseudo
function bestWeaponInGroup(bot, group, target):
    best = null; bestScore = -INF
    for w in bot.weaponsInGroup(group).sortedBy(slotPath):
        if w.state in {DESTROYED, DETACHED}: continue
        if w.cooldownRemainingS > 0:          continue
        if w.ammoRemaining == 0:              continue
        if w.heat > 0.9:                      continue

        d = distance(bot, target)
        score  = rangeSuitability(w, d)                     # peaks at w's optimal range
        score *= damageTypeSuitability(w.damageType, target.dominantMaterial)
        score *= (1.0 - w.heat * 0.5)
        score *= w.healthFraction                            # a degraded gun is less useful
        if d < w.minRangeM: score *= 0.1                     # e.g. mortars up close
        if score > bestScore: bestScore = score; best = w
    return best

# R10. Bots respect their own cooldowns, ammo, and heat because those live in the same
#      components the server owns (D04-S4.3). A bot cannot fire faster than a human
#      with the same vehicle — there is no separate bot firing path to get that wrong.
```

<!-- D11-S5.6 -->### 5.6 Bot Lifecycle

```pseudo
function BotFactory.fill(world, count, difficulty):
    for i in 0 .. count-1:
        botPlayer = world.createEntity()
        add PlayerIdentityComponent(name = botName(i), isBot = true)
        add TeamComponent(assignTeam(world, balanced = true))
        add ScoreComponent()
        assembly = selectAssemblyForDifficulty(world, difficulty,
                                               world.random.stream(BOT_DECISION))
        vehicle  = spawnVehicle(world, assembly, chooseSpawnPoint(world), botPlayer)
        add BotControllerComponent(vehicle, difficulty = difficulty,
                                   reactionDelayS = table[difficulty].reactionDelayS)

# R11. Bots are removed OLDEST-FIRST when humans join (D01-E10). A human is never kicked.
# R12. Bot vehicles are chosen from the same assembly catalogue as players', respecting
#      the power budget (D05-S5.7). Bots never get exclusive equipment.
```

<!-- D11-S5.7 -->### 5.7 Match State Machine

```pseudo
STATES: LOBBY -> COUNTDOWN -> ACTIVE -> ENDING -> RESULTS -> (LOBBY)

function MatchFlowSystem.update(world, dt, tick):          # slot 4, authority only
    m = world.match
    switch m.phase:

        case LOBBY:
            # Wait for players, or start immediately if configured (D03-S4.2 autoStart).
            if world.config.autoStart or allPlayersReady(world)
               or (humanCount(world) >= 1 and lobbyWaitedTicks() > LOBBY_MAX_WAIT (1800)):
                BotFactory.fill(world, world.rules.botCount, world.rules.botDifficulty)
                assignTeams(world); loadArena(world, world.rules.arenaId)
                spawnAllVehicles(world)
                transitionTo(COUNTDOWN, tick)

        case COUNTDOWN:
            # Vehicles exist and are simulated (they settle onto their suspension),
            # but input is ignored and damage is disabled (D01-R21/R22).
            world.inputEnabled = false; world.damageEnabled = false
            if ticksInPhase() >= world.rules.warmupTicks:
                transitionTo(ACTIVE, tick)

        case ACTIVE:
            world.inputEnabled = true; world.damageEnabled = true
            handleRespawns(world, tick)
            result = evaluateWinCondition(world)            # D01-S5.5
            if result == ENTER_SUDDEN_DEATH:
                world.rules.respawnDelayTicks = INFINITE
                m.suddenDeath = true
                world.clock.timeLimitTicks += world.rules.suddenDeathTicks
            else if result != CONTINUE:
                m.outcome = result
                transitionTo(ENDING, tick)

        case ENDING:
            # Input ignored, but physics and destruction keep running so the final
            # wreck plays out on screen (D01-R23).
            world.inputEnabled = false
            if ticksInPhase() >= ENDING_TICKS (300):
                transitionTo(RESULTS, tick)

        case RESULTS:
            if ticksInPhase() >= RESULTS_TICKS (900) or allPlayersLeft(world):
                emitMatchReport(world)                       # D11-S5.8
                resetWorld(world)                            # destroy entities, new seed
                transitionTo(LOBBY, tick)

function transitionTo(phase, tick):
    m.phase = phase; m.phaseEnteredTick = tick
    emit MatchPhaseChanged(phase, tick)                      # replicated, CONTROL (D10-S4.2)

function handleRespawns(world, tick):
    for player in world.players.sortedBy(playerId):          # deterministic (G3)
        if player.vehicle == null and not world.rules.noRespawn:
            if ticksSince(player.deathTick) >= world.rules.respawnDelayTicks:
                spawnPoint = chooseSpawnPoint(world, player,
                                              avoidEnemiesWithinM = 40)
                player.vehicle = spawnVehicle(world, player.selectedAssembly,
                                              spawnPoint, player)
```

<!-- D11-S5.8 -->### 5.8 Offline Headless Match Simulation

```pseudo
# Purpose: run complete matches with no client, no rendering, and no human, at maximum
# speed, to (a) smoke-test the whole simulation, (b) sweep balance, (c) catch
# non-determinism and crashes that only appear over long runs.

function runOfflineMatch(config):
    world = WorldFactory.create(config, assetIndex)          # DEDICATED_SERVER system set
    BotFactory.fill(world, config.botCount, config.botDifficulty)
    world.match.requestPhase(COUNTDOWN)

    tick = 0; maxTicks = config.timeLimitTicks + SAFETY_TICKS (3600)
    while world.match.phase != RESULTS and tick < maxTicks:
        world.tick(tick)                                     # no sleeping: as fast as it runs
        tick += 1
        if tick % 600 == 0: recordTelemetrySample(world)
    assert tick < maxTicks : "match failed to terminate"     # a hang is a bug, not a timeout

    return MatchReport {
        seed, config, durationTicks: tick, outcome: world.match.outcome,
        perPlayer: [ { name, isBot, difficulty, assemblyId, kills, deaths, assists,
                       damageDealt, damageTaken, partsDestroyed, partsLost,
                       distanceTravelledM, shotsFired, shotsHit, timeAliveTicks } ],
        perPart:   [ { partTypeId, timesDestroyed, avgLifetimeTicks, damageAbsorbed } ],
        physics:   { maxTickDurationMs, meanTickDurationMs, p99TickDurationMs,
                     maxDebrisBodies, maxEntities, nanEvents },
        telemetry: samples }

# BALANCE SWEEP: many matches, varied configuration, aggregated.
function runBalanceSweep(matrix, repeats):
    results = []
    for cfg in matrix.expand():                              # assemblies × modes × difficulty
        for r in 0 .. repeats-1:
            cfg.matchSeed = deterministicSeed(cfg, r)        # reproducible (G4)
            results.append(runOfflineMatch(cfg))
    report = aggregate(results)
    for assembly in report.assemblies:
        # Flag outliers rather than auto-tuning: the tool reports, humans decide.
        if assembly.winRate > 0.60 or assembly.winRate < 0.40:
            report.flags.append("{} win rate {} outside [0.40, 0.60] over {} matches"
                                .format(assembly.id, assembly.winRate, assembly.matchCount))
    writeJson("build/balance/sweep.json", report)
    return report

# R13. Every offline match is fully reproducible from its seed and config. A crash found
#      in a sweep is replayed by re-running with the same seed — which is only true
#      because of G4 and G5.
# R14. The sweep runs in CI nightly (D12-S5.5), not per-commit: 500 matches is minutes,
#      not seconds.
```

---

<!-- D11-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D11-1.** A bot's only output is `PlayerInputComponent`; no bot code writes any other component (ArchUnit rule).
- [ ] **AC-D11-2.** Bots never read world state directly; all perception flows through `SensorSnapshot` (ArchUnit rule on `BotDecisionSystem` reads).
- [ ] **AC-D11-3.** No difficulty level grants damage, health, speed, or accuracy bonuses beyond the perception/execution parameters in D11-S4.2.
- [ ] **AC-D11-4.** Each difficulty parameter measurably changes behaviour (verified by a sweep: `BRUTAL` beats `EASY` in ≥ 90% of head-to-head matches with identical vehicles).
- [ ] **AC-D11-5.** Bots respect line of sight; a bot does not track a target through a wall beyond `TARGET_MEMORY_S`.
- [ ] **AC-D11-6.** Reaction delay is observable: a bot does not respond to a new target sooner than `reactionDelayS`.
- [ ] **AC-D11-7.** Pathfinding is budgeted; no tick exceeds `MAX_PATHS_PER_TICK` path computations.
- [ ] **AC-D11-8.** Bots navigate the reference arena from any spawn to any other without getting permanently stuck (100 trials).
- [ ] **AC-D11-9.** The unstick behaviour recovers a wedged bot within 5 s in 95% of contrived stuck scenarios.
- [ ] **AC-D11-10.** Bots never fire through a teammate when `friendlyFire` is enabled.
- [ ] **AC-D11-11.** `HARD`/`BRUTAL` bots demonstrably target parts (measured: >40% of their damage lands on wheels and weapons versus <20% for `NORMAL`).
- [ ] **AC-D11-12.** Match phases transition exactly as D11-S5.7; input and damage gating match D01-R21/R22.
- [ ] **AC-D11-13.** An offline match always terminates; the safety cap is never reached in the standard suite.
- [ ] **AC-D11-14.** Two offline matches with the same seed and config produce identical reports (within physics tolerance).
- [ ] **AC-D11-15.** A 500-match balance sweep completes and flags any assembly outside a 40–60% win rate.
- [ ] **AC-D11-16.** Bot decision cost stays within budget: ≤ 0.8 ms per tick for 11 bots (D12-S5.6).

---

<!-- D11-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | Bot's target despawns | Target cleared next decision tick; the tree falls through to `hunt` then `patrol`. No null dereference. |
| E2 | Bot loses all wheels | It is immobile (D01-E4). Driving actions produce no motion; the `unstick` behaviour must not fire forever — it checks `hasDrivableWheels()` first and, if false, the bot switches to a stationary-turret behaviour. |
| E3 | Destination is unreachable | `findPath` returns null; the bot paths to the nearest reachable point and re-evaluates next repath. |
| E4 | Navmesh missing for an arena | Asset validation error A404 (D08-S5.4). At runtime, bots fall back to direct steering with obstacle avoidance and log at ERROR once. |
| E5 | Bot wedged between debris | Local avoidance plus `unstick`; if still stuck after `STUCK_ESCALATE_TICKS` (600), it is teleported to the nearest spawn point and the event is logged (a last resort that must be rare and visible). |
| E6 | All bots target the same player | Intended when `focusFireCoordination` is on. Spawn protection and the `separation` steering term prevent a literal pile-up. |
| E7 | Bot's weapon destroyed mid-burst | `bestWeaponInGroup` skips destroyed weapons next tick; if the group is empty, that bit is never set. |
| E8 | Bot has no weapons at all | `engage` still runs (it can ram); firing produces an empty mask. |
| E9 | `botCount` exceeds free slots | Clamped to available slots with a warning. |
| E10 | Human joins a full-bot match | Oldest bot removed; its vehicle is destroyed cleanly, its parts become debris. |
| E11 | Match reaches the time limit in `COUNTDOWN` (misconfiguration) | Countdown always completes; the clock starts at `ACTIVE`. A `warmupTicks` longer than `timeLimitTicks` is a configuration error caught at startup. |
| E12 | Every player leaves during `ACTIVE` | Bots continue; the match runs to its win condition (D01-E1). |
| E13 | Sudden death with all players tied and alive at the extended limit | Declare a draw (D01-E2). Never extend twice. |
| E14 | Respawn point occupied | Deterministically pick the next candidate by the `SPAWN_SELECT` stream (D06-E7). |
| E15 | Offline match does not terminate | Safety cap trips, the run **fails** rather than returning a partial report — a hang must be a red test, not a quiet truncation. |
| E16 | Behaviour tree throws | Log the bot entity, tick, and node path; reset that bot's tree state to root and continue. One bad bot must not abort the match. |
| E17 | Sensor snapshot is older than the history buffer | Clamp `capturedTick` to the oldest available tick; log once. |
| E18 | Bot aim error random walk drifts unboundedly | The walk is clamped to ±3σ; it is a bounded Ornstein-Uhlenbeck-style process, not a free walk. |

---

<!-- D11-S8 -->## 8. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D11-1 | 8 `NORMAL` bots, `DEATHMATCH`, headless | Match completes; a winner or a draw is declared; report emitted |
| T-D11-2 | Same seed, run twice | Identical `MatchReport` (within physics tolerance) |
| T-D11-3 | `BRUTAL` vs `EASY`, identical vehicles, 100 matches | `BRUTAL` wins ≥ 90% |
| T-D11-4 | Place a bot behind a wall from its target | No tracking after `TARGET_MEMORY_S`; bot moves to the last known position |
| T-D11-5 | Spawn a target in front of an `EASY` bot | No fire before 0.60 s |
| T-D11-6 | 12 bots requesting paths on one tick | ≤ 2 path computations that tick; the rest queue |
| T-D11-7 | 100 random spawn→spawn navigation trials | 100 arrivals; no permanent stalls |
| T-D11-8 | Wedge a bot against geometry | Recovers within 5 s in ≥ 95% of trials |
| T-D11-9 | Teammate directly between bot and target, friendly fire on | Bot does not fire |
| T-D11-10 | `HARD` bots vs a stationary target, 60 s | > 40% of damage on wheels/weapons |
| T-D11-11 | `NORMAL` bots, same scenario | < 20% on wheels/weapons |
| T-D11-12 | Destroy a bot's wheels | It stops driving, keeps firing, does not loop `unstick` |
| T-D11-13 | Destroy all of a bot's weapons | It attempts to ram; no null errors |
| T-D11-14 | Full phase run: LOBBY→…→LOBBY | Each transition at the specified tick counts; input/damage gating correct |
| T-D11-15 | Fire during `COUNTDOWN` (bot and human) | No projectiles spawn |
| T-D11-16 | Force a tie at the time limit with sudden death configured | Extends once, no respawns; a second tie declares a draw |
| T-D11-17 | 500-match balance sweep | Completes; flags any assembly outside 40–60% win rate |
| T-D11-18 | Profile 11 bots for 3600 ticks | Bot decision cost ≤ 0.8 ms/tick mean |
| T-D11-19 | Throw an exception from a behaviour tree node | That bot resets; the match continues; the error is logged with the node path |
| T-D11-20 | Remove the navmesh from an arena | A404 at validation; runtime falls back with a single ERROR log and bots still move |
| T-D11-21 | Human joins a match with `botCount` bots at capacity | Oldest bot removed; no human displaced |
| T-D11-22 | Grep bot code for direct world reads | None; all perception via `SensorSnapshot` |

---

<!-- D11-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| Deterministic ordering, seeded randomness | `docs/00_master_index.md#D00-S5.2` (G3, G4) |
| Game modes and rules | `docs/01_product_game_design.md#D01-S4.2`, `#D01-S4.3` |
| Win condition evaluation | `docs/01_product_game_design.md#D01-S5.5` |
| Match structure and phase gating | `docs/01_product_game_design.md#D01-S5.6` |
| Scoring | `docs/01_product_game_design.md#D01-S5.4` |
| Mode configuration (`--bots`, `--bot-difficulty`) | `docs/03_runtime_modes.md#D03-S4.2` |
| `BotDecisionSystem` / `MatchFlowSystem` slots | `docs/04_entity_component_model.md#D04-S4.4` |
| `BotControllerComponent` fields | `docs/04_entity_component_model.md#D04-S4.3` |
| Slot graph for part targeting | `docs/05_vehicle_part_system.md#D05-S4.3` |
| Vehicle stats used by the steering solver | `docs/05_vehicle_part_system.md#D05-S5.6` |
| `BOT_DECISION` random stream | `docs/06_physics_simulation.md#D06-S5.8` |
| Sensor ray collision mask | `docs/06_physics_simulation.md#D06-S4.4` |
| Damage/health visibility for `integrity` | `docs/07_damage_destruction_model.md#D07-S5.3` |
| Arena definition and navmesh asset | `docs/08_asset_pipeline.md#D08-S4.7` |
| `InputCommand` structure | `docs/10_networking_multiplayer.md#D10-S4.2` |
| State history buffer reused by sensors | `docs/10_networking_multiplayer.md#D10-S5.7` |
| Headless smoke and nightly sweep in CI | `docs/12_testing_validation_ci.md#D12-S5.5` |
| Performance budgets | `docs/12_testing_validation_ci.md#D12-S5.6` |
