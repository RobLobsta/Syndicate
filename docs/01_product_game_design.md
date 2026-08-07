<!-- D01-S0 --># 01 — Product and Game Design

**Document ID:** D01
**Owns:** Genre and fantasy, game modes, core loop, combat rules, win/loss and scoring, progression.

---

<!-- D01-S1 -->## 1. Purpose

This document specifies what the player does, what they experience, and what rules govern a match. It is the source of truth for every player-facing rule and for the tuning constants that define feel. Systems documents (D05–D07, D11) implement these rules; where a number is a *design* number it lives here, and where it is a *simulation* number it lives in the owning technical document with a pointer from here.

Requirements are numbered `R1..Rn`, cited as `D01-R9`.

---

<!-- D01-S2 -->## 2. Scope

<!-- D01-S2.1 -->### 2.1 In Scope

- Genre, target audience, core fantasy, design pillars.
- Game mode catalogue with rules and parameters.
- Core gameplay loop.
- Vehicle combat rules: movement feel, weapon families, damage types, hit zones.
- Win/loss conditions, match structure, scoring and attribution.
- Player progression: cosmetic and functional unlocks.
- Player-facing feedback requirements for damage and destruction.

<!-- D01-S2.2 -->### 2.2 Non-Goals

- **NG1.** Free-form vehicle building. Vehicles are **prebuilt assemblies** selected by the player, not assembled part-by-part in a garage editor. Part-level customisation is a post-v1 consideration explicitly out of scope; the data model (D05) permits it, the product does not expose it.
- **NG2.** Monetisation, storefront, or live-ops economy design.
- **NG3.** Narrative, campaign, or characters.
- **NG4.** UI layout and visual style guides.
- **NG5.** The degradation *curve mathematics* — owned by `docs/05_vehicle_part_system.md#D05-S5.4`. This document states the intended *feel*; D05 states the function.
- **NG6.** Matchmaking, ranking, and social systems.

---

<!-- D01-S3 -->## 3. Dependencies

| Depends on | For |
|---|---|
| `docs/00_master_index.md#D00-S6` | Glossary terms used throughout |
| `docs/05_vehicle_part_system.md#D05-S5.4` | How degradation is actually computed |
| `docs/07_damage_destruction_model.md#D07-S4.3` | Damage type implementation |
| `docs/11_ai_bots_and_match_simulation.md#D11-S5.7` | Match phase state machine |
| `docs/10_networking_multiplayer.md#D10-S5.1` | Authority model constraining what rules can be client-side |

---

<!-- D01-S4 -->## 4. Data Contracts

<!-- D01-S4.1 -->### 4.1 Product Definition

| Attribute | Value |
|---|---|
| **Working title** | Syndicate |
| **Genre** | Arena vehicular combat, third-person, physics-driven, with persistent structural damage |
| **Platform** | Windows desktop (primary); Linux for the dedicated server |
| **Session length** | 6–10 minutes per match |
| **Player count** | 1 (vs bots) to 12 (6v6) |
| **Perspective** | Third-person chase camera, free-look aim |
| **Target audience** | Players of arena shooters and vehicle combat games who want visible consequence: the 25–40 core segment familiar with *Crossout*, *Twisted Metal*, *World of Tanks*, and destruction-physics sandboxes |

**R1. Core fantasy.** *You pilot a machine that visibly and functionally falls apart around you, and you keep fighting anyway.* Damage is never an abstract health bar: a lost wheel changes how you steer, a shredded armour plate exposes the frame beneath, a destroyed gun stops firing and leaves a torn mount. Losing is gradual and legible.

**R2. Design pillars.** Every design decision is checked against these, in priority order:

| # | Pillar | Consequence |
|---|---|---|
| P1 | **Damage is legible** | The player can always tell what is broken by looking at the vehicle. Visual state and functional state never disagree (D07-S4.2). |
| P2 | **Degradation is felt, not read** | Performance loss is continuous and perceptible through handling and fire rate before the player consults any UI. |
| P3 | **Physics is the arbiter** | Mass, momentum, and traction decide outcomes. Losing 300 kg off one flank *should* pull the vehicle. |
| P4 | **Fights are readable at a glance** | Silhouette damage tells you which opponent is nearly finished. |
| P5 | **Fair before fancy** | Anything that affects the outcome is server-authoritative (G1, G15). Spectacle is cosmetic and client-local (G6). |

