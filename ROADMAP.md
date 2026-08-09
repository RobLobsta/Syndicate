# Syndicate — Roadmap

**Last updated:** 2026-08-09 (end of SESS-013)
**Where we are:** two real cars' worth of physics drive around a headless server. Nothing shoots,
and nothing is visible.

> This file is maintained by the coding assistant and updated **at the end of every session**
> (CLAUDE.md §5, step 14). It is allowed to change shape as the work demands — reorder phases, split
> them, delete ones that stopped making sense. What it must always answer is: *what just happened,
> what is next, and how far is it to a game you can play?*

---

## 1. The timeline

```mermaid
gantt
    title From blueprints to a game you can play
    dateFormat X
    axisFormat %s

    section Done
    Blueprints and build scaffolding        :done, p0, 0, 2
    Destruction toolchain                   :done, p1, 2, 3
    Simulation core - physics and breakage   :done, p2, 5, 5
    Spawning and lifetime                   :done, p3, 10, 1
    A vehicle you can drive  (we are here)  :done, p4, 11, 2

    section Next
    Combat - hits, damage, weapons          :active, p5, 13, 3
    A world to fight in - real content      :p6, 16, 3

    section Then
    A window - client, render, HUD          :p7, 19, 3
    Opponents - bots and match flow         :p8, 22, 3
    Multiplayer                             :p9, 25, 4
    Production hardening                    :p10, 29, 3
```

Read the numbers as **sessions of work, roughly**, not dates. Everything before "we are here" is
done and green; everything after is an estimate that will move.

### The same thing, as a path

```
  ┌─ DONE ───────────────────────────────────────────────────────────┐
  │                                                                  │
  │  ●  Phase 0   Blueprints, Gradle build, ECS engine               │
  │  │            15 spec documents, 8 modules, guardrail checks     │
  │  ●  Phase 1   Destruction toolchain                              │
  │  │            Blender fractures a mesh; a harness re-verifies it │
  │  ●  Phase 2   Simulation core                                    │
  │  │            Bullet steps; parts fracture, detach, become scrap │
  │  ●  Phase 3   Spawning and lifetime                              │
  │  │            An assembly becomes a vehicle; debris expires      │
  │  ●  Phase 4   A vehicle you can drive        ← THIS SESSION      │
  │  │            Stats aggregate, wheels turn, a server runs ticks  │
  └──┼───────────────────────────────────────────────────────────────┘
     │
  ┌──┼─ NEXT ──────────────────────────────────────────────────────────┐
  │  ○  Phase 5   Combat                                               │
  │  │            Contacts become damage; weapons fire; parts die      │
  │  ○  Phase 6   A world to fight in                                  │
  │  │            A glTF reader, real parts, an arena to drive on      │
  │  ○  Phase 7   A window                                             │
  │  │            Rendering, damage morphs, camera, HUD                │
  │  ○  Phase 8   Opponents                          ★ PLAYABLE HERE   │
  │  │            Bots, a match that starts, scores and ends           │
  │  ○  Phase 9   Multiplayer                                          │
  │  │            Replication, prediction, reconciliation              │
  │  ○  Phase 10  Production hardening                ★ SHIPPABLE      │
  │               Perf budgets, packaging, balance sweep, CI gates     │
  └────────────────────────────────────────────────────────────────────┘
```

### The system catalogue, which is the honest progress bar

`docs/04_entity_component_model.md#D04-S4.4` fixes 27 systems in a specific order. Nine exist.

```
 1 InputCollection   ○   10 Physics          ●   19 NetworkReceive   ○
 2 InputReceive      ○   11 CollisionEvent   ○   20 Reconciliation   ○
 3 BotDecision       ○   12 Damage           ○   21 Transform        ○
 4 MatchFlow         ○   13 Fracture         ●   22 Interpolation    ○
 5 Spawn             ●   14 Detach           ●   23 DamageVisual     ○
 6 VehicleStats      ●   15 MassProperty     ●   24 Effect           ○
 7 VehicleControl    ●   16 Lifetime         ●   25 Audio            ○
 8 Weapon            ○   17 Score            ○   26 Render           ○
 9 Projectile        ○   18 NetworkSend      ○   27 EntityDestroy    ●

 ●●●●●●●●●○○○○○○○○○○○○○○○○○○  9 / 27
```

There is also now a **schedule** rather than a list of systems assembled by hand in a test: one
table holds all 27 slots and which runtime modes each belongs to, and a process asks it what to run.
The eighteen that do not exist are named in a log line at startup rather than silently absent.

---

