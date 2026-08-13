<!-- D10-S0 --># 10 — Networking and Multiplayer

**Document ID:** D10
**Owns:** Authority model, message catalogue, replication tables, snapshot/delta format, prediction and reconciliation, lag compensation, connection lifecycle, anti-cheat boundaries, listen vs dedicated differences.

---

<!-- D10-S1 -->## 1. Purpose

This document specifies how multiple machines agree on one match. It fixes the authority model (server-authoritative with client-side prediction), exactly what is replicated and what is not, the wire message catalogue, the snapshot and delta encoding, the prediction/reconciliation algorithm, lag compensation for hits, the connection lifecycle, and the trust boundary that defines what a client is allowed to assert.

The governing invariants are G1 (single authority), G15 (clients never author gameplay state), G16 (replication is idempotent), and G6 (authoritative vs cosmetic).

Requirements are numbered `R1..Rn`, cited as `D10-R12`.

---

<!-- D10-S2 -->## 2. Scope

<!-- D10-S2.1 -->### 2.1 In Scope

- Authority model and its justification.
- Transport channels and reliability classes.
- Message catalogue.
- Replicated / not-replicated state tables, including destruction state.
- Tick rate, snapshot rate, delta compression, quantisation.
- Client-side prediction and reconciliation.
- Entity interpolation for remote entities.
- Lag compensation for hitscan and projectile hits.
- Connection lifecycle from connect to disconnect.
- Anti-cheat assumptions and trust boundaries.
- Listen-server vs dedicated-server differences.

<!-- D10-S2.2 -->### 2.2 Non-Goals

- **NG1.** Matchmaking, lobbies outside a server, accounts, or authentication services.
- **NG2.** NAT traversal, relays, or hole punching. v1 assumes a directly reachable server.
- **NG3.** Encryption of game traffic. The handshake supports a shared secret for basic integrity; full transport security is out of scope for v1.
- **NG4.** Voice chat.
- **NG5.** Server-side anti-cheat heuristics beyond the validation rules here.
- **NG6.** Rollback/lockstep determinism networking — explicitly rejected (D10-S5.1).

---

<!-- D10-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S5.2` | G1, G6, G15, G16 |
| `docs/00_master_index.md#D00-S6.4` | `TICK_RATE_HZ`, `SNAPSHOT_RATE_HZ` |
| `docs/02_technical_architecture.md#D02-S4.3` | KryoNet choice and the `Transport` abstraction |
| `docs/03_runtime_modes.md#D03-S4.1` | Which modes are authority/client |
| `docs/04_entity_component_model.md#D04-S6.2` | `NetworkId` semantics |
| `docs/04_entity_component_model.md#D04-S5.8` | `Replicable` serialisation hook |
| `docs/06_physics_simulation.md#D06-S5.8` | Determinism limits that force state replication |
| `docs/07_damage_destruction_model.md#D07-S5.9` | Destruction replication requirements |

---

<!-- D10-S4 -->## 4. Data Contracts

<!-- D10-S4.1 -->### 4.1 Channels and Reliability

**R1.** Two channels, with distinct reliability semantics.

| Channel | Transport | Reliability | Ordering | Carries |
|---|---|---|---|---|
| `CONTROL` | TCP | Reliable | Ordered | Handshake, match config, assembly definitions, structural destruction events, chat, disconnect |
| `STATE` | UDP | Unreliable | Unordered (tick-stamped) | Input commands, snapshots/deltas, cosmetic event hints |

**R2.** The rule for choosing a channel: **if losing the message would cause permanent divergence, it is CONTROL. If the next message supersedes it, it is STATE.** A `PartDetached` event is CONTROL (losing it leaves the client with a heavier vehicle forever, D07-R27). A health update is STATE (the next snapshot carries the current value).

<!-- D10-S4.2 -->### 4.2 Message Catalogue

**R3.** Every message. `C→S` = client to server, `S→C` = server to client.

| Message | Dir | Channel | Contents | Rate |
|---|---|---|---|---|
| `ClientHello` | C→S | CONTROL | protocolVersion, contentHash, clientVersion, playerName | once |
| `ServerHello` | S→C | CONTROL | accepted, peerId, serverTick, matchConfig, protocolVersion, contentHash | once |
| `Reject` | S→C | CONTROL | reason enum, detail | once |
| `MatchConfig` | S→C | CONTROL | rules, arenaId, matchSeed, teamAssignments | on change |
| `SpawnEntity` | S→C | CONTROL | networkId, archetype, assemblyId, ownerPeerId, initial transform, tick | per spawn |
| `DespawnEntity` | S→C | CONTROL | networkId, reason, tick | per despawn |
| `StructuralEvent` | S→C | CONTROL | one of `PartDestroyed`/`PartFractured`/`PartDetached`/`VehicleDestroyed` (D07-S5.9) | per event |
| `Snapshot` | S→C | STATE | serverTick, baselineTick, entity deltas | `SNAPSHOT_RATE_HZ` (20) |
| `InputCommand` | C→S | STATE | acknowledgedSnapshotTick, sequence, commandTick, throttle/steer/brake/aim/fireMask, redundant window | `TICK_RATE_HZ` (60), batched |
| `InputAck` | S→C | STATE | lastProcessedSequence, lastProcessedTick | piggybacked on Snapshot |
| `SnapshotNack` | C→S | STATE | missingBaselineTick | per undecodable delta |
| `HitConfirm` | S→C | STATE | targetNetworkId, slotPath, damageApplied, damageType, tick | per confirmed hit |
| `DamageReceived` | S→C | STATE | attackerPeerId, slotPath, amount, type, direction | per hit taken |
| `ScoreUpdate` | S→C | CONTROL | full scoreboard | on change, max 2 Hz |
| `MatchPhase` | S→C | CONTROL | phase, phaseEnteredTick, remainingTicks | on change |
| `ChatMessage` | both | CONTROL | peerId, text | user-driven |
| `Ping` / `Pong` | both | STATE | sendTimeTicks, echoTick | 2 Hz |
| `Disconnect` | both | CONTROL | reason, detail | once |
| `SelectVehicle` | C→S | CONTROL | vehicleTypeId | per respawn |
| `AdminCommand` | C→S | CONTROL | command text (authenticated peers only) | rare |