**R3.** Where a pillar conflicts with spectacle, the pillar wins. A destruction effect that looks superb but makes P1 ambiguous is cut.

<!-- D01-S4.2 -->### 4.2 Game Mode Catalogue (`GameMode` enum)

**R4.** The `GameMode` enum has exactly these values. Modes differ only in rule parameters and the win predicate; they share one simulation.

| `GameMode` | Name | Players | Teams | Win condition | Time limit | Respawn |
|---|---|---|---|---|---|---|
| `SKIRMISH` | Single-player Skirmish | 1 human + 1–7 bots | FFA or teams | Score limit or time | 8 min | Yes, 8 s |
| `DEATHMATCH` | Free-for-all Deathmatch | 2–8 | None | First to `scoreLimit` kills, or highest at time | 8 min | Yes, 8 s |
| `TEAM_DEATHMATCH` | Team Deathmatch | 4–12 | 2 | Team reaches `scoreLimit`, or higher at time | 10 min | Yes, 8 s |
| `LAST_MACHINE` | Last Machine Standing | 4–8 | None or 2 | One vehicle/team remains | 6 min | No |
| `PAYLOAD` | Payload Escort | 6–12 | 2 (attack/defend) | Attackers move the payload to the goal, or defenders hold to time | 10 min | Yes, 10 s |
| `TIME_TRIAL` | Time Trial (practice) | 1 | None | Complete the course | none | n/a |
| `TEST_RANGE` | Test Range (development) | 1 | None | none | none | Instant |

**R5. Recommended additions and why they were chosen.** `LAST_MACHINE` exists because permanent damage is most meaningful without respawn — it is the mode where degradation is the whole game. `PAYLOAD` exists because an objective forces vehicles into predictable positions, which showcases sustained structural damage over a long engagement rather than short bursts. `TEST_RANGE` exists because designers and the coding assistant need a mode with no win condition for inspecting parts under damage; it shares the harness's debug overlays (D14-S5.11) but runs in the game client.

<!-- D01-S4.3 -->### 4.3 Match Rules Parameters

**R6.** `MatchRulesComponent` (D04-S4.3) carries these, sourced from the mode's default table and overridable per match.

| Field | Type | Default | Range | Meaning |
|---|---|---|---|---|
| `mode` | `GameMode` | — | — | See D01-S4.2 |
| `scoreLimit` | int | 20 (DM), 40 (TDM) | 1–200 | Points to win |
| `timeLimitTicks` | int | mode table × `TICK_RATE_HZ` | ≥ 60 | Match duration |
| `respawnDelayTicks` | int | 480 (8 s) | 0–1800 | Delay before respawn |
| `friendlyFire` | bool | false | — | Team damage enabled |
| `botCount` | int | 0 | 0–11 | Bots to fill |
| `botDifficulty` | `BotDifficulty` | `NORMAL` | — | D11-S4.2 |
| `arenaId` | AssetId | — | — | Arena to load |
| `matchSeed` | long | random at creation | — | Seeds all gameplay randomness (G4) |
| `warmupTicks` | int | 300 (5 s) | 0–3600 | Countdown before ACTIVE |
| `suddenDeathTicks` | int | 0 | ≥ 0 | If > 0 and tied at time, extend with no respawns |

<!-- D01-S4.4 -->### 4.4 Weapon Families

**R7.** Weapons are parts (category `weapon`, D05-S4.2). Each weapon type belongs to one family. Family determines projectile behaviour and primary damage type.

| Family | Delivery | Primary damage type | Fire rate | Range | Notes |
|---|---|---|---|---|---|
| `AUTOCANNON` | Fast projectile (ballistic, 600 m/s) | `KINETIC` | 4–8 /s | 200 m | Workhorse. Rate degrades sharply with weapon health. |
| `CANNON` | Slow heavy projectile (250 m/s, gravity-affected) | `KINETIC` | 0.4 /s | 300 m | High single-part damage; can detach a part in one hit. |
| `SHOTGUN` | Multi-pellet hitscan cone, 20 m falloff | `KINETIC` | 1 /s | 25 m | Spreads damage across several parts — good at stripping armour. |
| `ROCKET` | Slow projectile with proximity detonation | `EXPLOSIVE` | 0.5 /s | 150 m | Area damage; propagates strongly (D07-S5.4). |
| `MORTAR` | High-arc projectile | `EXPLOSIVE` | 0.3 /s | 120 m | Indirect fire; hits top hit zones. |
| `FLAMER` | Short continuous cone | `INCENDIARY` | continuous | 12 m | Applies burn stacks; strong against low-armour parts. |
| `LASER` | Hitscan continuous beam | `ENERGY` | continuous | 100 m | Ignores a fraction of armour; heat-limited. |
| `RAM` | Melee/collision (not a weapon part; a chassis property) | `COLLISION` | — | contact | Damage from relative momentum (D07-S5.2). |

