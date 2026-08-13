# Syndicate — Roadmap

**Last updated:** 2026-08-12 (end of SESS-022)
**Where we are:** it has a window, and the engines are no longer recordings — they are synthesised
live from what each car is doing and what state it is in. Eight cars on an arena floor, a camera
behind one of them, a scoreboard in the corner, and a supercharged V8 that burbles, misfires when you
wreck it, and doppler-shifts as it goes past. Nobody has driven it yet.

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
    A vehicle you can drive                 :done, p4, 11, 2
    Reading real art                        :done, p6a, 13, 1
    Combat - hits, damage, weapons          :done, p5, 14, 1
    A world to fight in                     :done, p6, 15, 1
    A car that drives                       :done, p6b, 16, 2
    Opponents - bots and match flow         :done, p8, 18, 1
    Preparation pipeline, sound, input      :done, p6c, 19, 1
    A window - client, render, HUD          :done, p7, 20, 1
    Audio - engines, induction, triggers    :done, p7b, 21, 1
    Real-time engine synthesis              :done, p7c, 22, 1
    The rumble - pulse fusion               :done, p7d, 23, 1
    Lope, startup, overrun (here)           :done, p7e, 24, 1

    section Next
    Driving it - tuning, balance, feel      :active, p11, 25, 2
    Preparation pipeline - stages 6 to 8    :p6d, 26, 3

    section Then
    Multiplayer                             :p9, 28, 4
    Production hardening                    :p10, 32, 3
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
  │  ●  Phase 4   A vehicle you can drive                            │
  │  │            Stats aggregate, wheels turn, a server runs ticks  │
  │  ●  Phase 6a  Reading real art                                   │
  │  │            glTF loads headlessly; two cars measured and drawn │
  │  ●  Phase 5   Combat                                             │
  │  │            Contacts become damage; weapons fire; parts die    │
  │  ●  Phase 6   A world to fight in                                │
  │  │            An arena, an asset gate, and cars cut into parts   │
  │  ●  Phase 6b  A car that drives                                  │
  │  │            Wheels on the ground, spinning, and detachable     │
  │  ●  Phase 8   Opponents                     ★ PLAYABLE           │
  │  │            Bots, a match that starts, scores and ends         │
  │  ●  Phase 6c  Parts, sound and input                             │
  │  │            A car cut up by geometry; 52 sounds; a gamepad     │
  │  ●  Phase 7   A window          ★ WATCHABLE                     │
  │  │            Rendering, camera, HUD, morphs, particles, audio   │
  │  ●  Phase 7b  Audio             ★ AUDIBLE                        │
  │  │            Real engines, induction, every family triggered    │
  │  ●  Phase 7c  The rumble                                         │
  │  │            Pulses fuse; a tool that measures against real cars│
  │  ●  Phase 7d  Feel                            ← THIS SESSION     │
  │  │            A flywheel, a rocking couple, a start you can hear │
  └──┼───────────────────────────────────────────────────────────────┘
     │
  ┌──┼─ NEXT ──────────────────────────────────────────────────────────┐
  │  ○  Phase 11  Driving it                          ★ IS IT ANY GOOD │
  │  │            Handling, damage numbers, bot difficulty, match len  │
  │  ○  Phase 6d  Preparation pipeline, stages 6–8                     │
  │  │            Geometry repair, hinges, per-class destruction       │
  │  ○  Phase 9   Multiplayer                                          │
  │  │            Replication, prediction, reconciliation              │
  │  ○  Phase 10  Production hardening                ★ SHIPPABLE      │
  │               Perf budgets, packaging, balance sweep, CI gates     │
  └────────────────────────────────────────────────────────────────────┘
```

### The system catalogue, which is the honest progress bar

`docs/04_entity_component_model.md#D04-S4.4` fixes 27 systems in a specific order. Twenty-three exist.