**R4a.** `SnapshotNack` is what R18 asks a client to send when a delta names a baseline it does not hold, and it is the client's half of the acknowledgement protocol; `acknowledgedSnapshotTick` on `InputCommand` is the other half. Both ride the STATE channel a client is already sending at 60 Hz, so neither costs a message of its own. Added after the implementation found R18 requiring a message the catalogue did not name (DEC-058).

**R4.** `InputCommand` carries a **redundant window** of the last `INPUT_REDUNDANCY = 6` commands. UDP loss of a single input packet therefore causes no dropped input at all, at the cost of a few bytes — far cheaper than reliability for a message that is superseded every 16 ms anyway.

<!-- D10-S4.3 -->### 4.3 Replication Tables

**R5. Replicated (authoritative).** Everything a client must agree on.

| State | Source | Channel | Rate | Encoding |
|---|---|---|---|---|
| Vehicle root transform (position, rotation) | `TransformComponent` | STATE | 20 Hz | Position: 3× 16-bit quantised to arena bounds (≈1.2 cm). Rotation: smallest-three quaternion, 3× 10 bits + 2-bit index |
| Vehicle linear/angular velocity | `VelocityComponent` | STATE | 20 Hz | 3× 12-bit, ±60 m/s and ±30 rad/s |
| Per-part `healthFraction` | `HealthComponent` | STATE | 20 Hz, delta only | 8 bits (D07-S5.9) |
| Per-part `DamageState.state` | `DamageStateComponent` | STATE | 20 Hz, delta only | 3 bits |
| Structural events (destroy/fracture/detach/wreck) | events | **CONTROL** | per event | full record |
| Entity spawn/despawn | events | **CONTROL** | per event | full record |
| Player input (C→S) | `PlayerInputComponent` | STATE | 60 Hz | throttle/steer/brake 8 bits each, aim 2× 16 bits, fireMask 8 bits |
| Weapon cooldown/ammo/heat | `WeaponControllerComponent` | STATE | 20 Hz, owner only | 8 bits each |
| Projectile spawn | events | STATE | per shot | origin (quantised), direction (16-bit octahedral), speed, weaponType |
| Match phase, clock, score | match components | CONTROL | on change | full |
| Team assignment | `TeamComponent` | CONTROL | on change | 4 bits |
| Hit confirmations and damage taken | events | STATE | per event | full |

**R6. Not replicated (cosmetic or derived).** Anything a client computes or invents for itself.

| State | Why not replicated |
|---|---|
| Morph/shape-key weights | Derived from replicated `healthFraction` (D07-S5.5) |
| Individual shard transforms and velocities | Cosmetic (D07-R5); clients spawn their own shard set from the replicated fracture event |
| Debris trajectories and despawn timing | Cosmetic; debris affects nothing (D06-R10/R11) |
| Particles, smoke, fire, sparks, decals, scorch | Cosmetic |
| Audio | Cosmetic |
| Camera state | Client-local |
| Vehicle total mass, COM, inertia | **Derived** on every peer from the replicated structural state — cheaper to recompute than to send, and guaranteed to agree because its inputs agree (D07-R26) |
| Effective stats from degradation | Derived from replicated health via the same pure function (D05-S5.6) |
| UI state, hit markers' animation | Client-local presentation of replicated events |
| Wheel suspension compression | Derived by the client's own physics; visual only |

**R7.** The line between R5 and R6 is exactly G6. A CI check cross-references this table with D07-S4.2 and fails if a field appears in one as authoritative and in the other as cosmetic.

<!-- D10-S4.4 -->### 4.4 Snapshot Format

```pseudo
Snapshot {
    uint32  serverTick
    uint32  baselineTick        # the last snapshot this client ACKed; 0 = full snapshot
    uint16  entityCount
    Entity[] entities
}

Entity {
    uint32  networkId
    uint8   changedComponentMask     # bit per replicated component type
    ComponentDelta[] deltas          # only for set bits
}

ComponentDelta {
    uint8   componentTypeId          # from ComponentTypeRegistry (D04-S6.3)
    uint8   changedFieldMask         # bit per field within the component
    bits    fieldValues              # bit-packed, per the encoding in D10-S4.3
}

# R8. Bit-level packing throughout. A 12-player match with 64-part vehicles would be
#     unshippable at byte granularity; at bit granularity a full-detail snapshot of one
#     vehicle is ~40 bytes and a typical delta is ~8.
# R9. Every snapshot is self-describing via the masks, so a client can apply it without
#     knowing which fields the server chose to send this tick.
# R10. Snapshots are IDEMPOTENT (G16): applying the same snapshot twice yields the same
#      state, because every field carries an absolute value, never an increment.
```