**R8.** Every weapon part exposes `baseFireIntervalS`, `damagePerShot`, `damageType`, `projectileSpeedMps`, `spreadRad`, `heatPerShot`, and `ammoCapacity` in its part definition (D08-S4.2). Balance values are content, not code.

<!-- D01-S4.5 -->### 4.5 Damage Types

**R9.** Exactly five damage types exist (`DamageType`). Their gameplay identity is here; their maths is D07-S5.2.

| Type | Identity | Armour interaction | Propagation |
|---|---|---|---|
| `KINETIC` | Direct impact, the default | Reduced by flat armour value | Low (0.5 × base) |
| `EXPLOSIVE` | Blast, area | Partially bypasses armour; falls off with distance | High (1.5 × base), reaches 2 hops |
| `INCENDIARY` | Burn over time | Ignores armour; applies stacks that tick | Low, but spreads to the same-parent part |
| `ENERGY` | Beam | Fixed fraction of armour ignored | None (single part only) |
| `COLLISION` | Ramming and world impact | Reduced by armour; scaled by relative momentum | Medium (1.0 × base) |

<!-- D01-S4.6 -->### 4.6 Hit Zones

**R10.** There are no abstract hit-zone multipliers. **The hit zone is the part that was hit.** A shot resolves to a specific part via the collision contact (D07-S5.1), and that part's own armour and health absorb it. This is what makes P1 and P4 work: shooting the left wheel destroys the left wheel.

**R11.** Three positional modifiers apply on top, because pure part-hit resolution loses tactical depth:

| Modifier | Condition | Effect |
|---|---|---|
| `REAR` | Contact normal within 60° of the vehicle's −forward axis | ×1.35 damage |
| `TOP` | Contact normal within 45° of world up | ×1.20 damage |
| `EXPOSED` | The struck part's parent slot has lost its covering armour part | ×1.50 damage |

**R12.** `EXPOSED` is the design payoff of the whole destruction system: stripping armour is rewarded, and the reward is visible because the exposed frame is literally showing.

---

<!-- D01-S5 -->## 5. Logic & Algorithms

<!-- D01-S5.1 -->### 5.1 Core Gameplay Loop

```
   ┌──────────────┐
   │  SELECT      │  Choose a prebuilt vehicle from the roster.
   │              │  See its stat profile: speed, armour, firepower, mass.
   └──────┬───────┘
          ▼
   ┌──────────────┐
   │  DEPLOY      │  Spawn into the arena. Countdown. Vehicles are intact.
   └──────┬───────┘
          ▼
   ┌──────────────┐    parts degrade, detach, and are lost permanently
   │  COMBAT      │◄─────────────────────────────────────────┐
   │              │  drive, aim, fire, ram, take damage       │
   └──────┬───────┘                                          │
          │ destroyed (chassis lost)                         │
          ▼                                                  │
   ┌──────────────┐  respawn modes: full repair, delay        │
   │  RESPAWN     │──────────────────────────────────────────┘
   └──────┬───────┘  no-respawn modes: spectate
          ▼
   ┌──────────────┐
   │  RESULTS     │  Score, damage dealt, parts destroyed, XP awarded.
   └──────┬───────┘
          ▼
   ┌──────────────┐
   │  ITERATE     │  Unlock/select a different vehicle; adjust approach.
   └──────────────┘
```

**R13.** Within a life, damage is **permanent** — there is no in-match repair pickup in v1. Respawn restores a fully intact vehicle. This makes each life an arc from intact to wreck, which is the product's core rhythm.

<!-- D01-S5.2 -->### 5.2 Movement Model