```
 1 InputCollection   ●   10 Physics          ●   19 NetworkReceive   ○
 2 InputReceive      ○   11 CollisionEvent   ●   20 Reconciliation   ○
 3 BotDecision       ●   12 Damage           ●   21 Transform        ●
 4 MatchFlow         ●   13 Fracture         ●   22 Interpolation    ●
 5 Spawn             ●   14 Detach           ●   23 DamageVisual     ●
 6 VehicleStats      ●   15 MassProperty     ●   24 Effect           ●
 7 VehicleControl    ●   16 Lifetime         ●   25 Audio            ●
 8 Weapon            ●   17 Score            ●   26 Render           ●
 9 Projectile        ●   18 NetworkSend      ○   27 EntityDestroy    ●

 ●●●●●●●●●●●●●●●●●●●●●●●○○○○  23 / 27
```

The four that remain are **all networking** (2, 18, 19, 20). There is no longer a single
unimplemented system that a single-player game needs. If multiplayer were cut tomorrow, the
catalogue would be finished.

---

## 2. What happened this session (SESS-024)

You listened to the six takes and reported three things: no startup sound at all, still no rumble
you could *feel*, and a popping on every engine "like popcorn being made". All three were right, all
three were mechanisms the model simply did not have, and one of them was a mistake this project had
already written down once and then made again.

### The lope was the right size and in the wrong place

Last session fixed the *total* amount of slow variation and you still could not feel the cycles.
That was the correct report, and the measurement says why. An engine's lope has a pitch, and it
matters where it sits:

| how often | what you feel | real Mustang | ours before | ours now |
|---|---|---|---|---|
| once per cycle (6 Hz) | the slow heave | 4.8–8.1% | 0.9% | 4.8% |
| once per revolution (12 Hz) | the thump | 8.9–16.3% | 1.1% | 11.8% |
| three times (19 Hz) | a flutter | 6.7–7.3% | 13.6% | 9.2% |

Our total was right and nearly all of it was at 19 Hz — too fast to feel as a beat — and it was
there only because each car's random cylinder trims happened to peak there. **The pitch of the lope
was effectively random per vehicle.**

Two things were missing, and neither is a knob:

**The crankshaft does not turn at a constant speed.** Each power stroke is a kick and the flywheel
only partly smooths it, so an idling engine surges and slows within every single cycle — you can
watch the needle do it. Ours integrated the crank angle at exactly the rpm the simulation reported,
which is an engine with an infinite flywheel. Adding a real one matters because a flywheel is
mathematically a *low-pass filter on the kicks*: it cannot follow the fast firing rate but it
follows the slow pattern completely, so the energy lands exactly where the feel is. It also makes a
damaged engine limp for nothing: a cylinder that does not fire does not kick.

**A cross-plane V8 rocks on its mounts once per revolution.** Its crank pins sit at 90° and the
masses do not balance end to end, which is why these engines need heavy counterweights and why one
at idle visibly shakes. A moving engine radiates sound differently as it moves. Nothing else in the
model could produce that 12 Hz thump at all — so without it a V8 is a drone.

### The popcorn was a clock

The overrun crackle fired from a fixed 26 times a second, which gave 11–14 evenly spaced pops per
second on every engine at every speed. A stream of identical clicks with no relationship to the car
is exactly what popcorn sounds like, and you identified it instantly.

Real pops happen when unburnt fuel lights as an exhaust valve opens, so they belong to exhaust
events — which means the rate rises with revs and differs between a four and a twelve. They also
come in bursts, because one bang leaves the pipe hotter, and they have *body*: a detonation shoves
gas down a pipe and thumps before it cracks. Ours was a bare 1.9 kHz hiss — all crack, no thump.
Now: 3–7 a second, clustered, irregular, with a low thump underneath.

Annoyingly, this is the same fault as one recorded two sessions ago (a crank modulation hardcoded at
6 Hz), made again by the same hand. The rule is now a check rather than a principle: before adding
any constant with "Hz" or "rate" in its name, ask what it is a rate *of*.

### The start was faithful and inaudible

Your Mustang clip cranks for about half a second before it catches, and that is what we built. You
heard nothing — and that is the right reaction, because half a second of a quiet noise, most of it
spent spinning up, is below the point where an event registers as an event. A phone recording of a
car you are standing next to and watching is not the same listening situation as an arena with seven
other cars in it.