## 2. What happened this session (SESS-012)

PROG-007 named two systems as the next work. Both are done, plus the piece underneath them that
neither could exist without, plus the shell that turns the whole thing into a program.

- **Damage now means something to performance.** Every part carries a curve saying how its
  contribution falls off as it takes damage — a wheel loses grip immediately and keeps a third of it
  to the end, steering fades gently, a weapon works perfectly until it is two-thirds dead and then
  starts sputtering, and a hit always does full damage because a player needs to be able to read
  that. Those curves are now real code rather than a table in a document.
- **`VehicleStatsSystem`.** The thing that adds a vehicle up: engine force, brakes, steering lock,
  average armour, and from those a top speed and an acceleration that are *derived* rather than
  authored — so no content file can promise a speed the physics will not deliver. Utility parts that
  buff other parts are folded in here too, and stop buffing the moment they die.
- **`VehicleControlSystem`.** The one that makes it move. Throttle becomes engine force spread
  across the wheels that are actually driven and actually alive; steering is eased toward its target
  rather than snapped to it, because a keyboard produces input that jumps and a vehicle that
  followed it exactly would flip; each wheel gets grip reflecting its own damage, so a car with one
  dead corner pulls to that side.
- **A schedule, and a server that runs it.** There are 27 systems in the design, in a fixed order,
  and which of them run depends on whether the process is a client, a listen server or a dedicated
  one. That table is now data, and `syndicate-server` boots against it: natives, assets, world,
  schedule, and a 60 Hz loop that paces itself against the clock and skips forward rather than
  spiralling if the machine cannot keep up. Before this, the simulation only ever ran inside a test
  that assembled its own list of systems.

Two bugs came out of it, both worth naming.

The first drive test put the vehicle on the ground and gave it full throttle, and it sat there. The
wheels are ray casts, and every one of them was missing the floor — Bullet issues that ray with a
fixed collision filter it will not let you change, and the filter our arena geometry was registered
with did not admit it. So the vehicle sank through its own suspension and rested on its chassis,
with the engine turning nothing. Everything about the vehicle was correct; one bit in one number was
not.

The second surfaced while investigating the first. The suspension and tyre settings had been chosen
two sessions ago from a Bullet sample, on the belief that the specification didn't provide any — it
does, in a table a few hundred lines above the code that reads it. Tyre grip had been five times the
specified value. It is now what the document says, which is a noticeably different car.

### Since then (SESS-013) — two vehicles with real numbers

The handling question §4 asked — *how should it handle?* — turned out to be answerable by picking
real cars and copying their homework.

There are now two vehicles. The **Eclipse** is a mid-engine road supercar whose mass, power, torque,
0–100 time, top speed, braking distance, drag coefficient and tyre sizes all come from Maserati's
published figures for the MC20. The **Stampede** is a front-engine GT racer built the same way
from the Ford Mustang GT3 — 1289 kg, 550 hp, slicks and a wing. The in-game names are deliberately
not the real ones: the numbers are facts and free to use, the trademarks are not.

They are meant to be a choice rather than a ladder. The road car wins a drag race — it makes more
power and puts it down through a shorter first gear. The race car is 200 kg lighter, brakes about a
fifth shorter, holds far more grip and generates six times the downforce, so it is quicker at
everything except a standing start.

What makes this more than a table of numbers is that the game is held to it. Each vehicle is spawned
in the real physics world, driven, and timed: the 0–100 has to come out within about a tenth of a
second of the real car's, and the braking distance within a third of the published one. Change the
drag model or the tyre grip and a test says which car stopped matching reality.

Getting there needed three fixes. A vehicle had only a force, not a power, so one calibrated to a
real standing start claimed a 637 km/h top speed — adding an engine power ceiling makes both figures
come out right at once. Bullet turned out to read the brake command as an impulse where it reads the
engine command as a force, so braking was sixty times too strong and had been hiding behind that.
And splitting the brake evenly across four wheels throws away the rears' share as the nose dives,
which had the race car barely out-braking the road car; the brake now follows the weight, like a real
car's does.

`VEHICLES.md` is the roster, with every published figure, every derived one, every estimate, and the
sources. It regenerates itself and the build fails until the new copy is committed, so it cannot go
stale. `assets/README.md` says where a model file goes.


## 3. What is next

### Phase 5 — Combat *(next session)*

The shortest path from a vehicle that drives to a vehicle that can be beaten.

1. **`CollisionEventSystem` (slot 11).** Turns Bullet's contact manifolds into damage events. Today
   the only way a part takes damage is a test reaching in and declaring it destroyed.