**R14.** Vehicles use a ray-cast vehicle model with real mass (D06-S5.5). Design intent expressed as constraints on that model:

```pseudo
MOVEMENT INTENT:
  - Top speed is a property of the drivetrain and total mass, not a fixed number.
        maxSpeed ≈ baseMaxSpeed * (referenceMass / totalMass) ^ 0.5     (D05-S5.6)
  - Acceleration is force / mass. Adding armour must visibly cost acceleration.
  - Steering authority comes from wheels that are (a) present, (b) touching ground,
    (c) not degraded. Losing a steering wheel loses steering, not "some handling".
  - Centre of mass is real. An off-centre load pulls; a high load rolls.
  - There is no artificial anti-roll or auto-correct assist beyond a mild
    downforce term at speed (design tuning value, D06-S5.5).

CONTROL MAPPING (per tick):
  throttle ∈ [-1, 1]   -> engineForce = throttle * effectiveEngineForceN
  steer    ∈ [-1, 1]   -> steerAngle  = steer * effectiveMaxSteerRad, rate-limited
  brake    ∈ [0, 1]    -> brakeForce  = brake * effectiveBrakeForceN
  handbrake            -> rear wheels frictionSlip * 0.25 for the duration
  aim (yaw, pitch)     -> turret/weapon orientation, independent of hull heading
```

**R15. Feel targets** (measured on the reference chassis, intact, D14-S7.1):

| Metric | Target |
|---|---|
| 0 → 10 m/s | 2.4 s ± 0.3 |
| Top speed | 22 m/s |
| Full-lock turn radius at 8 m/s | 7.5 m |
| 20 m/s → 0 braking distance | 26 m |
| Time to roll over on a 30° bank at 15 m/s | should not roll |

<!-- D01-S5.3 -->### 5.3 Degradation Feel Specification

**R16.** This is the design intent that D05-S5.4 must realise. Each category degrades in the way that is *most legible* for that category.

| Part category | What degrades | Feel at 50% health | Feel at 10% health |
|---|---|---|---|
| `wheel` | Traction, then steering authority | Slight pull, longer stops | Wheel visibly buckled; barely grips; vehicle crabs |
| `weapon` | Fire rate, then accuracy | Noticeably slower cadence | Sputtering, wide spread, frequent stalls |
| `armor` | Effective armour value only (never mass until destroyed) | Cracked and dented; absorbs less | Hanging off; near-zero protection |
| `chassis` | Engine force and structural integrity | Sluggish, engine noise change | Crawling; one more hit ends the life |
| `utility` | Its specific effect (e.g. radar range, ammo feed) | Reduced effect | Effect near zero |
| `decorative` | Nothing functional | Visual only | Falls off |

**R17.** Armour parts must not lose mass as they degrade. Mass changes **only** on detachment. A continuously lightening vehicle would make P3 unreadable — the player could not attribute handling change to a specific event.

**R18.** Degradation is **continuous** in the stat (so it is felt, P2) and **discrete** in the visual state (so it is legible, P1). The four damage states map to the four damage morphs; the stat curve is smooth between them.

<!-- D01-S5.4 -->### 5.4 Scoring and Attribution

```pseudo
SCORE EVENTS:
    PART_DESTROYED        +10   to the player whose damage crossed the part's 0 HP
    VEHICLE_DESTROYED     +100  to the killer
    ASSIST                +40   to anyone who dealt >= 20% of the vehicle's total
                                effective HP within ASSIST_WINDOW_TICKS (600 = 10 s)
    OBJECTIVE_TICK        +5    per second of payload progress (PAYLOAD mode)
    SELF_DESTRUCT         -50   to the player (drove off the map, or self-inflicted)
    TEAM_KILL             -100  and the damage is refunded to the victim if friendlyFire

function onPartDestroyed(part, damageEvent):
    award(damageEvent.attacker, PART_DESTROYED)
    if part.isChassis:                       # chassis loss = vehicle destroyed
        award(damageEvent.attacker, VEHICLE_DESTROYED)
        for contributor in damageLedger(vehicle).within(ASSIST_WINDOW_TICKS):
            if contributor != damageEvent.attacker
               and contributor.totalDamage >= 0.20 * vehicle.totalEffectiveHp:
                award(contributor, ASSIST)
        recordDeath(vehicle.owner)

# The damage ledger is authoritative state (G1) and is retained for the whole match
# so the results screen can show a full damage breakdown per player.
```