So the crank is now about twice the measured length, the starter is louder, and it audibly *labours*
— the motor gets heavier and growls each time a compression stroke drags it down, which is the part
that makes it read as an engine being turned over rather than a fade-in. You said faking it a little
was fine; this is the little, and it is written down as a deliberate exception rather than slipped
in, so nobody "fixes" it back to the recording later.

### Three tests had to be restated, and none was weakened

Each was measuring where a thing *happened to be* rather than what it *is*. The crackle test asked
for energy in a fixed high band — the band the old hiss occupied — so it passed the popcorn and then
failed the improved version whose energy had moved lower. The crank test asked for the loudest
frequency component of a chuff whose second harmonic is bigger than its fundamental, so it reported
double the real rate on a four and a twelve. Replaced by measures that do not care about band or
shape, and every one of them would have caught the thing you reported by ear.

### The showcase, since you liked it

It is now a proper part of the repository rather than a scratch script:

```
./gradlew :game-client:showcaseAudio
```

renders one take per car — start, settle, idle, two blips with lifts, a pull to the limiter — through
the real mixer, ready to listen to. New vehicles get added to one list. That is deliberately a
first-class thing now, because every single defect in this synthesiser was found by ear first and
each one needed a *new* measurement afterwards; a test suite can only check the questions somebody
already thought to ask.

## 3. What is next

### Phase 11 — Driving it ★ *is any of this good?*

This is now the only question left that cannot be answered by writing more code, and for the first
time it can be answered at all: build the client, run it, drive the car.

Everything it needs is in place and nothing in it has been tuned by a person. The handling is a real
supercar's figures, which is a defensible starting point and possibly the wrong one for an arena
brawl. The damage numbers are blueprint defaults nobody has been hit by. The bots have a difficulty
scale nobody has lost to. The match is three minutes long because three minutes is a round number.
None of that is a bug and none of it is settleable by reading.

Concretely, the numbers a session here would move: collision damage scale and threshold, the armour
floors, the propagation fraction, the degradation curves, the steering rate and lock, the chase
camera's two half-lives, bot reaction delay and aim error, and the match length and frag limit.

The one piece of machinery that would pay for itself immediately is a **live tuning console** — those
numbers are compiled in today, and a handling pass that needs a rebuild per value is a handling pass
nobody finishes.

### Phase 6d — Preparation pipeline, stages 6 to 8

Stages 1–5 ship. What remains is named as pending in the tool's own report rather than quietly
skipped: **geometry repair** (non-manifold edges, flipped normals, degenerate faces), **hinge
rigging** so doors swing rather than only detach, and **per-class destruction authoring** so each
label gets the treatment `D15` specifies rather than everything being treated as a rigid part.

Hinges depend on an open choice in §4 — cosmetic animation or a real constrained body — so that
decision wants making before the session that implements them starts.

### Loose content work, any time

- **Fracture manifests** for the six parts, so a destroyed wheel breaks up rather than detaching
  whole. A tool run rather than a project, and the last thing between the asset pipeline and being
  a CI gate.
- **One weapon part**, so the eight weapon families have something in `assets/` to fire.
- **One armour plate that covers something**, so the interception and exposure rules have content.
- **The JSON schemas**, so malformed content fails on its shape rather than on whichever field a
  hand-written check happens to read first.
- **Tuning the numbers now that a match runs.** Collision damage scale and threshold, the armour
  floors, the propagation fraction, the degradation curves — all implemented, all at blueprint
  defaults, and for the first time there is a thing to run them against: eight bots fighting for
  sixty seconds and a report at the end of it.
- **Mixing, and the engine's own voicing.** Forty-seven sounds plus a live synthesiser, which is a
  different thing from them playing *well together*. Nothing has ever been balanced with a person
  listening: the gains in `AudioSystem` and `EngineMixer` are first guesses, and so is the
  synthesiser's own voicing — the blower level, the muffler corner, how far a wrecked exhaust opens
  up. Those are now numbers to turn rather than files to re-commission, which is the point, but
  somebody still has to turn them.