<!-- D10-S4.5 -->### 4.5 Protocol Versioning

**R11.** `ClientHello` and `ServerHello` exchange:

| Field | Purpose |
|---|---|
| `protocolVersion` | Integer, bumped on any wire-format change |
| `contentHash` | SHA-256 over `asset-index.json` + `component_types.txt` |

A mismatch in either rejects the connection with `PROTOCOL_MISMATCH` / `CONTENT_MISMATCH`, reporting both values (D03-E6). Silently proceeding with mismatched content is how desyncs become unexplainable.

---

<!-- D10-S5 -->## 5. Logic & Algorithms

<!-- D10-S5.1 -->### 5.1 Authority Model

**R12.** The model is **server-authoritative simulation with client-side prediction and entity interpolation**.

| Option | Verdict | Reasoning |
|---|---|---|
| **Server-authoritative + client prediction** | **CHOSEN** | The only model compatible with our constraints. Native Bullet is not bit-deterministic across platforms (D02-R4, D06-S5.8), which rules out lockstep. Destruction state must be identical on all peers or the game is unreadable, which rules out distributed authority. Prediction hides latency for the one thing that needs to feel instant: your own vehicle. |
| Deterministic lockstep | Rejected | Requires bit-identical physics across machines. Native Bullet does not provide it. Would also make a single desync fatal to the match. |
| Client-authoritative movement | Rejected | Violates G15; trivially exploitable (speed, teleport, invulnerability). |
| Distributed authority (each client owns its vehicle) | Rejected | Destruction and collision outcomes would depend on who reported first. |

```pseudo
# AUTHORITY FLOW, one tick.
#
#  CLIENT (tick T)                          SERVER (tick T')
#  ──────────────                           ────────────────
#  1. sample input -> InputCommand(seq, T)
#  2. apply locally (PREDICT):
#       simulate own vehicle with this
#       input for one tick
#  3. store (seq, T, input, resultingState)
#     in the pending buffer
#  4. send InputCommand ─────────────────►  5. buffer input; at tick T' apply the
#                                              input whose commandTick is closest
#                                              to T' (clamped to the jitter window)
#                                           6. simulate ALL entities authoritatively
#                                           7. build Snapshot(serverTick = T',
#                                                             baseline = lastAcked)
#  9. receive Snapshot ◄──────────────────  8. send Snapshot + InputAck(seq)
# 10. RECONCILE:
#       - discard pending inputs <= ackedSeq
#       - if predicted state at ackedTick differs from the snapshot beyond
#         RECONCILE_THRESHOLD, rewind to the snapshot state and replay every
#         remaining pending input
# 11. INTERPOLATE remote entities between the two most recent snapshots,
#     rendered INTERP_DELAY_MS in the past
```

<!-- D10-S5.2 -->### 5.2 Server Tick

```pseudo
function serverTick(world, tick):
    # 1. Drain and validate inputs (never trust them; G15).
    for peer in peers.sortedBy(peerId):                       # deterministic (G3)
        cmd = peer.inputBuffer.selectFor(tick)                # jitter buffer, D10-S5.3
        if cmd == null:
            cmd = peer.lastInput.withFireMask(0)              # repeat movement, stop firing
            peer.missedInputTicks += 1
        validateInput(cmd, peer)                              # D10-S5.9
        applyToEntity(peer.vehicle, cmd)

    # 2. Simulate. Identical system schedule to single-player (D03-R3).
    world.tick(tick)

    # 3. Send snapshots at SNAPSHOT_RATE_HZ, staggered across peers so 12 clients do
    #    not all receive a burst on the same tick.
    if tick % (TICK_RATE_HZ / SNAPSHOT_RATE_HZ) == peer.staggerOffset:
        for peer in peers:
            snapshot = buildSnapshot(world, peer, baseline = peer.lastAckedSnapshot)
            peer.send(STATE, snapshot)
```

<!-- D10-S5.3 -->### 5.3 Rates, Jitter Buffering, and Replication Classes

**R13.** `TICK_RATE_HZ = 60` (fixed, G2). `SNAPSHOT_RATE_HZ = 20` (configurable per server, D03-S4.2). Inputs are sent at 60 Hz.

**R14.** Replication classes control per-entity send frequency:

| Class | Entities | Frequency |
|---|---|---|
| `HIGH_FREQ` | Vehicles (transform, velocity) | Every snapshot (20 Hz) |
| `LOW_FREQ` | Part health and damage state | Every snapshot, delta only (usually empty) |
| `EVENT_ONLY` | Structural events, spawns, score | On change, CONTROL channel |