**R19.** `scoreLimit` counts `VEHICLE_DESTROYED` events in `DEATHMATCH`/`TEAM_DEATHMATCH`, not points. Points determine ranking within the match, not victory.

<!-- D01-S5.5 -->### 5.5 Win / Loss Evaluation

```pseudo
function evaluateWinCondition(match):
    switch match.rules.mode:
        case DEATHMATCH:
            leader = players.maxBy(kills)
            if leader.kills >= rules.scoreLimit:            return WIN(leader)
            if match.clock.tick >= rules.timeLimitTicks:
                if uniqueMax(players by kills):             return WIN(leader)
                else if rules.suddenDeathTicks > 0:         return ENTER_SUDDEN_DEATH
                else:                                       return DRAW(tiedPlayers)

        case TEAM_DEATHMATCH:
            for team in teams:
                if team.kills >= rules.scoreLimit:          return WIN(team)
            if match.clock.tick >= rules.timeLimitTicks:    return highestTeamOrDraw()

        case LAST_MACHINE:
            alive = vehicles.filter(chassis.state != DESTROYED)
            if distinctOwners(alive).size <= 1:             return WIN(alive.firstOwnerOrNone())
            if match.clock.tick >= rules.timeLimitTicks:    return WIN(mostIntactVehicle())
                # "most intact" = highest sum of part current HP; ties -> highest damage dealt

        case PAYLOAD:
            if payload.atGoal:                              return WIN(ATTACKERS)
            if match.clock.tick >= rules.timeLimitTicks:    return WIN(DEFENDERS)

        case SKIRMISH:  return evaluateAsIfDeathmatchOrTeam(match)
        case TIME_TRIAL, TEST_RANGE:                        return NEVER
    return CONTINUE
```

**R20.** Evaluation runs on the authority in `MatchFlowSystem` (D04-S4.4 slot 4), once per tick. Clients render the outcome they are told; they never evaluate it (G15).

<!-- D01-S5.6 -->### 5.6 Match Structure

```pseudo
LOBBY  --(players ready or bots filled)-->  COUNTDOWN (warmupTicks)
       --(countdown elapsed)-->             ACTIVE
       --(win condition)-->                 ENDING (endingTicks = 300, cameras on the winner)
       --(ending elapsed)-->                RESULTS (resultsTicks = 900, scoreboard)
       --(results elapsed or all leave)-->  LOBBY

# The state machine itself is specified in docs/11_ai_bots_and_match_simulation.md#D11-S5.7
# Rules:
R21. Vehicles are frozen (input ignored, physics still simulated) during COUNTDOWN.
R22. Damage is disabled until ACTIVE begins.
R23. During ENDING, input is ignored but debris and destruction continue to simulate,
     so the final wreck is what the player watches.
R24. Late joiners enter at the next respawn opportunity in respawning modes, and
     as spectators in LAST_MACHINE.
```

<!-- D01-S5.7 -->### 5.7 Progression

**R25.** Progression is shallow, transparent, and never grants raw power over an opponent who has less of it.

```pseudo
XP AWARD (end of match):
    xp = 10 * partsDestroyed
       + 50 * vehiclesDestroyed
       + 20 * assists
       + floor(damageDealt / 500)
       + 100 if won
       + 25  participation

UNLOCK TIERS:
    Level 1..5    : cosmetic paint schemes, decals            (COSMETIC)
    Level 3,6,9.. : additional prebuilt vehicles              (FUNCTIONAL, sidegrade)
    Level 5,10,15 : additional weapon loadout variants of an  (FUNCTIONAL, sidegrade)
                    already-unlocked vehicle
    Any level     : arena voting weight, name plates          (COSMETIC)

R26. Every FUNCTIONAL unlock is a SIDEGRADE: a newly unlocked vehicle must not
     dominate a starter vehicle on every axis. Each unlock trades at least one
     of {speed, armour, firepower, mass} down for another up.
R27. A vehicle's total power budget (D05-S5.7) is equal across all unlockable
     vehicles of the same class. Unlocks change distribution, never total.
R28. Cosmetics never alter mass, collision shape, or any stat.
```