- **More reference recordings.** One real V8 moved the model further than forty generic clips did.
  There is still nothing to check the V6, V10, V12 or four against, so their sub-order content is
  inference rather than measurement. A clean recording of each would settle them the same way — and
  an MC20 in particular, since the Eclipse is the one shipped car with no reference at all.
- **Body resonance.** The synthesiser models an exhaust, not a car. Part of the Mustang's low-order
  energy is almost certainly panels and cabin, which nothing here reproduces.

### Phases 9–10 — Multiplayer, then hardening

Replication, client prediction, reconciliation and lag compensation, followed by the performance
budgets, packaging and balance sweeps that `docs/12_testing_validation_ci.md#D12-S5.4` requires
before anything ships.

## 4. Open choices — things you could ask for

None of these are decided. They are here so the options are visible when a session is being planned.

**Scope and direction**

- **Cut multiplayer from v1.** Phase 9 covers the largest and riskiest body of work in the project.
  A single-player-plus-bots game is a complete game, and the blueprints are written so that
  networking can be added later without re-architecting. This choice is now cheaper to make than it
  was: the single-player game exists and runs.
- **What the first playable build is for.** There is a build to hand somebody now, and it draws.
  Whether the next milestone is "make the thing it does good" or "make more of it" is the fork, and
  the first is cheaper and answers a question nobody has asked yet.
- **Local multiplayer.** The input layer takes the first connected pad and ignores the rest, because
  assigning pads to players needs a UI that does not exist. Split-screen is a genuinely different
  product decision and the input code is one device-list loop away from it.

**Gameplay**

- **How should it handle?** Half-answered. The two shipped vehicles handle like the real cars they
  were derived from, which is a defensible starting point and a much better one than invented
  numbers. What nobody has decided is whether a *combat* game wants that: real cars are fragile,
  grippy and fast, and an arena brawl might want something heavier and more forgiving. The place to
  find out is to drive them, and everything but the window is now in place: the cars, the controls
  the player would use, and an arena with opponents already in it.
- **Which vehicles next?** The two shipped are both fast, and now differ mainly in mass. A roster
  wants more contrast than that — a pickup, a van, something with six wheels. Each is an afternoon
  now that the profile machinery exists: pick a real vehicle, copy its published figures, author the
  parts. Finding *art* for it is the slower half.
- **What happens to the two car models before release?** Both are licensed CC-BY-NC-SA — free to
  use, credit required, and **not for commercial use**. As prototype and reference art that is
  ideal: real shapes, real proportions, real measurements to build the pipeline against. It is not
  something that can ship in a paid game. The options are to license replacements, commission
  original models, or decide the project is non-commercial. Nothing needs deciding now; it needs
  deciding before art budget is spent.
- **Do doors and other moving parts open?** Undecided, and now *blocking* — Phase 6d's hinge
  rigging cannot start until it is answered. A door is already expressible: the slot graph supports a part hanging off a part, so a door
  is a part in a `door_left` slot with its own mass, health and fracture data, and it can be shot
  off today. What does not exist is *articulation* — a door that swings on a hinge rather than being
  either attached or gone. The design has the pieces (`D06-S5.6` specifies a breakable constraint,
  and `DEV-009` records that a part only gets one once it is a body of its own), but nothing builds
  an articulated part, and the choice between "cosmetic hinge animation on the client" and "a real
  constrained body" is a fork with very different costs.
- **Which engine does each car get?** Six configurations ship, I4 through V12, and each is audibly
  a different engine rather than a pitched copy. Which one a vehicle carries is currently derived
  from its power figure; making it an explicit authoring choice per vehicle is a one-line content
  change and a real character decision — a heavy brawler with a lazy V8 reads completely differently
  from the same car with an angry I4.
- **How long is a match, and what ends it?** The runner defaults to a three-minute time limit and a
  frag count nobody has tuned. Eight bots and sixty seconds produced a match; whether it produced a
  *good* one is the first question that can now be asked by running it rather than by arguing.
- **How hard should the bots be?** Difficulty scaling exists as reaction delay and aim error and
  ships at its blueprint defaults. Nobody has played against them, so nobody knows whether the
  hardest setting is hard.