2. **`DamageSystem` (slot 12).** Applies that damage through armour, drives each part through the
   intact → damaged → critical → destroyed states, and propagates a share of it to neighbouring
   parts. It also hands `DetachSystem` the one thing it has been missing since it was written: the
   direction a part was hit from, so it flies off the right way.
3. **`WeaponSystem` (8) and `ProjectileSystem` (9)**, then `ScoreSystem` (17) to record who did what.

At the end of Phase 5 two vehicles can hurt each other, and everything the destruction pipeline has
been able to do since Phase 1 finally gets triggered by the game rather than by a test.

### Phase 6 — A world to fight in

The blocker here is specific, known, and now the *only* thing between the content that exists and a
car on screen: the simulation cannot read a `.glb` file. Two vehicles are fully authored — parts,
masses, slots, handling, assemblies — and they load, validate, spawn and drive correctly against
stand-in box hulls. Drop a model into `assets/parts/chassis_eclipse_01/mesh.glb` today and nothing
reads it.

The verification harness already has a reader for exactly this format; `game-core` needs its own.
After that: the pipeline that validates content, and an arena format with spawn points and ground.

### Phase 7 — A window

`game-client`: a window, a camera, rendering, and the damage morph targets the Blender tool has been
generating since Phase 1 and nothing has yet displayed. The seam they plug into now exists — the
schedule knows about the six client-side systems and simply reports them as unimplemented.

### Phase 8 — Opponents ★ *the first thing that is actually a game*

Bots that drive and shoot, and a match that starts, keeps score, and ends. This is the milestone
where the answer to "can I play it?" becomes yes.

### Phases 9–10 — Multiplayer, then hardening

Replication, client prediction, reconciliation and lag compensation, followed by the performance
budgets, packaging and balance sweeps that `docs/12_testing_validation_ci.md#D12-S5.4` requires
before anything ships.

---

## 4. Open choices — things you could ask for

None of these are decided. They are here so the options are visible when a session is being planned.

**Scope and direction**

- **Cut multiplayer from v1.** Phases 9 covers the largest and riskiest body of work in the project.
  A single-player-plus-bots game is a complete game, and the blueprints are written so that
  networking can be added later without re-architecting.
- **Bring the client forward.** Phase 7 is placed after content, but a rough renderer earlier would
  make every subsequent phase easier to judge — right now the only way to see anything is a
  screenshot from the verification harness.
- **Go the other way and stay headless longer.** Bots, damage and match flow can all be built and
  tested without a window, and a headless-first project is one that will always run on a server.

**Gameplay**

- **How should it handle?** Half-answered. The two shipped vehicles handle like the real cars they
  were derived from, which is a defensible starting point and a much better one than invented
  numbers. What nobody has decided is whether a *combat* game wants that: real cars are fragile,
  grippy and fast, and an arena brawl might want something heavier and more forgiving. The place to
  find out is to drive them, which needs Phase 6.
- **Which vehicles next?** The two shipped are both fast and light. A roster wants contrast — a
  pickup, a van, something with six wheels. Each is an afternoon now that the profile machinery
  exists: pick a real vehicle, copy its published figures, author the parts.
- **Do doors and other moving parts open?** Undecided, and worth deciding before any more art is
  made. A door is already expressible: the slot graph supports a part hanging off a part, so a door
  is a part in a `door_left` slot with its own mass, health and fracture data, and it can be shot
  off today. What does not exist is *articulation* — a door that swings on a hinge rather than being
  either attached or gone. The design has the pieces (`D06-S5.6` specifies a breakable constraint,
  and `DEV-009` records that a part only gets one once it is a body of its own), but nothing builds
  an articulated part, and the choice between "cosmetic hinge animation on the client" and "a real
  constrained body" is a fork with very different costs.
- **Arena shapes.** Open scrapyard, tight industrial corridors, a pit with a hazard in the middle.
  This changes what vehicle builds are good, so it is a design decision, not set dressing.
- **Game modes beyond deathmatch.** `docs/01_product_game_design.md` sketches several; picking one
  early would shape the match-flow work in Phase 8.
- **Do parts get repaired?** Currently damage is permanent within a match. A repair mechanic
  changes the pacing of a fight considerably.
- **How lethal should a hit be?** The degradation curves exist and nothing has tuned them. Somebody
  has to decide whether losing a wheel is an inconvenience or the end of the fight.

**Content and tooling**

- **Author one real vehicle end to end** — Blender source, fracture, export, part manifest,
  assembly — as a vertical slice, before building any more systems. It would prove the whole content
  path works and produce the first thing that looks like a vehicle.