```pseudo
# Jitter buffer: the server holds a small window of client inputs so that network
# jitter does not translate into missed ticks.
class InputBuffer:
    RingBuffer<InputCommand> commands       # keyed by commandTick
    int targetDelayTicks = 3                # ~50 ms; adapts to measured jitter

    function selectFor(serverTick):
        wanted = serverTick - targetDelayTicks
        cmd = commands.closestTo(wanted, maxDistance = 4)
        adaptDelay()                        # grow on misses, shrink slowly on clean runs
        return cmd

    function adaptDelay():
        if missRateOver(last 120 ticks) > 0.02: targetDelayTicks = min(targetDelayTicks+1, 10)
        else if missRateOver(last 600 ticks) == 0: targetDelayTicks = max(targetDelayTicks-1, 1)

# R15. A missing input REPEATS the previous movement input but ZEROES the fire mask.
#      Repeating movement keeps the vehicle behaving plausibly through a dropped packet;
#      repeating fire would let a lagging client shoot without asking.
```

<!-- D10-S5.4 -->### 5.4 Delta Compression

```pseudo
function buildSnapshot(world, peer, baseline):
    snap = Snapshot(serverTick = world.tick, baselineTick = baseline?.tick ?? 0)

    for entity in world.family(NetworkReplicated).iterate():      # ascending id (G3)
        if not isRelevantTo(peer, entity): continue               # relevance, D10-S5.10
        base = baseline?.stateOf(entity.networkId)

        mask = 0; deltas = []
        for component in entity.replicatedComponents.sortedBy(typeId):
            if base == null or not component.equalsForReplication(base[component.typeId]):
                mask |= bit(component.typeId)
                deltas.append(encodeDelta(component, base?[component.typeId]))
        if mask != 0: snap.entities.append(Entity(entity.networkId, mask, deltas))

    peer.pendingSnapshots.store(snap)         # kept until ACKed, for future baselines
    return snap

function onSnapshotAck(peer, ackedTick):
    peer.lastAckedSnapshot = peer.pendingSnapshots.get(ackedTick)
    peer.pendingSnapshots.dropOlderThan(ackedTick)

# R16. Baselines are per-peer: each client ACKs what it actually received, and the
#      server deltas against that specific snapshot. A shared baseline would force a
#      full snapshot whenever any client lost a packet.
# R17. If a peer has not ACKed within SNAPSHOT_HISTORY = 64 snapshots (3.2 s), the
#      server sends a FULL snapshot (baselineTick = 0) and resets the baseline. This
#      guarantees recovery from any loss pattern without an explicit resync protocol.
# R18. Applying a delta requires the exact baseline. A client that receives a delta
#      against a baseline it does not have DISCARDS it and NACKs, rather than applying
#      it to a wrong base (which would corrupt state silently).
```

<!-- D10-S5.5 -->### 5.5 Client Prediction and Reconciliation

```pseudo
function clientTick(world, tick):
    # ---- 1. Predict own vehicle -----------------------------------------------
    input = sampleInput(tick)
    cmd   = InputCommand(sequence = nextSeq++, commandTick = tick, ...input)
    pendingInputs.push(cmd)                                   # ring buffer, capacity 128
    applyToEntity(localVehicle, cmd)
    world.tick(tick)                                          # predicted systems only
                                                              # (D03-S5.2: no DamageSystem)
    predictedStates.store(tick, snapshotOf(localVehicle))     # for later comparison
    transport.send(STATE, cmd.withRedundancy(pendingInputs.last(INPUT_REDUNDANCY)))

    # ---- 2. Apply authoritative snapshots --------------------------------------
    while (snap = receivedSnapshots.poll()) != null:
        if snap.serverTick <= lastAppliedServerTick: continue  # stale; discard (G16)
        if snap.baselineTick != 0 and not haveBaseline(snap.baselineTick):
            nack(snap.baselineTick); continue                  # D10-R18
        applySnapshot(world, snap)                             # remote entities: direct
        lastAppliedServerTick = snap.serverTick
        reconcile(snap)

function reconcile(snap):
    ackedSeq  = snap.inputAck.lastProcessedSequence
    ackedTick = snap.inputAck.lastProcessedTick
    pendingInputs.dropThrough(ackedSeq)

    authoritative = snap.stateOf(localVehicle.networkId)
    predicted     = predictedStates.get(ackedTick)
    if predicted == null: return                               # too old; accept the server

    posError = distance(predicted.position, authoritative.position)
    rotError = angleBetween(predicted.rotation, authoritative.rotation)

    if posError <= RECONCILE_POS_THRESHOLD_M (0.05)
       and rotError <= RECONCILE_ROT_THRESHOLD_RAD (0.02):
        return                                                 # prediction was good enough

    # ---- 3. Rewind and replay --------------------------------------------------
    metrics.increment("net.reconcile"); metrics.record("net.reconcile.error", posError)
    setState(localVehicle, authoritative)                      # snap to the server's truth
    world.physics.syncBodyFromComponents(localVehicle)
    for cmd in pendingInputs.inOrder():                        # replay unacked inputs
        applyToEntity(localVehicle, cmd)
        world.stepPhysicsOnly(TICK_DT)                         # only the local vehicle's
                                                               # body needs re-simulating
        predictedStates.store(cmd.commandTick, snapshotOf(localVehicle))

    # ---- 4. Smooth the visible correction --------------------------------------
    # Snapping the SIMULATION is correct; snapping the CAMERA is not. The renderer
    # carries a decaying visual offset so a correction is felt as a nudge, not a jump.
    visualOffset = predicted.position - localVehicle.position
    visualOffsetDecayPerTick = 0.85                            # ~0.3 s to settle

# R19. Only the LOCAL vehicle is predicted. Remote vehicles are interpolated (D10-S5.6).
#      Predicting remote vehicles would compound errors and produce rubber-banding on
#      every collision.
# R20. Damage is NEVER predicted. The client does not run DamageSystem (D03-S5.2), so
#      it can never show a part destroyed that the server kept alive — the one
#      inconsistency players find unacceptable (P1).
# R21. Firing IS predicted visually (muzzle flash, tracer, sound) but the hit is not.
#      A tracer that hits nothing until the server confirms feels responsive and cannot
#      lie about outcomes.
```