- **Arena shapes.** Open scrapyard, tight industrial corridors, a pit with a hazard in the middle.
  This changes what vehicle builds are good, so it is a design decision, not set dressing.
- **Game modes beyond deathmatch.** `docs/01_product_game_design.md` sketches several; picking one
  early would shape the match-flow work in Phase 8.
- **Do parts get repaired?** Currently damage is permanent within a match. A repair mechanic
  changes the pacing of a fight considerably.
- **How lethal should a hit be?** The degradation curves exist and nothing has tuned them. Somebody
  has to decide whether losing a wheel is an inconvenience or the end of the fight. This is now a
  *live* question rather than a hypothetical: the numbers that decide it — collision damage scale and
  threshold, the armour floors, the propagation fraction — are all implemented and all at their
  blueprint defaults, which nobody has driven against.
- **What is the first weapon?** The eight families exist in code and none of them exists as content.
  Whichever is authored first sets the tone: an autocannon makes fights about sustained accuracy, a
  cannon makes them about single decisive hits, a flamer makes them about closing distance. It is an
  afternoon's work and a real design decision.
- **What is actually in the scrapyard?** The arena is a flat box, deliberately. Cover changes what
  weapons are good, what vehicles are good, and whether ramming is a tactic or a mistake. It should
  be decided by someone who has driven in the empty one.
- **How much of a car is a part?** Now specified rather than speculative — see
  `docs/15_vehicle_preparation_pipeline.md` and §3. The taxonomy has twelve labels; the open question
  is which of them you actually want as *separable* parts. Every extra separable part is more
  physics bodies, more network state and more art to verify, so "a car that comes apart into
  fourteen pieces" is a gameplay choice with a cost, not a free upgrade.

**Content and tooling**

- **Author one real vehicle end to end** — Blender source, fracture, export, part manifest,
  assembly — as a vertical slice, before building any more systems. It would prove the whole content
  path works and produce the first thing that looks like a vehicle.
- **A live tuning console.** Physics constants, degradation curves and power budgets are currently
  edited and recompiled. A runtime editor pays for itself the first time somebody tunes handling.
- **Replay recording.** The simulation is deterministic by design, so a replay is a seed plus an
  input log. Cheap to build now, and it makes every future bug report reproducible.

**Technical debt worth naming**

- **`DISC-011`** — the collision-filter trap that made every suspension ray miss the ground. Still
  waiting for the next ray anyone adds without thinking about it, which will most likely be a bot
  sensor.
- **`DISC-017`** — the physics engine's ray test is only accurate on small shapes, and the ray-cast
  wheel is built entirely on ray tests. Ground surfaces are planes now, which are exact; the trap
  returns the moment an arena is built out of large boxes.
- **No JSON schemas.** `schemas/` is empty, so the one validation rule that catches a malformed file
  by its *shape* — rather than by whichever field a hand-written check happens to read first —
  cannot fire on either the loader or the pipeline. Both work; both are checking a list rather than
  a contract.
- **The pipeline is still not a CI gate.** Generating the real fracture manifests is the last thing
  between it and `check`.
- **Preparation pipeline stages 6–8 do not exist.** The tool reports them as pending rather than
  skipping them silently, which is the right failure mode, but a prepared car today has unrepaired
  geometry, no hinges and one destruction treatment for every part regardless of what it is made of.
- **The split meshes are 32 MB**, of which 19 MB is one chassis, because each `.glb` embeds its own
  copy of the textures it uses. Two cars is tolerable; a roster is not. The fix is shared texture
  files rather than embedded ones, and it should happen before a third vehicle is authored.
- **`DISC-016`** — the mesh reader places a skinned mesh by its node transform, which is wrong
  whenever the placement lives in the skeleton instead. Nothing shipped depends on it today, because
  the split parts have no skeletons; the next downloaded model with a live skin will.
- **Nobody has heard any of it.** Seventy-four sounds, every family triggered, and the whole of it
  verified by spectral measurement rather than by listening — because this sandbox has no audio
  device any more than it has a screen. The measurements say the V8 burbles and the turbo goes quiet
  off-throttle; whether the result is *good* is the same kind of open question as the handling.