<!-- D01-S5.8 -->### 5.8 Feedback Requirements

**R29.** These are contractual player-feedback requirements that other documents must support.

| # | Requirement | Realised by |
|---|---|---|
| F1 | The player can see which of their own parts are damaged without opening a menu | Vehicle silhouette widget driven by `DamageState` (D04-S4.3) |
| F2 | A hit on an enemy gives immediate directional feedback | Hit marker on authoritative damage confirmation (D10-S5.7) |
| F3 | The visual damage state and the functional state never disagree | Both derive from `healthFraction` (D07-S5.5, G6/P1) |
| F4 | Detachment is unmistakable | Part visibly separates, becomes debris, silhouette widget updates (D07-S5.7) |
| F5 | Handling change from a lost part is felt within 1 second | `MassPropertySystem` recomputes in the same tick (G10) |
| F6 | The player understands *why* they died | Results screen shows the damage ledger and the killing part |
| F7 | Cosmetic-only effects never mislead about gameplay state | Effects derive from authoritative events only (G6) |

---

<!-- D01-S6 -->## 6. Acceptance Criteria

- [ ] **AC-D01-1.** All seven `GameMode` values exist and are selectable, with the parameters of D01-S4.2/S4.3.
- [ ] **AC-D01-2.** Win conditions evaluate exactly as D01-S5.5, verified by a scripted headless match per mode.
- [ ] **AC-D01-3.** A hit always resolves to a specific part; no abstract hit-zone multiplier exists in code (D01-R10).
- [ ] **AC-D01-4.** `REAR`, `TOP`, `EXPOSED` modifiers apply with the stated multipliers and are visible in the damage ledger.
- [ ] **AC-D01-5.** Armour parts lose no mass before destruction (D01-R17), asserted by a physics test.
- [ ] **AC-D01-6.** The reference chassis meets every feel target in D01-S5.5 within the stated margin.
- [ ] **AC-D01-7.** All five damage types are implemented with distinct armour and propagation behaviour.
- [ ] **AC-D01-8.** All eight weapon families exist with the delivery and damage type in D01-S4.4.
- [ ] **AC-D01-9.** Score events award exactly the values in D01-S5.4; assists require ≥ 20% within the window.
- [ ] **AC-D01-10.** Every unlockable vehicle of a class has an equal power budget (D01-R27), checked by an asset-pipeline rule.
- [ ] **AC-D01-11.** No cosmetic item changes mass, collision shape, or any stat (asset validation rule, D08-S5.4).
- [ ] **AC-D01-12.** Damage is disabled before `ACTIVE` and input is ignored during `COUNTDOWN` and `ENDING`.
- [ ] **AC-D01-13.** Every feedback requirement F1–F7 has an implemented mechanism.
- [ ] **AC-D01-14.** No in-match repair mechanic exists in v1 (D01-R13).

---

<!-- D01-S7 -->## 7. Edge Cases & Failure Modes

| # | Condition | Required behaviour |
|---|---|---|
| E1 | All players leave mid-match | Authority runs to the time limit if bots remain; otherwise ends the match and returns to LOBBY. |
| E2 | Tie at time limit with `suddenDeathTicks = 0` | Declare a draw. Never resolve a tie by a hidden tiebreaker. |
| E3 | A player is destroyed by world geometry (fall, crush) | `SELF_DESTRUCT`, −50, no kill credit to anyone. |
| E4 | A vehicle loses every wheel | It is immobile but alive. It can still fire. It is not auto-killed; the player may be finished off or survive to time. |
| E5 | The chassis is destroyed while parts remain intact | Vehicle is destroyed; all remaining parts detach as debris (D07-S5.7). |
| E6 | A weapon part is destroyed mid-burst | Firing stops at the tick the part reaches 0 HP; in-flight projectiles persist and can still score. |
| E7 | Damage dealt after the win condition fires | Ignored for scoring; still simulated visually during `ENDING`. |
| E8 | A player disconnects with kills | Score retained on the scoreboard as `(left)`; they cannot win the match. |
| E9 | Friendly fire disabled but a teammate is rammed | `COLLISION` damage is also suppressed; no team damage of any type when `friendlyFire = false`. |
| E10 | Bot count exceeds free slots | Bots are removed oldest-first as humans join; never kick a human. |
| E11 | `LAST_MACHINE` reaches time with all vehicles alive | Most-intact wins (D01-S5.5); ties broken by damage dealt, then by earliest join. |
| E12 | Payload contested by both teams | Payload holds position; no progress in either direction while contested. |
| E13 | A player selects a vehicle they have not unlocked (tampered client) | Authority rejects the selection and substitutes the default vehicle (G15). |
| E14 | Explosive damage kills a part whose parent is already detached | Damage applies to the detached subtree as debris damage only; no score. |