<!-- D10-S5.6 -->### 5.6 Entity Interpolation

```pseudo
INTERP_DELAY_MS = 100          # two snapshot intervals at 20 Hz, plus margin

function InterpolationSystem.update(world, renderTimeMs):
    target = renderTimeMs - INTERP_DELAY_MS
    for e in world.family(Interpolation, Transform).iterate():
        (a, b) = e.buffer.bracketing(target)
        if b == null:
            # Extrapolate briefly rather than freeze; freezing reads as a teleport
            # when the next snapshot lands.
            dt = min(target - a.time, EXTRAPOLATE_MAX_MS (150))
            e.renderTransform = a.transform + a.velocity * dt
            e.isExtrapolating = true
        else:
            t = (target - a.time) / (b.time - a.time)
            e.renderTransform.position = lerp(a.position, b.position, t)
            e.renderTransform.rotation = slerp(a.rotation, b.rotation, t)

# R22. Interpolation affects RENDERING only. The physics body of a remote vehicle is
#      set from the latest snapshot so collisions and ray tests use current state.
#      Mixing the two would make a remote vehicle collide with where it used to be.
```

<!-- D10-S5.7 -->### 5.7 Lag Compensation

```pseudo
# The server rewinds the world to what the shooter SAW, validates the shot there, then
# applies the result in the present.

HISTORY_TICKS = 60                    # 1 second of history

class HitboxHistory:
    RingBuffer<TickSnapshot> buffer    # per vehicle: transform + per-part slot transforms
                                       # + which parts were still attached

function onShotReceived(server, shooter, shot):
    # 1. Determine the shooter's view time.
    rewindTicks = clamp(shooter.rttTicks / 2 + INTERP_DELAY_TICKS, 0, HISTORY_TICKS)
    viewTick    = server.tick - rewindTicks
    metrics.record("net.lagcomp.rewind", rewindTicks)

    # 2. Rewind only the candidate targets, not the whole world.
    candidates = vehiclesWithinCone(shot.origin, shot.direction, shot.maxRange)
    saved = {}
    for v in candidates.sortedBy(networkId):
        saved[v] = v.currentHitboxState()
        v.applyHitboxState(HitboxHistory[v].at(viewTick))

    # 3. Resolve the shot against the rewound state.
    hit = resolveShot(shot, candidates)          # D07-S5.1 hit resolution, unchanged

    # 4. Restore and apply damage in the PRESENT.
    for v in candidates.sortedBy(networkId): v.applyHitboxState(saved[v])
    if hit != null:
        # The struck part may have been destroyed since viewTick. Rule: the hit counts,
        # but it applies to the part's CURRENT state. If the part is already destroyed,
        # the damage is discarded (D07-R12). This is the one place where "favour the
        # shooter" is deliberately NOT absolute — resurrecting a destroyed part to
        # absorb damage would be more confusing than losing the shot.
        applyDamage(world, damageEventFrom(hit, tick = server.tick))
        send HitConfirm to shooter; send DamageReceived to victim

# R23. Rewind is capped at HISTORY_TICKS (1 s). A client claiming more latency than
#      that is clamped, which bounds the "shot behind cover" experience for victims.
# R24. Only hit RESOLUTION is rewound. Physics, scoring, and destruction all happen in
#      the present. Rewinding the simulation itself would be unbounded in cost and would
#      break G2's single forward timeline.
# R25. History records which parts were ATTACHED at each tick, so a shot at a wheel that
#      has since detached correctly misses the vehicle rather than hitting empty space
#      where the wheel used to be.
```

<!-- D10-S5.8 -->### 5.8 Connection Lifecycle