- **A live tuning console.** Physics constants, degradation curves and power budgets are currently
  edited and recompiled. A runtime editor pays for itself the first time somebody tunes handling.
- **Replay recording.** The simulation is deterministic by design, so a replay is a seed plus an
  input log. Cheap to build now, and it makes every future bug report reproducible.

**Technical debt worth naming**

- **`DISC-011`** — the collision-filter trap that made every suspension ray miss the ground is fixed
  for bodies, but the same trap is waiting for the next ray anyone adds: bot sensors, hitscan
  weapons, a ground check.
- **`DEC-034`** — the shipped vehicles still stop 11% and 25% longer than the cars they came from.
  That residue is the ray-cast tyre model, not the calibration, and closing it needs a tyre model
  rather than a bigger brake number.
- **`DISC-012`** — a wheel can report ground contact while carrying no load at all, so counting
  wheels in contact is not a measure of whether a vehicle is properly on its wheels. One test fixture
  had been driving on two wheels while every check said four.
- **`DISC-006`** — a latent bug in the fracture tool's point-in-mesh test. It has not produced a
  wrong result yet. It will.
- **`DEV-006`** — one test fixture does not match its own specification.
- **`DEV-008`** — Bullet has no way to remove a wheel from a vehicle, so a detached wheel is
  re-indexed on our side and left in the native controller. Harmless today; a trap later.
- **`DISC-007`** — the sandbox cannot fetch the pinned JDK 17, so every build here runs on 21 under
  an override. CI runs the real toolchain, but local results are not quite the shipping ones.

---

## 5. Where the project actually stands

Plainly: there is a working simulation, a process that runs it, and still nothing to look at.

That is a real change from last session, and a smaller one than it sounds. A vehicle now drives — it
sits on its suspension, accelerates, brakes, corners, and does all of that worse as it takes damage,
in ways that follow from which specific parts were hurt rather than from a global health bar. Lose
a wheel and the car pulls; lose the ammo feed and the guns slow down; lose armour and the mass, the
balance point and the handling all change in the same instant. That last part has been true since
Phase 2, but until this session there was no way to feel it, because nothing moved.

The other change is that `syndicate-server` is now a program rather than a placeholder. It starts,
loads what content exists, builds the right set of systems for its mode, and ticks a world at 60 Hz
until you stop it. It has nothing to put in that world — no arena, no authored vehicle, and no
network port for anyone to connect to — so what it does is tick an empty universe very reliably. But
the shape is right, and the gap between it and a real server is a list of named, ordinary pieces
rather than an unknown.

What is missing is still everything between the simulation and a person. There is no window, no
input from a device, nothing to shoot at and nothing that shoots back. Damage happens only when a
test declares it. The two things standing between here and something recognisably a game are making
a collision hurt (Phase 5) and being able to load a mesh that wasn't built out of boxes in a test
fixture (Phase 6) — and the second is one file: a reader for the format the rest of the project has
been producing since Phase 1.

Worth noting what the two bugs this session say about the project. Both were found by the first test
that tried to do the obvious thing — drive the vehicle forward — and both had been sitting in code
that reviewed as correct. One was a single bit in a collision mask; the other was a number copied
from a sample instead of from the specification that authored it. The specifications keep earning
their keep, and so does the habit of writing down why a number is what it is: the second bug was
found because a memory entry claimed nothing authored those values, and that claim was checkable.

The realistic read: something you can fight in within a handful of sessions, something you can look
at shortly after, and something recognisably a game around Phase 8. Whether it is a *good* game is
still unexplored — but for the first time the question "how does it feel to drive?" has an answer
rather than being premature, and the answer is "heavy, and a bit slow to turn". Someone should
decide whether that is what the game wants.

---

## 6. How this file gets maintained

At the end of every session, before the session summary is written:

1. **Add what happened** to §2, replacing the previous session's entry (the memory system in
   `.agent-memory/session_summaries/` is the permanent record; this section is the current one).
2. **Move the "we are here" marker** in §1 and update the system-catalogue progress bar.
3. **Re-cut §3** — what is next changes as things land, and a "next" list that still describes work
   already done is worse than no list.
4. **Add to §4** any choice the session ran into and deliberately did not make.
5. **Rewrite §5 if the honest answer changed.** Most sessions it will not. When it does, that is the
   most valuable paragraph in the file.

Restructure freely. Delete phases that stopped making sense, split ones that turned out to be three
phases wearing a coat. The structure is a convenience, not a contract — unlike `docs/`, which is.