- **`game-client` does not build here** (`DISC-024`). `jitpack.io` is blocked by the sandbox network
  policy and `gdx-gltf` is published nowhere else, so every client change since this session is
  type-checked by hand and untested until CI runs it. This will bite every future session that
  touches the client.
- **Every arena is tarmac.** The tyre loops ship for tarmac, gravel and metal, and the surface a
  wheel is on is hard-coded to the first because an arena declares none (`DEV-014`). The three files
  are correct and two of them can never play until arenas carry a surface.
- **No damage morph targets exist yet**, so slot 23 is correct code driving nothing. Deformation
  arrives with stage 7 of the preparation pipeline, and until then a damaged car looks undamaged
  until a part comes off it entirely.
- **The renderer is one draw call per part with no culling and no batching.** Forty-one instances at
  eight cars is fine; a scrapyard full of debris on an integrated GPU has never been measured, and
  D12's performance budgets have never been run against a real frame.
- **The input layer has never met a physical pad.** Every line is tested through a fake device, which
  is what makes it testable at all in a headless sandbox, and no test can tell you whether the
  steering curve feels right or whether the trigger axis indices are correct on real hardware. The
  analogue-trigger index is content precisely because it will need changing per pad and platform.
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

It is a game you can watch and hear. Two sessions ago it could only be read about in a log file; last
session it could be watched; this session its engines stopped being recordings and became machines
that are simulated, so a car sounds like what it is doing and what has been done to it.

Eight cars go onto an arena floor, drawn from real art with real paint on them. They drive, crash,
take damage, throw sparks, lose parts, and one of them wins. A camera follows one, a HUD says how
fast and how broken it is, a scoreboard says who is ahead. Every sound the design calls for now
plays: engines that crank, catch, rev, misfire and die, tyres that squeal under slip, weapons that
fire and land, shards that clatter as they settle, and a fire that keeps burning until somebody
finishes the car off. Each engine is in a place, so they pan, fade with distance and doppler past.

**Nobody has driven it, and nobody has heard it.** That remains the entire question, and this session
sharpened rather than changed it — though you have now heard six rendered takes, which is the closest
anyone has come. The handling is a real supercar's published figures. The damage
numbers are blueprint defaults. The bots ship at a difficulty nobody has lost to. And the mix — now
including the synthesiser's own voicing — is a set of first guesses. None of that is a bug, and none
of it can be settled by writing more code.

What is genuinely still missing is short and it is all networking: four systems, and a game that will
never need them if multiplayer is cut. Everything else on the "not done" list is content (damage
morphs, weapons, fracture manifests), Blender pipeline stages 6–8, or tuning.

The risk profile is unchanged, and this session added a fourth version of the same lesson with a new
edge on it, and this session added a fifth that sharpens it again. Fourth: **measure both halves of
a ratio before tuning either** — two sessions were spent raising the engine's lope, which was already
the right size, because "not enough rumble" was assumed to mean "not enough of the slow stuff" when
it meant "far too much of the fast stuff". Fifth: **a total can be right while the distribution
underneath it is wrong** — the very next session, the corrected total turned out to be sitting
entirely at the wrong frequency, which a listener heard immediately and no aggregate could show. The related
older lesson is that **the tests verify components rather than
their combination** — it is that a test written from the implementation's vocabulary tests that the
implementation *ran*, not that its output is right. Two tests covered the engine loops; both were
satisfied by the very defect that made them wrong, because both asked what the code produces rather
than what an engine is. The counter-measure has worked every single time: build the real artefact,
then measure the artefact rather than the code. This session that meant a DFT over the committed
`.wav` files, and then — the part worth keeping — replacing those two tests with ones that fail on
the old bank.

It is also worth recording that a user's ear beat the entire suite. The loops were committed,
documented, measured and green. Somebody listened to them and said no.

There is one new structural risk worth naming. `game-client` cannot be built in this environment at
all, so the largest module in the project is now the least verified — and the audio work landed
squarely in it.

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