```pseudo
STATE MACHINE (client side):
  DISCONNECTED --connect()--> CONNECTING --ServerHello(accepted)--> SYNCING
  SYNCING --all spawn messages received + first snapshot applied--> PLAYING
  PLAYING --Disconnect / timeout--> DISCONNECTED
  any --Reject / error--> DISCONNECTED

function handshake(client, server):
    # 1. CONNECT
    client -> ClientHello { protocolVersion, contentHash, clientVersion, playerName }
    if server.protocolVersion != msg.protocolVersion:
        server -> Reject(PROTOCOL_MISMATCH, "server {} client {}"); close; exit 76
    if server.contentHash != msg.contentHash:
        server -> Reject(CONTENT_MISMATCH, both hashes); close; exit 76
    if server.peerCount >= maxPlayers:  server -> Reject(SERVER_FULL); close
    if server.isBanned(peer):           server -> Reject(BANNED); close

    # 2. HANDSHAKE
    server -> ServerHello { accepted, peerId, serverTick, matchConfig }
    server -> MatchConfig { rules, arenaId, matchSeed, teams }

    # 3. SYNC — full world state, on CONTROL so nothing is lost.
    for entity in server.world.replicatedEntities.sortedBy(networkId):
        server -> SpawnEntity(entity)
    server -> ScoreUpdate(full)
    server -> MatchPhase(current)
    server -> Snapshot(baselineTick = 0)          # full snapshot
    client: apply everything; align local tick to serverTick + estimatedOneWayTicks

    # 4. PLAY
    client -> SelectVehicle(vehicleTypeId)         # validated (D10-S5.9)
    server -> SpawnEntity(client's vehicle)
    client enters PLAYING; input flows at 60 Hz

    # 5. DISCONNECT
    either side -> Disconnect(reason)
    server: destroy the peer's vehicle after DISCONNECT_GRACE_TICKS (180 = 3 s), so a
            brief network blip does not lose the player's vehicle; retain their score
            for the scoreboard as "(left)" (D01-E8)

TIMEOUTS:
    handshake        10 s
    no packet from peer  15 s  -> Disconnect(TIMEOUT)
    no packet from server 15 s -> Disconnect(TIMEOUT), exit to menu
```

<!-- D10-S5.9 -->### 5.9 Anti-Cheat Boundaries and Input Validation

**R26.** Trust model, stated plainly:

| Trusted | Not trusted |
|---|---|
| The server's own simulation | Any value a client sends |
| Content hashes matching (integrity check, not security) | Client-reported positions, velocities, health, damage, kills |
| Timing measured by the server | Client-reported timestamps or latency |
| — | Client-reported hit results |

```pseudo
function validateInput(cmd, peer):
    # 1. Range clamping. Never reject silently — clamp and count.
    cmd.throttle = clamp(cmd.throttle, -1, 1)
    cmd.steer    = clamp(cmd.steer,    -1, 1)
    cmd.brake    = clamp(cmd.brake,     0, 1)
    cmd.aimPitchRad = clamp(cmd.aimPitchRad, MIN_PITCH, MAX_PITCH)
    if wasClamped: peer.suspicion += 1                     # a legit client never needs this

    # 2. Temporal validation.
    if cmd.commandTick > server.tick + MAX_FUTURE_TICKS (10):
        drop(cmd); peer.suspicion += 5                     # claiming the future = speed hack
    if cmd.commandTick < server.tick - HISTORY_TICKS:
        drop(cmd)                                           # too old to matter

    # 3. Rate limiting.
    if peer.inputsThisSecond > TICK_RATE_HZ * 1.5:
        drop(cmd); peer.suspicion += 2

    # 4. Fire validation is NOT here — it is implicit. The server owns cooldowns, ammo,
    #    and heat (D04-S4.3), so a client that sets fireMask every tick simply fires at
    #    its weapon's real rate. There is nothing to validate because there is nothing
    #    to gain.

function validateVehicleSelection(peer, vehicleTypeId):
    if not assemblies.contains(vehicleTypeId):        return DEFAULT_VEHICLE
    if not peer.hasUnlocked(vehicleTypeId):           return DEFAULT_VEHICLE   # D01-E13
    if world.match.phase not in {LOBBY, ACTIVE}:      return REJECT
    return vehicleTypeId

# R27. Suspicion is logged and exposed to admins; it never auto-bans. False positives
#      from packet reordering are common enough that automatic action would kick real
#      players.
# R28. Aimbots, wallhacks, and input automation are OUT OF SCOPE for v1 (NG5). The
#      architecture bounds their damage — a cheater cannot teleport, cannot fire faster
#      than their weapon allows, cannot deal damage the server did not compute, and
#      cannot survive damage the server did apply.
# R29. Relevance filtering (D10-S5.10) also limits wallhacks: a client is not told about
#      entities it has no plausible way to perceive.
```

<!-- D10-S5.10 -->### 5.10 Relevance Filtering

```pseudo
function isRelevantTo(peer, entity):
    if entity.networkId == peer.vehicleNetworkId:            return true    # always
    if entity.archetype == MATCH:                            return true
    if entity.archetype == PROJECTILE:
        return distance(entity, peer.vehicle) < PROJECTILE_RELEVANCE_M (250)
    if entity.archetype == VEHICLE:
        d = distance(entity, peer.vehicle)
        if d > VEHICLE_RELEVANCE_M (400):                    return false
        return true                                          # arenas are small; distance
                                                             # alone is enough in v1
    if entity.archetype == DEBRIS:                           return false   # never replicated
    return false

# R30. Relevance is DISTANCE-based only in v1. Line-of-sight culling would be stronger
#      anti-wallhack but risks popping when a vehicle rounds a corner, and needs
#      occlusion queries the server does not otherwise run. Recorded as a deliberate
#      v1 limitation.
```

<!-- D10-S5.11 -->### 5.11 Listen Server vs Dedicated Server

| Aspect | Listen server (`HOSTED_MULTIPLAYER`) | Dedicated server (`DEDICATED_SERVER`) |
|---|---|---|
| Authority | The hosting client | The server process |
| Host's own player | `LoopbackTransport`: zero latency, no prediction needed, no interpolation | n/a |
| Remote players | Full prediction/reconciliation/interpolation | Same |
| Rendering | Yes (host renders) | No |
| Tick source | The host's render loop drives the accumulator (D03-S5.3) | Dedicated tick loop with sleep (D03-S5.4) |
| Host advantage | Real and unavoidable: zero latency for the host | None |
| Migration on host quit | Not supported in v1; the match ends | n/a |
| Performance | Rendering competes with simulation; a frame spike is a tick spike for everyone | Simulation only; steadier |