---

<!-- D01-S8 -->## 8. Test Cases

| ID | Scenario | Expected |
|---|---|---|
| T-D01-1 | Headless `DEATHMATCH`, `scoreLimit = 3`, 4 bots | Match ends when a bot reaches 3 kills; results emitted |
| T-D01-2 | Headless `LAST_MACHINE`, 4 bots | Ends with one survivor or with the most-intact vehicle at time |
| T-D01-3 | Tie at time limit, sudden death off | Result is `DRAW` with the tied set named |
| T-D01-4 | Shoot the left front wheel until destroyed | Only that wheel's health falls (plus propagation); steering degrades; other wheels unaffected |
| T-D01-5 | Hit the same part from front and rear | Rear hit deals 1.35× the front hit's damage |
| T-D01-6 | Destroy an armour plate, then hit the part it covered | Subsequent damage is 1.5× (`EXPOSED`) |
| T-D01-7 | Damage an armour plate to 10% health | Vehicle mass unchanged; armour value reduced |
| T-D01-8 | Destroy that plate | Vehicle mass drops by the plate's mass within one tick |
| T-D01-9 | Reference chassis 0 → 10 m/s | 2.4 s ± 0.3 |
| T-D01-10 | Reference chassis braking 20 → 0 m/s | 26 m ± 3 |
| T-D01-11 | Deal 25% of a vehicle's HP, teammate finishes it | Assist awarded (+40) |
| T-D01-12 | Deal 15% then teammate finishes | No assist |
| T-D01-13 | Drive off the map | −50, no kill credit |
| T-D01-14 | Fire during `COUNTDOWN` | No projectile spawns; no damage |
| T-D01-15 | Compute power budgets of all vehicles of a class | All equal within the tolerance in D05-S5.7 |
| T-D01-16 | Apply each of the five damage types to identical plates | Distinct resulting HP, matching D01-S4.5 |
| T-D01-17 | Tampered client requests a locked vehicle | Authority substitutes the default; no desync |

---

<!-- D01-S9 -->## 9. Cross-References

| Topic | Section |
|---|---|
| Glossary and invariants | `docs/00_master_index.md#D00-S6`, `#D00-S5.2` |
| Runtime modes hosting these game modes | `docs/03_runtime_modes.md#D03-S4.1` |
| `MatchRulesComponent` fields | `docs/04_entity_component_model.md#D04-S4.3` |
| `MatchFlowSystem` slot | `docs/04_entity_component_model.md#D04-S4.4` |
| Part categories | `docs/05_vehicle_part_system.md#D05-S4.2` |
| Degradation curve mathematics | `docs/05_vehicle_part_system.md#D05-S5.4` |
| Vehicle stat aggregation and speed/mass relation | `docs/05_vehicle_part_system.md#D05-S5.6` |
| Power budget | `docs/05_vehicle_part_system.md#D05-S5.7` |
| Ray-cast vehicle model and tuning | `docs/06_physics_simulation.md#D06-S5.5` |
| Hit resolution to a part | `docs/07_damage_destruction_model.md#D07-S5.1` |
| Damage type maths and armour | `docs/07_damage_destruction_model.md#D07-S5.2` |
| Damage propagation | `docs/07_damage_destruction_model.md#D07-S5.4` |
| Detachment | `docs/07_damage_destruction_model.md#D07-S5.7` |
| Part definition content fields | `docs/08_asset_pipeline.md#D08-S4.2` |
| Authority model | `docs/10_networking_multiplayer.md#D10-S5.1` |
| Hit confirmation feedback | `docs/10_networking_multiplayer.md#D10-S5.7` |
| Bot difficulty | `docs/11_ai_bots_and_match_simulation.md#D11-S4.2` |
| Match phase state machine | `docs/11_ai_bots_and_match_simulation.md#D11-S5.7` |
