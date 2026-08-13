<!-- D04-S0 --># 04 — Entity / Component Model

**Document ID:** D04
**Owns:** Entities, components, systems, entity IDs, lifecycle, pooling, system execution order.

---

<!-- D04-S1 -->## 1. Purpose

This document specifies the runtime data model of the simulation: what an entity is, the complete catalogue of components and their fields, the complete catalogue of systems with their read/write sets and fixed execution order, how entities are created and destroyed, how IDs are allocated so they remain stable across the network, and how allocation is pooled to keep per-tick garbage at zero.

Every other document that says "the `HealthComponent`" or "`DamageSystem` runs before `PhysicsSystem`" is referring to definitions here.

---

<!-- D04-S2 -->## 2. Scope

<!-- D04-S2.1 -->### 2.1 In Scope

- Architecture decision: component-based data-oriented model, with justification against alternatives.
- Entity archetypes.
- Component catalogue: every component, every field, type, unit, authority classification.
- System catalogue: every system, its component query, read/write sets, execution slot.
- Entity lifecycle: creation, activation, deferred destruction, pooling.
- Entity and component ID schemes, including network stability.
- Query/family caching and iteration determinism.

<!-- D04-S2.2 -->### 2.2 Non-Goals

- **NG1.** The physics behaviour inside `PhysicsSystem` — see `docs/06_physics_simulation.md#D06-S5`.
- **NG2.** Damage math inside `DamageSystem` — see `docs/07_damage_destruction_model.md#D07-S5`.
- **NG3.** Wire encoding of components — see `docs/10_networking_multiplayer.md#D10-S4.4`.
- **NG4.** Rendering system internals — `game-client` concern; only the interface boundary is specified here.
- **NG5.** A general-purpose reusable ECS library. This model is purpose-built for this game.

---

<!-- D04-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S5.2` | G3 (deterministic ordering), G17 (headless parity), G15 (client authority ban) |
| `docs/00_master_index.md#D00-S4.5` | Identifier conventions (Entity ID vs Asset ID) |
| `docs/02_technical_architecture.md#D02-S4.7` | Package layout (`dev.syndicate.core.ecs/component/system`) |
| `docs/06_physics_simulation.md#D06-S5.4` | The tick contract the scheduler drives |
| External: libGDX `Vector3`, `Quaternion`, `Matrix4` | Math types stored in components |

---

<!-- D04-S4 -->## 4. Data Contracts

<!-- D04-S4.1 -->### 4.1 Architecture Decision

**R1.** The simulation uses a **component-based, data-oriented model with a fixed, explicitly ordered system schedule**, implemented in-house in `dev.syndicate.core.ecs` (an Ashley-style engine: entities are ID + component map, systems iterate cached families).

Alternatives considered:

| Option | Verdict | Reasoning |
|---|---|---|
| **In-house Ashley-style component model** | **CHOSEN** | Gives exactly the control the invariants need: deterministic iteration order by `EntityId` (G3), an explicit system schedule we author (G3), deferred destruction that plays well with Bullet's native lifetime rules (G19), and network-stable IDs (D04-S6). Small enough to own; no dependency risk. |
| Ashley (libGDX's ECS) as-is | Rejected | Family iteration order is insertion-dependent and not guaranteed stable across add/remove churn; entity IDs are not designed for network stability; extending it for pooled deferred destruction would fork it in practice. Its design is the right one, so we adopt the *shape* and own the *code*. |
| Artemis-odb | Rejected | Strong performance, but its bytecode weaving/annotation processing complicates the headless/native build matrix, and its entity ID recycling semantics would need wrapping for network stability anyway. |
| Pure OOP hierarchy (`Vehicle extends GameObject`) | Rejected | Damage/detachment repeatedly changes an object's capability set at runtime (a part loses its weapon behaviour, gains debris behaviour). Composition models that naturally; inheritance does not. |
| Full archetype/SoA ECS (Flecs-style) | Rejected for v1 | Entity counts are modest (hundreds, not 100k). The cache-locality win does not pay for the complexity, and Bullet already owns the hot inner loop. |

**R2.** Components are **data only**: public fields or records, no methods beyond trivial accessors, `reset()`, and validation. All behaviour lives in systems. This is what makes replication (D10) and snapshot/rollback (D10-S5.5) tractable.

**R3.** Systems are **stateless with respect to gameplay**. Any state a system needs across ticks lives in a component or in an explicitly declared, snapshot-able system-owned store (`SystemState`), never in ad-hoc fields.

<!-- D04-S4.2 -->### 4.2 Entity Archetypes

**R4.** An *archetype* is a named, documented component set produced by a factory. Archetypes are conventions, not a runtime type.

| Archetype | Components | Created by |
|---|---|---|
| `VEHICLE` | Transform, RigidBody, VehicleChassis, VehicleStats, SlotGraph, PlayerInput \| BotController, Team, NetworkReplicated, Health(chassis), DamageState(chassis), PartStats(chassis) | `VehicleFactory.spawn()` (D05-S5.2) |
| `PART` | Transform (local), PartRef, PartStats, Health, DamageState, DamageVisual, SlotAttachment, NetworkReplicated, [WeaponController], [WheelController], [FractureData] | `VehicleFactory` while building a vehicle |
| `PROJECTILE` | Transform, RigidBody \| BallisticMotion, ProjectileStats, Owner, Lifetime, NetworkReplicated | `WeaponSystem` |
| `DEBRIS` | Transform, RigidBody, Lifetime, DebrisTag | `FractureSystem`, `DetachSystem` (D07-S5.6) |
| `PLAYER` | PlayerIdentity, Team, Score, ConnectionRef, [ControlledVehicleRef] | `MatchSystem` on join |
| `MATCH` | MatchState, MatchClock, MatchRules, MatchScoreboard, RandomSource | `WorldFactory` (singleton entity) |
| `ARENA` | Transform, StaticCollision, SpawnPoints, ArenaRef | `WorldFactory` |
| `EFFECT` (client only) | Transform, ParticleRef, Lifetime | Client-side effect systems; never replicated (G6) |

**R5.** The `MATCH` entity is a singleton: exactly one exists per world, with the reserved `EntityId` `1`. `EntityId` `0` is reserved as the null/invalid entity and is never allocated.

<!-- D04-S4.3 -->### 4.3 Component Catalogue

Legend for **Auth**: `A` = authoritative (replicated, gameplay-relevant, G6); `C` = cosmetic (client-local, never replicated); `L` = local infrastructure (exists on all peers but derived, not sent).

<!-- D04-S4.3.1 -->#### 4.3.1 Spatial and Physics

| Component | Field | Type | Unit | Auth | Notes |
|---|---|---|---|---|---|
| `TransformComponent` | `position` | `Vector3` | m | A | World space for root entities; parent-local for parts. |
| | `rotation` | `Quaternion` | — | A | Unit quaternion, `(x,y,z,w)`. |
| | `scale` | `Vector3` | — | A | Uniform in practice; non-uniform scale is rejected by asset validation (D08-S8). |
| | `worldMatrix` | `Matrix4` | — | L | Derived cache, recomputed by `TransformSystem`. |
| | `parent` | `EntityId` | — | A | `0` if root. |
| | `dirty` | `boolean` | — | L | |
| `RigidBodyComponent` | `body` | `btRigidBody` handle | — | L | Native; owned per D02-S5.7. |
| | `motionState` | `btMotionState` handle | — | L | |
| | `shapeKey` | `ShapeCacheKey` | — | L | Reference into `ShapeCache`. |
| | `massKg` | `float` | kg | A | 0 = static. Must be 0 or ≥ `MIN_BODY_MASS_KG`. |
| | `localInertia` | `Vector3` | kg·m² | L | Derived. |
| | `centerOfMassLocal` | `Vector3` | m | L | Derived; updated per G10. |
| | `layer` | `CollisionLayer` | — | A | D06-S4.4. |
| | `mask` | `int` | bitmask | A | D06-S4.4. |
| | `isKinematic` | `boolean` | — | A | |
| `VelocityComponent` | `linear` | `Vector3` | m/s | A | Mirrored from Bullet each tick for systems that must not touch native. |
| | `angular` | `Vector3` | rad/s | A | |
| `StaticCollisionComponent` | `shapes` | `List<ShapeCacheKey>` | — | L | Arena geometry. |
| `BallisticMotionComponent` | `velocity` | `Vector3` | m/s | A | Non-Bullet projectile integration (D07-S5.2). |
| | `gravityScale` | `float` | — | A | |
| | `dragCoefficient` | `float` | — | A | |

<!-- D04-S4.3.2 -->#### 4.3.2 Vehicle and Parts

| Component | Field | Type | Unit | Auth | Notes |
|---|---|---|---|---|---|
| `VehicleChassisComponent` | `assemblyId` | `AssetId` | — | A | Which assembly was spawned. |
| | `chassisPartEntity` | `EntityId` | — | A | The root part entity. |
| | `totalMassKg` | `float` | kg | A | Recomputed on any attach/detach (G10). |
| | `comLocal` | `Vector3` | m | A | Centre of mass in chassis local space. |
| | `wheelEntities` | `EntityId[]` | — | A | Ordered, index = Bullet wheel index. |
| | `currentSteerRad` | `float` | rad | A | The steering angle applied to the steered wheels. Rate-limited toward its target by `VehicleControlSystem` (D06-S5.5), which needs last tick's value; systems hold no cross-tick state (D04-R3). |
| | `vehicleController` | `btRaycastVehicle` handle | — | L | D06-S5.5. |
| `SlotGraphComponent` | `nodes` | `SlotNode[]` | — | A | Tree rooted at chassis; see D05-S4.3. |
| | `parentOf` | `map<EntityId,EntityId>` | — | A | Derived index, rebuilt on structural change. |
| | `structuralVersion` | `int` | — | A | Increments on any attach/detach; used to invalidate caches and trigger replication. |
| `PartRefComponent` | `partTypeId` | `AssetId` | — | A | Which part type this is. |
| | `vehicleEntity` | `EntityId` | — | A | `0` once detached. |
| | `slotPath` | `string` | — | A | Stable path from chassis, e.g. `root/turret_00/barrel_01` (D05-S4.3). |
| `PartStatsComponent` | `baseStats` | `StatBlock` | — | A | Immutable copy of the part type's stats. |
| | `effectiveStats` | `StatBlock` | — | A | After degradation (D05-S5.4). Recomputed when health changes. |
| | `category` | `PartCategory` | — | A | |
| | `materialId` | `AssetId` | — | A | Drives density and damage-type modifiers. |
| `SlotAttachmentComponent` | `parentEntity` | `EntityId` | — | A | |
| | `slotId` | `string` | — | A | Slot name on the parent. |
| | `localTransform` | `Transform` | — | A | Attachment offset from slot definition. |
| | `constraintHandle` | `btTypedConstraint` handle | — | L | Present only if the part is a separate body (D06-S5.6). |
| | `breakImpulseN` | `float` | N·s | A | Joint break threshold (D07-S5.7). |
| `VehicleStatsComponent` | `maxSpeedMps` | `float` | m/s | A | Aggregated (D05-S5.6). |
| | `accelerationMps2` | `float` | m/s² | A | |
| | `maxSteerRad` | `float` | rad | A | Steering lock, the mean over live steering wheels. Assigned by D05-S5.6 phase 3; without it there is nothing for `VehicleControlSystem` to scale a steering input by. |
| | `steerRateRadPerSec` | `float` | rad/s | A | |
| | `engineForceN` | `float` | N | A | Traction-limited force, applied at low speed. |
| | `enginePowerW` | `float` | W | A | Caps `engineForceN` at speed: available force is `min(engineForceN, enginePowerW / v)` (D05-R16). |
| | `brakeForceN` | `float` | N | A | |
| | `armorRatingAvg` | `float` | — | A | |
| | `downforceCoefficient` | `float` | N/(m/s)² | A | The chassis part's downforce (D06-S4.5), carried here so `VehicleControlSystem` needs no asset lookup. |
| | `powerBudget` | `float` | — | A | D05-S5.7. |
| | `dirty` | `boolean` | — | L | Set by `DamageSystem`, cleared by `VehicleStatsSystem`. |

<!-- D04-S4.3.3 -->#### 4.3.3 Health and Damage

| Component | Field | Type | Unit | Auth | Notes |
|---|---|---|---|---|---|
| `HealthComponent` | `maxHp` | `float` | HP | A | From part type. |
| | `currentHp` | `float` | HP | A | Clamped `[0, maxHp]`. |
| | `healthFraction` | `float` | — | A | Derived `currentHp/maxHp`; kept as a field to avoid repeated division. |
| | `armorValue` | `float` | — | A | Flat mitigation, D07-S5.2. |
| | `lastDamageTick` | `TickNumber` | tick | A | For damage-over-time and scoring attribution. |
| | `lastAttacker` | `EntityId` | — | A | |
| | `lastHitNormalX/Y/Z` | `float` | — | A | The contact normal of the most recent direct hit, world space. Recorded by `DamageSystem` (12) and spent by `DetachSystem` (14) as the detach kick of D07-S5.7; a system field would be the cross-tick state D04-R3 prohibits. |
| `BurnStackComponent` | `remainingS` | `float[5]` | s | A | Seconds left on each live incendiary stack (D07-R8). Per-stack rather than one refreshed timer, so one touch cannot keep five stacks alive. |
| | `stackCount` | `int` | — | A | How many entries of `remainingS` are live; capped at 5. |
| | `lastAttacker` | `EntityId` | — | A | Who lit it, so a part that burns down still credits somebody. |
| `DamageStateComponent` | `state` | `DamageState` | enum | A | `INTACT/DAMAGED/CRITICAL/DESTROYED/DETACHED`. |
| | `stateEnteredTick` | `TickNumber` | tick | A | |
| | `stateVersion` | `int` | — | A | Increments on transition; drives delta replication (D10-S5.4). |
| `DamageVisualComponent` | `morphWeights` | `float[4]` | — | **C** | Shape key weights (D07-S5.5). Client-local. |
| | `targetMorphWeights` | `float[4]` | — | C | Interpolation target. |
| | `charLevel` | `float` | — | C | Scorch/decal blend. |
| | `emissiveFireLevel` | `float` | — | C | |
| `FractureDataComponent` | `manifestRef` | `AssetId` | — | A | Which fracture manifest to use. |
| | `shardCount` | `int` | — | A | |
| | `hasFractured` | `boolean` | — | A | One-way (G8/G9). |
| `DebrisTagComponent` | `sourcePartEntity` | `EntityId` | — | L | For debugging/telemetry. |
| | `spawnTick` | `TickNumber` | tick | A | |

<!-- D04-S4.3.4 -->#### 4.3.4 Control, Weapons, AI

| Component | Field | Type | Unit | Auth | Notes |
|---|---|---|---|---|---|
| `PlayerInputComponent` | `throttle` | `float` | `[-1,1]` | A (as intent) | Client-produced input command (D10-S4.4). |
| | `steer` | `float` | `[-1,1]` | A | |
| | `brake` | `float` | `[0,1]` | A | |
| | `aimYawRad` / `aimPitchRad` | `float` | rad | A | |
| | `fireMask` | `int` | bitmask | A | Bit per weapon group. |
| | `commandTick` | `TickNumber` | tick | A | Tick this input was produced for. |
| | `sequence` | `int` | — | A | Monotonic per client; drives acknowledgement (D10-S5.5). |
| `WeaponControllerComponent` | `weaponTypeId` | `AssetId` | — | A | |
| | `cooldownRemainingS` | `float` | s | A | |
| | `baseFireIntervalS` | `float` | s | A | |
| | `effectiveFireIntervalS` | `float` | s | A | After degradation. |
| | `ammoRemaining` | `int` | — | A | `-1` = unlimited. |
| | `heat` | `float` | `[0,1]` | A | |
| | `groupIndex` | `int` | — | A | Which `fireMask` bit fires it. |
| | `muzzleLocal` | `Vector3` | m | A | |
| `ProjectileComponent` | `damageType` | `DamageType` | enum | A | What the shot delivers on impact (D07-S4.3). |
| | `damageAmount` | `float` | HP | A | Frozen at the muzzle, never re-read from the weapon: a shot in flight keeps its damage after its launcher is destroyed (D01-E6). |
| | `blastRadiusM` | `float` | m | A | 0 for a point hit; positive for an explosive, which damages every part inside it (D07-E3). |
| | `maxRangeM` / `travelledM` | `float` | m | A | A shot that reaches its range is spent (D06-S5.9). |
| | `shooterVehicleEntity` | `EntityId` | — | A | For the friendly-fire test (D01-E9). |
| | `sourceWeaponGroup` | `int` | — | A | For the damage ledger; `-1` when nothing fired it. |
| `WheelControllerComponent` | `wheelIndex` | `int` | — | A | Index into `btRaycastVehicle`. |
| | `isSteering` / `isDriven` | `boolean` | — | A | |
| | `radiusM`, `suspensionRestLengthM` | `float` | m | A | D06-S5.5. |
| | `suspensionStiffness`, `dampingRelax`, `dampingCompress`, `frictionSlip`, `rollInfluence` | `float` | — | A | |
| | `effectiveFrictionSlip` | `float` | — | A | After degradation. |
| `BotControllerComponent` | `difficulty` | `BotDifficulty` | enum | A | D11-S4.2. |
| | `behaviorTreeState` | `BtState` | — | A | |
| | `targetEntity` | `EntityId` | — | A | |
| | `reactionDelayS` | `float` | s | A | |
| | `perceivedWorld` | `SensorSnapshot` | — | A | Delayed view (D11-S5.2). |
| | `memory` | `BotMemory` | — | A | Targets seen within `TARGET_MEMORY_S` but not currently visible (D11-R6). |
| | `blackboard` | `BotBlackboard` | — | A | What the tree decided this tick, before the solvers read it (D11-R7). |
| | `aimYawRad` / `aimPitchRad` | `float` | rad | A | Current aim, converging at `aimSettleRate`. |
| | `aimErrorOffset` | `Vector3` | m | A | The bounded random walk of D11-E18. Authoritative because a rollback must reproduce it. |
| | `lastTargetSwitchTick` | `TickNumber` | tick | A | Drives the re-target cooldown (D11-S5.2). |
| | `stuckTicks`, `unstickTicksRemaining` | `int` | tick | A | The `unstick` branch's counters (D11-S5.1). |
| | `patrolIndex` | `int` | — | A | Which arena point of interest the `patrol` branch is heading for. |
| `TeamComponent` | `teamId` | `int` | — | A | `-1` = free-for-all. |
| `OwnerComponent` | `ownerEntity` | `EntityId` | — | A | Attribution for kills/score. |
| `LifetimeComponent` | `remainingS` | `float` | s | A | |
| | `despawnPolicy` | `enum` | — | A | `DESTROY`, `FADE`, `SLEEP_THEN_DESTROY`. |

<!-- D04-S4.3.5 -->#### 4.3.5 Networking, Match, Infrastructure

| Component | Field | Type | Unit | Auth | Notes |
|---|---|---|---|---|---|
| `NetworkReplicatedComponent` | `networkId` | `NetworkId` (uint32) | — | A | Stable network identity (D04-S6.2). |
| | `replicationClass` | `enum` | — | A | `HIGH_FREQ`, `LOW_FREQ`, `EVENT_ONLY` (D10-S5.3). |
| | `ownerPeerId` | `int` | — | A | For prediction filtering. |
| | `lastSentTick` | `TickNumber` | tick | L | Server-side only. |
| `InterpolationComponent` | `buffer` | `RingBuffer<TransformSample>` | — | **C** | Client-only smoothing (D10-S5.6). |
| `PredictionComponent` | `pendingInputs` | `RingBuffer<InputCommand>` | — | L | Client-only (D10-S5.5). |
| | `lastAckedTick` | `TickNumber` | tick | L | |
| `MatchStateComponent` | `phase` | `MatchPhase` | enum | A | `LOBBY/COUNTDOWN/ACTIVE/ENDING/RESULTS` (D11-S5.7). |
| | `phaseEnteredTick` | `TickNumber` | tick | A | |
| | `inputEnabled` | `boolean` | — | A | D01-R21/R23's input gate. D11-S5.7 writes it as `world.inputEnabled`; it is match state, because the offline simulator runs many matches in one process (D11-S5.8) and a process-wide flag could not. |
| | `damageEnabled` | `boolean` | — | A | D01-R22's damage gate, same reasoning. |
| | `outcome` | `MatchOutcome` | enum | A | `UNDECIDED` until the win condition fires (D01-S5.5). |
| | `winnerPlayerEntity` / `winnerTeamId` | `EntityId` / `int` | — | A | Whichever the outcome names. |
| | `suddenDeath` | `boolean` | — | A | Latched, because D01-E2 allows exactly one extension. |
| `MatchClockComponent` | `tick` | `TickNumber` | tick | A | Authoritative tick counter. Counts ticks *in `ACTIVE`*, so a long lobby cannot consume the time limit (D11-E11). |
| | `timeLimitTicks` | `int` | tick | A | |
| `MatchRulesComponent` | `mode` | `GameMode` | enum | A | D01-S4.2. |
| | `arenaId` | `AssetId` | — | A | Where the match is fought; a respawn needs the arena's spawn points and slot 4 has no launch configuration (D11-S5.7). |
| | `scoreLimit`, `respawnDelayTicks`, `friendlyFire` | — | — | A | |
| | `noRespawn` | `boolean` | — | A | Set on entering sudden death (D11-S5.7). |
| | `warmupTicks`, `endingTicks`, `resultsTicks` | `int` | tick | A | Phase durations. Fields rather than the literals of D11-S5.7 so a 500-match sweep is not twenty minutes of scoreboard (D11-R14). |
| | `suddenDeathTicks` | `int` | tick | A | Ticks the clock is extended by on a tie, or 0 to declare a draw (D01-E2). |
| | `botCount`, `botDifficulty`, `autoStart` | — | — | A | What `MatchFlowSystem` fills the lobby with, and whether it waits (D11-S5.6, D03-S4.2). |
| `PlayerIdentityComponent` | `playerId` | `int` | — | A | Ascending in join order. Separate from `EntityId` because D11-S5.7 orders players by it and an entity id carries a generation that changes when an index is recycled. |
| | `displayName`, `isBot`, `botDifficulty` | — | — | A | `isBot` exists for the scoreboard and for D11-R11; no gameplay system reads it (G17). |
| | `joinTick` | `TickNumber` | tick | A | D11-R11 removes bots oldest-first, which is lowest here. |
| | `selectedAssemblyId` | `AssetId` | — | A | What this player respawns in. |
| `ControlledVehicleComponent` | `vehicleEntity` | `EntityId` | — | A | The vehicle currently driven, mirrored by `OwnerComponent` on the vehicle. Both directions are stored because scoring walks one at the instant of a kill and respawn walks the other every tick. |
| | `deathTick` | `TickNumber` | tick | A | When the last vehicle was lost, or `NEVER_DIED`. |
| | `spawnRequestedTick` | `TickNumber` | tick | A | When a spawn was last queued, so a refused spawn backs off rather than re-queueing every tick. |
| `RandomSourceComponent` | `matchSeed` | `long` | — | A | D06-S5.5. |
| | `streams` | `map<StreamId, PcgState>` | — | A | One deterministic stream per subsystem. |
| `ScoreComponent` | `kills`, `assists`, `deaths`, `damageDealt`, `objectiveScore` | numeric | — | A | |
| `DamageLedgerComponent` | `ledger` | `DamageLedger` | — | A | Who has damaged which vehicle, match-long and windowed over `ASSIST_WINDOW_TICKS` (D01-S5.4). On the match singleton, because it outlives every vehicle it records damage against — which is the point: the moment it is read is the moment a victim dies. |
| `RenderModelComponent` (client) | `modelInstance` | handle | — | C | Never in `game-core`. |

<!-- D04-S4.4 -->### 4.4 System Catalogue and Execution Order

**R6.** Systems execute in this exact order every tick. The order is a compile-time constant list; it is not derived from registration order or from dependency inference (G3).

| # | Phase | System | Module | Reads | Writes | Modes |
|---|---|---|---|---|---|---|
| 1 | INPUT | `InputCollectionSystem` | client | device state | `PlayerInputComponent` | client |
| 2 | INPUT | `InputReceiveSystem` | core | transport | `PlayerInputComponent` | authority |
| 3 | INPUT | `BotDecisionSystem` | core | world sensors, `BotController` | `PlayerInput`, `BotController` | authority |
| 4 | PRE_SIM | `MatchFlowSystem` | core | `MatchState`, `Score` | `MatchState`, `MatchClock` | authority |
| 5 | PRE_SIM | `SpawnSystem` | core | spawn requests | creates entities | authority |
| 6 | PRE_SIM | `VehicleStatsSystem` | core | `PartStats`, `SlotGraph`, `Health` | `VehicleStats`, `WheelController.effective*`, `WeaponController.effective*` | all |
| 7 | SIM | `VehicleControlSystem` | core | `PlayerInput`, `VehicleStats` | `btRaycastVehicle` engine/steer/brake | all |
| 8 | SIM | `WeaponSystem` | core | `PlayerInput`, `WeaponController` | spawns `PROJECTILE`, updates cooldown/ammo/heat | authority (+ predicted on client) |
| 9 | SIM | `ProjectileSystem` | core | `BallisticMotion`, `Transform` | `Transform`, emits hit events | authority (+ predicted) |
| 10 | SIM | `PhysicsSystem` | core | `RigidBody`, forces | Bullet world step; `Transform`, `Velocity` | all |
| 11 | POST_SIM | `CollisionEventSystem` | core | Bullet manifolds | emits damage events | authority |
| 12 | POST_SIM | `DamageSystem` | core | damage events, `Health`, `SlotGraph` | `Health`, `DamageState`, propagation | authority |
| 13 | POST_SIM | `FractureSystem` | core | `DamageState==DESTROYED`, `FractureData` | destroys part body, spawns `DEBRIS` | authority |
| 14 | POST_SIM | `DetachSystem` | core | `DamageState`, constraint break flags | `SlotGraph`, `VehicleChassis`, spawns debris | authority |
| 15 | POST_SIM | `MassPropertySystem` | core | `SlotGraph.structuralVersion` | `RigidBody` mass/inertia/COM (G10) | all |
| 16 | POST_SIM | `LifetimeSystem` | core | `Lifetime` | marks entities for destruction | all |
| 17 | POST_SIM | `ScoreSystem` | core | kill/damage events | `Score`, `MatchScoreboard` | authority |
| 18 | NET | `NetworkSendSystem` | core | all `NetworkReplicated` | transport out | authority |
| 19 | NET | `NetworkReceiveSystem` | core | transport in | sends this tick's `InputCommand`; applies snapshot, triggers reconciliation | client |
| 20 | NET | `ReconciliationSystem` | core | `Prediction`, snapshot | rewind + replay (D10-S5.5) | client |
| 21 | PRESENT | `TransformSystem` | core | `Transform` tree | `Transform.worldMatrix` | all |
| 22 | PRESENT | `InterpolationSystem` | client | `Interpolation` | render transforms | client |
| 23 | PRESENT | `DamageVisualSystem` | client | `Health`, `DamageState` | `DamageVisual` morph weights | client |
| 24 | PRESENT | `EffectSystem` | client | events | `EFFECT` entities | client |
| 25 | PRESENT | `AudioSystem` | client | events | audio | client |
| 26 | PRESENT | `RenderSystem` | client | render components | draw calls | client |
| 27 | CLEANUP | `EntityDestroySystem` | core | destroy queue | removes entities, disposes natives | all |

**R7.** Phases `INPUT` through `CLEANUP` all run inside one tick. `PRESENT` systems 22–26 run **once per rendered frame**, not once per tick (D03-S5.3); systems 21 and 27 run per tick. This split is the only place tick/frame decoupling appears.

**R8.** A system that is not applicable to the current mode is **absent from the schedule**, not present-but-disabled. `SystemSetFactory.forMode()` (D03-S5.2) builds the list.

<!-- D04-S4.5 -->### 4.5 Core ECS Interfaces

```pseudo
type EntityId  = uint32          # packed: index (low 24 bits) | generation (high 8 bits)
type NetworkId = uint32          # server-assigned, never recycled within a match

interface Component:
    void reset()                 # return to pristine state for pooling

class Entity:
    EntityId id
    long componentMask           # bit per component type, for fast family matching
    Component[] components       # indexed by component type index
    boolean active

class World:
    Entity createEntity()
    void   destroyEntity(EntityId)          # deferred; takes effect in CLEANUP
    Entity get(EntityId)                    # null if id stale (generation mismatch)
    <T> T  getComponent(EntityId, Class<T>)
    void   addComponent(EntityId, Component)
    void   removeComponent(EntityId, Class<?>)
    Family family(ComponentQuery)           # cached, sorted-by-id view
    EventBus events
    RandomSource random

interface System:
    Phase phase()                # INPUT | PRE_SIM | SIM | POST_SIM | NET | PRESENT | CLEANUP
    int   order()                # the fixed number from D04-S4.4
    void  initialize(World)
    void  update(World, float dtSeconds, TickNumber tick)
    void  dispose()
```

**R9.** `ComponentQuery` supports `all(...)`, `any(...)`, `exclude(...)`. Families are cached and updated incrementally on component add/remove, and always expose entities **sorted by ascending `EntityId`** (G3).

---

<!-- D04-S5 -->## 5. Logic & Algorithms

<!-- D04-S5.1 -->### 5.1 Entity Creation

```pseudo
function createEntity(world):
    if world.freeIndices.isNotEmpty():
        index = world.freeIndices.removeFirst()          # FIFO, not LIFO: maximises
                                                          # time before generation reuse
        generation = world.generations[index]             # already incremented at destroy
    else:
        assert world.nextIndex < MAX_ENTITIES : "entity capacity exhausted"
        index = world.nextIndex++
        generation = 0
        world.generations[index] = 0

    id = pack(index, generation)
    entity = world.entityPool.obtain()
    entity.id = id; entity.componentMask = 0; entity.active = true
    world.entities[index] = entity
    world.events.emit(EntityCreated(id))
    return entity

function pack(index, generation):  return (generation << 24) | (index & 0x00FFFFFF)
function isAlive(world, id):
    index = id & 0x00FFFFFF
    return world.entities[index] != null
       and world.generations[index] == (id >> 24)
       and world.entities[index].active
```

**R10.** `MAX_ENTITIES = 16384` per world. Exceeding it is a fatal error, not a silent grow — it means a leak (see D04-S7 E3).

**R11.** Generation is 8 bits and wraps. Wrapping is safe because an ID is only ever compared for liveness against the *current* generation, and stale references live at most a few ticks. Free indices are recycled FIFO to make wrap-collision practically unreachable within a match.

<!-- D04-S5.2 -->### 5.2 Component Add / Remove

```pseudo
function addComponent(world, id, component):
    assert isAlive(world, id)
    typeIndex = ComponentType.indexOf(component.class)
    entity = world.get(id)
    assert entity.components[typeIndex] == null : "duplicate component"
    entity.components[typeIndex] = component
    entity.componentMask |= (1 << typeIndex)
    world.families.onMaskChanged(entity)          # incremental family update
    if component implements NativeOwner: NativeResourceTracker.register(component)

function removeComponent(world, id, type):
    entity = world.get(id)
    typeIndex = ComponentType.indexOf(type)
    component = entity.components[typeIndex]
    if component == null: return
    entity.components[typeIndex] = null
    entity.componentMask &= ~(1 << typeIndex)
    world.families.onMaskChanged(entity)
    if component implements NativeOwner: component.disposeNative()   # G19
    component.reset(); world.componentPools[typeIndex].free(component)
```

**R12.** Component add/remove during a system's iteration is **legal** but takes effect for family membership at the next family query; a system never observes a family mutating underneath its own loop. Families snapshot their entity array at the start of `update()`.

<!-- D04-S5.3 -->### 5.3 Tick Loop and System Scheduling

```pseudo
function tick(world, tickNumber):
    world.currentTick = tickNumber
    world.frameAllocator.reset()                  # per-tick scratch arena, zero GC

    for system in world.schedule:                 # fixed list, D04-S4.4 order
        if system.phase == PRESENT: continue      # PRESENT runs in the render loop
        beginProfile(system)
        system.update(world, TICK_DT, tickNumber)
        endProfile(system)
        assert world.events.deferredOnly() : "systems must emit events, not mutate other systems"

    world.events.dispatchQueued()                 # events queued this tick are visible next tick
    runDestroyQueue(world)                        # EntityDestroySystem, order 27
```

**R13.** Systems communicate **only** through components and the event bus. Direct system-to-system calls are prohibited; this keeps the schedule the single description of causality (G3).

**R14.** Events emitted during tick *N* are dispatched at the end of tick *N* and consumed in tick *N+1*, except for **damage events**, which are emitted in `CollisionEventSystem` (11) and consumed by `DamageSystem` (12) in the same tick via a dedicated same-tick queue. That exception is explicit and is the only one.

<!-- D04-S5.4 -->### 5.4 World Construction

```pseudo
function createWorld(config, assetIndex):
    world = new World(maxEntities = MAX_ENTITIES)
    world.random = new RandomSource(config.matchSeed)          # G4
    world.physics = PhysicsWorld.create(config)                # D06-S5.1
    world.assets  = assetIndex

    matchEntity = world.createEntityWithReservedId(1)
    add MatchStateComponent(phase = LOBBY)
    add MatchClockComponent(tick = 0, timeLimitTicks = config.timeLimitTicks)
    add MatchRulesComponent(config.rules)
    add RandomSourceComponent(matchSeed = config.matchSeed)

    arenaEntity = ArenaFactory.load(world, assetIndex.arena(config.arenaId))
    return world
```

<!-- D04-S5.5 -->### 5.5 Deferred Destruction

```pseudo
function destroyEntity(world, id):
    if not isAlive(world, id): return
    world.get(id).active = false                # immediately invisible to families
    world.destroyQueue.add(id)                  # actual teardown deferred to CLEANUP

function runDestroyQueue(world):
    sort(world.destroyQueue) ascending          # G3: deterministic teardown order
    for id in world.destroyQueue:
        entity = world.entities[index(id)]
        if entity == null: continue             # double-destroy is a no-op, not an error

        # 1. Children first: destroying a vehicle destroys its parts.
        for child in world.childrenOf(id): destroyEntity(world, child)   # recurses into queue

        # 2. Native teardown in dependency order (D02-S5.7).
        if entity has SlotAttachmentComponent.constraintHandle:
            world.physics.removeConstraint(handle); handle.dispose()
        if entity has RigidBodyComponent:
            world.physics.removeRigidBody(body)
            motionState.dispose(); body.dispose()
            # NOTE: the collision shape is NOT disposed here; ShapeCache owns it.

        # 3. Components back to pools.
        for component in entity.components: if component != null:
            component.reset(); pool(component).free(component)

        # 4. ID recycling.
        world.families.onEntityRemoved(entity)
        world.entities[index(id)] = null
        world.generations[index(id)] += 1        # invalidates every outstanding EntityId
        world.freeIndices.addLast(index(id))
        world.entityPool.free(entity)
        world.events.emit(EntityDestroyed(id))

    world.destroyQueue.clear()
```

**R15.** Destruction is always deferred to the CLEANUP phase. No system may destroy an entity whose components another system will read later in the same tick — deferral makes this safe by construction.

**R16.** Destroying a vehicle destroys its part entities. Destroying a part does **not** destroy the vehicle; it detaches (D07-S5.6).

<!-- D04-S5.6 -->### 5.6 Pooling Strategy

```pseudo
# Goal: zero steady-state allocation during a tick (G-supports the frame budget in D12-S5.6).

POOLED:
  Entity objects            -> world.entityPool           (capacity MAX_ENTITIES)
  Every Component type      -> world.componentPools[type]  (grown on demand, never shrunk)
  Event objects             -> per-type event pools; freed after dispatchQueued()
  Vector3/Quaternion scratch-> world.frameAllocator (bump arena, reset each tick)
  InputCommand records      -> ring buffers, fixed capacity 128 per client
  Contact/damage event structs -> preallocated arrays sized MAX_CONTACTS_PER_TICK = 4096

NOT POOLED (allocated at load or match start only):
  Collision shapes (ShapeCache, keyed by (assetId, lodIndex))
  Asset records, PartType definitions, model data
  Snapshot buffers (preallocated per connection at handshake)

RULES:
  R17. reset() must clear every field to its declared default. A pooled component that
       leaks a stale field into a new entity is a correctness bug, not a performance bug.
  R18. Debug builds poison freed components (fill numeric fields with NaN sentinels and
       null references) so use-after-free is caught immediately rather than silently.
  R19. Pool exhaustion grows the pool and logs at WARN with the type and new size;
       repeated growth is a leak signal surfaced in the profiler overlay.
```

<!-- D04-S5.7 -->### 5.7 Family Query Caching

```pseudo
class Family:
    ComponentQuery query
    long allMask, anyMask, excludeMask
    SortedIntSet members            # EntityIds, ascending

    function matches(entity):
        return (entity.componentMask & allMask) == allMask
           and (anyMask == 0 or (entity.componentMask & anyMask) != 0)
           and (entity.componentMask & excludeMask) == 0

    function onMaskChanged(entity):
        was = members.contains(entity.id)
        now = entity.active and matches(entity)
        if now and not was: members.insert(entity.id)      # keeps sorted order
        if was and not now: members.remove(entity.id)

    function iterate():
        return members.toArraySnapshot(world.frameAllocator)   # stable during iteration
```

<!-- D04-S5.8 -->### 5.8 Component Serialisation Hook

```pseudo
# Replication (D10) needs a uniform way to read/write a component's authoritative fields.

interface Replicable<T extends Component>:
    int  typeId()                                  # stable, from ComponentTypeRegistry
    void writeFull(T c, BitWriter out)
    void readFull(T c, BitReader in)
    void writeDelta(T current, T baseline, BitWriter out)
    void applyDelta(T target, BitReader in)
    boolean equalsForReplication(T a, T b)          # ignores cosmetic/local fields

R20. Only components marked Auth = A in D04-S4.3 have a Replicable implementation.
R21. Cosmetic (C) and local (L) components MUST NOT implement Replicable. A CI check
     asserts no Replicable exists for a C/L-classified component (enforces G6).
R22. ComponentTypeRegistry assigns typeIds from a checked-in, append-only list so that
     the wire format is stable across builds; removing a type retires its id forever.
```

---

<!-- D04-S6 -->## 6. ID Schemes

<!-- D04-S6.1 -->### 6.1 EntityId

**R23.** `EntityId` is a `uint32`: low 24 bits index, high 8 bits generation. `0` is the null entity. `1` is the match singleton.

**R24.** `EntityId`s are allocated by the **authority** and are identical on all peers for replicated entities: the authority sends `NetworkId → EntityId` in the spawn message and clients create the entity with the given index. Client-only entities (effects, debris rendering aids) are allocated from a **reserved high index range** `[12288, 16383]` that the authority never allocates from, so there is no collision.

```pseudo
AUTHORITY_INDEX_RANGE = [2, 12287]
CLIENT_LOCAL_RANGE    = [12288, 16383]

function allocateIndex(world):
    range = world.isAuthority ? AUTHORITY_INDEX_RANGE : CLIENT_LOCAL_RANGE
    ... # allocate within range, assert not exhausted
```

<!-- D04-S6.2 -->### 6.2 NetworkId

**R25.** `NetworkId` is a `uint32` assigned by the authority, monotonically increasing from 1, **never recycled within a match**. It is the identity used on the wire. The `NetworkId → EntityId` map lives on each peer.

Rationale: `EntityId` recycles indices; a delayed packet referring to a recycled index would address the wrong entity. `NetworkId` never recycles, so a stale reference resolves to "unknown, ignore" rather than to the wrong object (supports G16).

<!-- D04-S6.3 -->### 6.3 Component Type IDs

**R26.** `ComponentTypeRegistry` maps component classes to a dense `int` index used for masks and array slots, assigned from a checked-in ordered list (`component_types.txt`). The list is append-only; entries are never reordered or removed, only marked `RETIRED`. Mask width is 64 bits; the current catalogue uses 32 slots, leaving headroom. If the catalogue exceeds 64, the mask becomes a two-word bitset — a mechanical change specified here so it does not become an ad-hoc decision later.

<!-- D04-S6.4 -->### 6.4 Slot Paths

**R27.** A part's identity within a vehicle is its `slotPath`: `/`-separated slot ids from the chassis, e.g. `root/hardpoint_left/turret_mount`. Slot paths are stable for the life of the assembly and are what assembly manifests, replication of structural changes, and test assertions use (D05-S4.3).

---

<!-- D04-S7 -->## 7. Acceptance Criteria

- [ ] **AC-D04-1.** Every component in D04-S4.3 exists with exactly the listed fields, types, and units.
- [ ] **AC-D04-2.** No component class contains gameplay behaviour (checked by an ArchUnit rule: component classes have no methods beyond accessors, `reset()`, and `validate()`).
- [ ] **AC-D04-3.** Systems execute in exactly the order in D04-S4.4; a test asserts the schedule list matches the table.
- [ ] **AC-D04-4.** Family iteration returns entities in ascending `EntityId` order in all cases, including after heavy add/remove churn.
- [ ] **AC-D04-5.** Running the same seeded scenario twice on the same build yields identical component state at every tick (bit-identical for non-physics fields; within physics tolerance for Bullet-derived fields).
- [ ] **AC-D04-6.** After 10,000 ticks of a full match, steady-state allocation per tick is 0 bytes (measured with an allocation profiler; excludes logging and the first 60 warm-up ticks).
- [ ] **AC-D04-7.** `destroyEntity` on a vehicle destroys all its part entities and leaves `NativeResourceTracker.outstanding() == 0` for those entities.
- [ ] **AC-D04-8.** Double-destroy, destroy-during-iteration, and stale-`EntityId` access are all safe no-ops (never exceptions, never wrong-entity access).
- [ ] **AC-D04-9.** No `Replicable` implementation exists for any component classified `C` or `L` (CI check).
- [ ] **AC-D04-10.** `NetworkId`s are never reused within a match (asserted by an instrumented long-running match test).
- [ ] **AC-D04-11.** Client-local entities never receive an index in `AUTHORITY_INDEX_RANGE`, and vice versa.
- [ ] **AC-D04-12.** Each system's declared read/write set matches its actual component access (verified by an instrumented test harness that records accesses in debug builds).

---

<!-- D04-S8 -->## 8. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | `getComponent` on a destroyed entity | Returns `null`. Never throws. Callers must null-check; debug builds log at TRACE with the stale ID. |
| E2 | Stale `EntityId` whose index was recycled | `isAlive()` returns false due to generation mismatch. Access returns null. |
| E3 | `MAX_ENTITIES` exhausted | Fatal: log the per-archetype entity census, then abort. This always means a leak (typically debris not despawning — see D07-S5.8 budget). |
| E4 | Component added twice to one entity | Assertion failure in debug; in release, the second add replaces and logs at ERROR (never silently keeps two). |
| E5 | Entity destroyed while a Bullet callback holds its body pointer | Impossible by construction: destruction runs in CLEANUP, outside `stepSimulation`. A debug guard asserts `!physicsWorld.isStepping` during native disposal. |
| E6 | A system mutates a component another system already read this tick | Legal and intended — the schedule defines the order. Documented read/write sets make it reviewable. Systems must not rely on *later* systems' output within the same tick. |
| E7 | Family query with no `all()` clause | Rejected at construction: an unbounded family would iterate every entity, defeating the design. |
| E8 | Pool returns a component with stale data | Caught by the debug poison in R18. Treated as a P0 correctness bug. |
| E9 | Client receives a snapshot for an unknown `NetworkId` | Buffer for up to `SPAWN_GRACE_TICKS = 30`, awaiting the spawn message; then discard and log once per ID. |
| E10 | Authority and client disagree on component type IDs (version skew) | Handshake compares a hash of `component_types.txt`; mismatch rejects the connection with `PROTOCOL_MISMATCH` (D10-S5.8). |
| E11 | Structural change (detach) during `PhysicsSystem`'s step | Prohibited: detachment is in POST_SIM (14), after the step. `MassPropertySystem` (15) then reconciles before the next tick's step, satisfying G10. |
| E12 | Event queue overflow | Fixed-capacity queues; overflow logs at ERROR, drops the *oldest* cosmetic events first, never drops damage or destruction events (which abort the tick with a fatal if their queue overflows). |
| E13 | Component mask exceeds 64 types | Compile-time assertion in `ComponentTypeRegistry`; requires the two-word bitset change in D04-S6.3. |

---

<!-- D04-S9 -->## 9. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D04-1 | Create 1000 entities, destroy every other one, create 500 more | No index collisions; all live IDs resolve; all stale IDs report not-alive |
| T-D04-2 | Destroy an entity twice in the same tick | Single teardown, no exception |
| T-D04-3 | Destroy an entity during family iteration | Iteration completes over the pre-iteration snapshot; teardown happens in CLEANUP |
| T-D04-4 | Recycle one index 300 times (generation wraps) | Liveness checks remain correct at every step |
| T-D04-5 | Run a 60-second scripted match twice with seed 1337 | Non-physics component state identical tick-by-tick |
| T-D04-6 | Add and remove a component 10,000 times | Family membership correct after every operation; pool size stabilises |
| T-D04-7 | Spawn/destroy 200 debris entities per second for 60 s | Zero steady-state allocation; entity count bounded by `MAX_DEBRIS_BODIES` |
| T-D04-8 | Assert schedule order against the D04-S4.4 table | Exact match, including phase grouping |
| T-D04-9 | Reflectively scan for `Replicable` on `C`/`L` components | None found |
| T-D04-10 | Vehicle with 32 parts destroyed | All 32 part entities destroyed; `outstanding()` natives == 0; constraints removed before bodies |
| T-D04-11 | Client allocates 100 effect entities while authority allocates 100 vehicles | Index ranges disjoint; no `EntityId` appears in both |
| T-D04-12 | Feed a snapshot referencing `NetworkId` 9999 before its spawn message | Buffered ≤30 ticks, applied on spawn arrival; discarded with one log line if spawn never arrives |
| T-D04-13 | Instrument read/write sets over a full match | Recorded accesses ⊆ declared sets for every system |

---

<!-- D04-S10 -->## 10. Cross-References

| Topic | Section |
|---|---|
| Deterministic ordering invariant | `docs/00_master_index.md#D00-S5.2` (G3) |
| Identifier kinds | `docs/00_master_index.md#D00-S4.5` |
| Package layout for ecs/component/system | `docs/02_technical_architecture.md#D02-S4.7` |
| Native resource ownership rules | `docs/02_technical_architecture.md#D02-S5.7` |
| Mode-specific system sets | `docs/03_runtime_modes.md#D03-S5.2` |
| Tick vs frame loop | `docs/03_runtime_modes.md#D03-S5.3` |
| Slot graph structure and slot paths | `docs/05_vehicle_part_system.md#D05-S4.3` |
| Vehicle stat aggregation (VehicleStatsSystem) | `docs/05_vehicle_part_system.md#D05-S5.6` |
| Degradation curve (PartStats.effectiveStats) | `docs/05_vehicle_part_system.md#D05-S5.4` |
| Physics world and stepping (PhysicsSystem) | `docs/06_physics_simulation.md#D06-S5.1`, `#D06-S5.4` |
| Ray-cast vehicle controller | `docs/06_physics_simulation.md#D06-S5.5` |
| Mass property recomputation (MassPropertySystem) | `docs/06_physics_simulation.md#D06-S5.7` |
| Damage pipeline (DamageSystem) | `docs/07_damage_destruction_model.md#D07-S5.2` |
| Fracture trigger (FractureSystem) | `docs/07_damage_destruction_model.md#D07-S5.6` |
| Detachment (DetachSystem) | `docs/07_damage_destruction_model.md#D07-S5.7` |
| Morph weights (DamageVisualSystem) | `docs/07_damage_destruction_model.md#D07-S5.5` |
| Replication classes and snapshot content | `docs/10_networking_multiplayer.md#D10-S5.3` |
| Prediction and reconciliation | `docs/10_networking_multiplayer.md#D10-S5.5` |
| Bot decision system | `docs/11_ai_bots_and_match_simulation.md#D11-S5.3` |
| Match flow system | `docs/11_ai_bots_and_match_simulation.md#D11-S5.7` |
| Determinism regression tests | `docs/12_testing_validation_ci.md#D12-S5.2` |
| Harness use of the same world/systems | `docs/14_test_environment.md#D14-S5.2` |