**R31.** The host's zero latency is a genuine competitive advantage and is not compensated for. Adding artificial host latency was considered and rejected: it degrades the host's experience to make an unmeasurable fairness gain, and dedicated servers exist for competitive play.

**R32.** Both modes run the **same** `game-core` authority code through the same `Transport` interface (D02-R19). There is no listen-server-specific replication path.

---

<!-- D10-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D10-1.** Exactly one process is authority per match in every mode.
- [ ] **AC-D10-2.** No client message ever directly sets an authoritative field; all client input passes through `validateInput` (G15).
- [ ] **AC-D10-3.** Every field in D10-S4.3 is replicated with the stated encoding; every field in D10-S4.4 (R6) is never sent (verified by a wire-capture test).
- [ ] **AC-D10-4.** The replication tables here and the classification in D07-S4.2 agree (CI cross-check).
- [ ] **AC-D10-5.** Applying the same snapshot twice yields identical state (G16).
- [ ] **AC-D10-6.** Out-of-order snapshots are discarded by tick number, never applied.
- [ ] **AC-D10-7.** A delta whose baseline is missing is discarded and NACKed, never applied to a wrong base.
- [ ] **AC-D10-8.** A client that misses 64 snapshots recovers via a full snapshot without manual intervention.
- [ ] **AC-D10-9.** Prediction error above threshold triggers rewind-and-replay; below threshold does not.
- [ ] **AC-D10-10.** Damage is never predicted client-side; `DamageSystem` is absent from the client schedule.
- [ ] **AC-D10-11.** Structural destruction events arrive reliably and in order; a client never ends a match with a different part set than the server.
- [ ] **AC-D10-12.** Client-computed vehicle mass and COM match the server's within `MASS_DELTA_FRAC` / `COM_OFFSET_M` throughout a match.
- [ ] **AC-D10-13.** Lag compensation rewinds at most `HISTORY_TICKS` and restores state exactly afterwards.
- [ ] **AC-D10-14.** Rewound hit resolution accounts for parts that were attached at `viewTick`.
- [ ] **AC-D10-15.** Handshake rejects protocol and content mismatches, reporting both values.
- [ ] **AC-D10-16.** A 3-second disconnect blip does not destroy the player's vehicle.
- [ ] **AC-D10-17.** Bandwidth for a 12-player match stays within budget (D12-S5.6): ≤ 128 kbit/s down, ≤ 32 kbit/s up per client, at 20 Hz.
- [ ] **AC-D10-18.** Debris is never replicated (zero debris entities in any wire capture).
- [ ] **AC-D10-19.** Loopback (single-player, host) uses the same replication code path as remote peers.
- [ ] **AC-D10-20.** Input loss of up to 6 consecutive packets causes no dropped input (redundancy window).

---

<!-- D10-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | Client's clock drifts ahead of the server | Inputs with `commandTick > server.tick + 10` are dropped and suspicion is incremented; the client resyncs its tick offset from `InputAck`. |
| E2 | 30% packet loss on STATE | Gameplay continues: input redundancy covers input loss; the next snapshot supersedes lost snapshots. Reconciliation frequency rises; the game is playable. |
| E3 | 100% loss for 3 s | Client shows a connection-warning HUD, keeps predicting; on recovery, a full snapshot resyncs. If loss exceeds 15 s, disconnect with `TIMEOUT`. |
| E4 | CONTROL channel stalls (TCP head-of-line blocking) | Structural events are delayed but never lost. Clients may briefly show a part that the server has destroyed; the STATE channel's health values already show it at 0, so the visual state is right even before the structural event lands. |
| E5 | Client NACKs a baseline repeatedly | After 3 NACKs the server sends a full snapshot and resets that peer's baseline. |
| E6 | Reconciliation triggers every tick (persistent divergence) | Log at WARN with the error magnitude; if it persists for 300 ticks, force a full state resync of the local vehicle. This is a bug signal, and it must be visible in metrics. |
| E7 | A shot arrives for an entity that has since despawned | Resolve against the rewound state; if the target no longer exists, discard the hit and confirm nothing. |
| E8 | Part destroyed between `viewTick` and now | The hit counts but applies to the present state; damage to a destroyed part is discarded (D07-R12, D10-R25). |
| E9 | Two clients report hits on the same part in the same tick | Both are resolved server-side in `peerId` order; the first to reduce health to 0 gets the kill credit (D01-S5.4). |
| E10 | Host quits a listen server | Match ends; all clients receive `Disconnect(HOST_LEFT)` and return to the menu. No host migration in v1. |
| E11 | Server overloaded, ticks behind | Snapshots are still sent at the configured rate but carry a stale `serverTick`; clients see it as latency. Server logs the overload (D03-E3). |
| E12 | Snapshot exceeds MTU | Split across multiple UDP datagrams with a fragment header and reassembly; a snapshot with any missing fragment is dropped whole (never partially applied). |
| E13 | A client sends an `AdminCommand` without authentication | Rejected, logged, suspicion +10. |
| E14 | `contentHash` matches but a part file differs (hash collision or tampering) | Not detectable; out of scope. The hash exists to catch honest version skew, not tampering (D10-R26). |
| E15 | Late joiner during `ACTIVE` | Full sync via the handshake, then spawns at the next respawn opportunity (D01-R24). |
| E16 | Player disconnects mid-air with a vehicle in flight | Vehicle continues simulating for `DISCONNECT_GRACE_TICKS`, then is destroyed and its parts detach as debris. |
| E17 | Snapshot arrives for a `NetworkId` the client has not spawned | Buffer up to `SPAWN_GRACE_TICKS` (D04-E9), then discard with one log line. |
| E18 | Client and server disagree on `component_types.txt` | Caught at handshake by `contentHash`; connection rejected. |

---

<!-- D10-S8 -->## 8. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D10-1 | 12-player headless match, 5 minutes, capture the wire | Bandwidth within budget; zero debris entities; zero morph-weight fields |
| T-D10-2 | Apply the same snapshot twice | Identical state |
| T-D10-3 | Deliver snapshots out of order | Older ones discarded by tick |
| T-D10-4 | Drop the baseline snapshot, then deliver a delta | Delta discarded, NACK sent, full snapshot follows |
| T-D10-5 | Drop 64 consecutive snapshots | Full snapshot recovery; state converges within one snapshot interval |
| T-D10-6 | 150 ms RTT, straight-line driving | Prediction error < 0.05 m; no reconciliation |
| T-D10-7 | 150 ms RTT, collision with another vehicle | Reconciliation fires; visual correction settles within 0.3 s; no teleport |
| T-D10-8 | Client attempts to send a health value | No such message exists; the field is not writable from the wire |
| T-D10-9 | Client sends `throttle = 50` | Clamped to 1; suspicion incremented |
| T-D10-10 | Client sends `commandTick = server.tick + 600` | Dropped; suspicion +5 |
| T-D10-11 | Client requests a locked vehicle | Default substituted; no desync |
| T-D10-12 | Destroy 30 parts across a match, compare client vs server part sets each tick | Identical at all times |
| T-D10-13 | Compare client vs server vehicle mass each tick | Within `MASS_DELTA_FRAC` |
| T-D10-14 | Shoot a target at 200 ms RTT while it strafes | Hit registers where the shooter saw it; rewind ≤ `HISTORY_TICKS` |
| T-D10-15 | Shoot a wheel that detached 200 ms ago | Miss, not a phantom hit |
| T-D10-16 | Protocol version mismatch | `Reject(PROTOCOL_MISMATCH)` with both versions; client exits 76 |
| T-D10-17 | Content hash mismatch | `Reject(CONTENT_MISMATCH)` with both hashes |
| T-D10-18 | Disconnect for 2 s, reconnect | Vehicle preserved (within grace); state resynced |
| T-D10-19 | Disconnect for 10 s | Vehicle destroyed; parts become debris; score retained as "(left)" |
| T-D10-20 | Drop 6 consecutive input packets | No input lost (redundancy) |
| T-D10-21 | Drop 10 consecutive input packets | Movement repeats, fire mask zeroed; no unintended firing |
| T-D10-22 | Single-player vs hosted-single-player, same seed and inputs | Identical authoritative state (proves one code path) |
| T-D10-23 | Snapshot larger than MTU | Fragmented and reassembled; a missing fragment drops the whole snapshot |
| T-D10-24 | Cross-check D10-S4.3/R6 against D07-S4.2 | No field classified differently in the two documents |

---

<!-- D10-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| G1, G6, G15, G16 | `docs/00_master_index.md#D00-S5.2` |
| `SNAPSHOT_RATE_HZ`, `TICK_RATE_HZ` | `docs/00_master_index.md#D00-S6.4` |
| Score attribution from destruction | `docs/01_product_game_design.md#D01-S5.4` |
| Transport abstraction and KryoNet | `docs/02_technical_architecture.md#D02-S4.3` |
| Loopback transport / one code path | `docs/02_technical_architecture.md#D02-S5.3` |
| Runtime mode capability matrix | `docs/03_runtime_modes.md#D03-S4.1` |
| Mode system sets (client lacks DamageSystem) | `docs/03_runtime_modes.md#D03-S5.2` |
| `NetworkId` never recycles | `docs/04_entity_component_model.md#D04-S6.2` |
| `Replicable` hook and cosmetic ban | `docs/04_entity_component_model.md#D04-S5.8` |
| Component type registry hashing | `docs/04_entity_component_model.md#D04-S6.3` |
| Stat aggregation derived on both sides | `docs/05_vehicle_part_system.md#D05-S5.6` |
| Determinism limits forcing state replication | `docs/06_physics_simulation.md#D06-S5.8` |
| Hit resolution reused under rewind | `docs/07_damage_destruction_model.md#D07-S5.1` |
| Authoritative vs cosmetic table | `docs/07_damage_destruction_model.md#D07-S4.2` |
| Destruction replication requirements | `docs/07_damage_destruction_model.md#D07-S5.9` |
| Bot inputs use the same command structure | `docs/11_ai_bots_and_match_simulation.md#D11-S5.3` |
| Network tests and bandwidth budget | `docs/12_testing_validation_ci.md#D12-S5.6` |
