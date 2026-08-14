# Syndicate — Roadmap

**Last updated:** 2026-08-14 (end of SESS-031)

This is the plan, in order. Each step below is meant to be picked up from the top: everything above
"you are here" is done, everything below it is not, and the order is the order they should be done
in. Where a step could genuinely be moved, it says so.

Unlike `docs/`, this file is a convenience rather than a contract. Restructure it freely — but keep
it sequential, and keep it honest about what does not work.

---

## 1. What this is, right now

A single-player vehicular combat game you can start, play and quit.

Launch the client and you get a title screen. PLAY takes you to a garage: two cars, drawn from their
real art, turning on a floor line, with the figures they were derived from beside them — an Eclipse
at 1500 kg doing 0–100 in 2.9 s, a Stampede that is heavier and holds speed where the Eclipse
cannot. DEPLOY drops you into an arena with seven bots. They drive, hunt you, and ram. Panels dent,
glass shatters, parts come off and change how the car handles, sparks fly, the engine sounds like the
engine it was modelled on, a chase camera follows and a scoreboard keeps score. Escape returns you to
the menu, and the whole physics world is torn down and rebuilt when you go back in.

Four things that sentence leaves out, in the order they hurt:

1. **There are no weapons.** Not "the weapons are unbalanced" — there is no weapon part in `assets/`.
   The firing, tracking, impact and scoring systems are all implemented and tested against nothing.
   Combat today is ramming.
2. **The arena is a flat box** with a grid painted on it, 300 m square, four invisible walls. A 600 m
   generated desert with dunes, passes, surfaces and a rim exists, collides, and has never been drawn.
3. **Nothing has been tuned by a person.** Handling is a real supercar's published figures. Damage
   numbers are blueprint defaults. Bot difficulty is a scale nobody has lost to. The audio mix is a
   set of first guesses.
4. **Multiplayer talks only to itself.** Snapshots, deltas, prediction and reconciliation all work,
   over a loopback transport, in one process. There is no socket.

None of those is a missing system. Every system the design specifies exists. What is left is content,
numbers, one renderer and one socket.

---

## 2. You are here

```
  DONE
   ├── Blueprints, build, guardrails, ECS                       PROG-033
   ├── Physics, vehicles, damage, destruction                   PROG-026
   ├── Blender toolchain: fracture, and model-in vehicle-out    PROG-029
   ├── Content loading, two calibrated vehicles                 PROG-028
   ├── Bots, match flow, headless runner                        PROG-031
   ├── Client: render, HUD, camera, input, engine audio         PROG-027
   ├── Replication over loopback                                PROG-032
   ├── Terrain generation, stage 1 of 4                         PROG-030
   └── Main menu, garage, and a build you can double-click      PROG-027
  ─────────────────────────────────────────────────────── you are here
  NEXT
   1. Guns                                    ← the game is not a game without these
   2. Somewhere to fight                      ← terrain stages 2–4
   3. The 25-part cars
   4. Tuning, with a console to do it from    ← the alpha gate
   5. Options, and the rest of the shell
   6. Sockets
   7. Hardening and release
```

---

## 3. The plan, in order

### Step 1 — Guns

**Why first:** everything else on this list makes a better version of a game whose central verb is
missing. D01 specifies eight weapon families; `assets/parts/` contains two chassis and four wheels.
The systems are written, tested and idle.

1. **One weapon part, authored by hand.** A machine gun: a `part.json` with a `weapon` block, a
   `mesh.glb`, and a hardpoint on both shipped chassis to mount it. This is the smallest change that
   turns the existing `WeaponSystem` and `ProjectileSystem` from tested code into gameplay.
2. **Wire it to input and to the bots.** Both already produce a `fireMask`; nothing consumes it
   because nothing is mounted.
3. **Then a second weapon that is not a third machine gun** — a shotgun or a mortar — so damage
   types, falloff and the armour table have more than one caller.
4. **A fracture manifest per weapon**, so a destroyed gun breaks up rather than vanishing.

**Done when:** you can shoot a bot, its bonnet dents, and it shoots back.

### Step 2 — Somewhere to fight

Terrain, stages 2 to 4 of the four D16 defines. Stage 1 is done.

- **2a. A top-down debug render** of the height, slope and surface grids to a PNG, from the
  verification harness. No GL, no client. Do this first: the terrain has been verified by 19 tests
  and never once looked at, and both of the last session's failures would have been obvious in one
  glance at an image.
- **2b. Draw it** (D16-S6). Chunked mesh from the same two grids the collision came from, frustum
  culling, two LOD levels with skirts, tiling textures generated at load, an analytic sky driving the
  skybox, the image-based lighting and the fog from one sun. This is the largest single piece of
  client work the project has attempted. It is **not** environment-blocked any more (DISC-046):
  the client builds, runs and screenshots in the sandbox under `xvfb-run`.
- **2c. Make the desert the default arena** and retire the flat box, or rename it — `Scrapyard`
  currently promises scenery that does not exist.
- **2d. Roads and surfaces** (D16-S5.4, S5.10). The spline carve, cut and fill, and per-surface grip
  at the wheel. This is what turns a landscape into an arena: a raised tarmac ribbon with sand either
  side, cuttings you can be pushed into, and sand that is slower than tarmac. The surface grid it
  needs already exists and is already populated.
- **2e. Structures** (D16-S7). A factory, a placement pass, four or five things to place. Everything
  that breaks them already exists (DEC-071), so the work is two new pieces plus a fracture run per
  object.

**Done when:** you are fighting in a place, not on a plane.

### Step 3 — The 25-part cars

The preparation pipeline produces, for both shipped cars, about twenty-five parts with doors that
open and dent and glass that shatters into twenty-four shards. The game still loads the old four-part
cut. Everything needed to swap them has been built and verified against the hand-authored content —
the axles land on the same millimetre and the wheels weigh what the authored ones weigh.

The step that has not been taken is overwriting committed content the game currently loads, which is
a decision to make deliberately rather than as a side effect of testing.

**Highest value per hour on this list.** It is what turns "25 parts per car" from a number in a
report into a car on the arena floor that sheds a door when you hit it.

### Step 4 — Tuning, with a console to do it from ★ the alpha gate

This is the only question left that cannot be answered by writing code, and it is the one that
decides whether the game is any good.

**Build the live tuning console first.** Every number below is compiled in today, and a handling pass
that needs a rebuild per value is a handling pass nobody finishes.

Then turn them, in roughly this order:

| What | Why it is first / last |
|---|---|
| Steering rate and lock, chase camera half-lives | Feel. Everything else is judged through them. |
| Collision damage scale and threshold | Decides whether ramming is the game or a nuisance. |
| Weapon damage, rate of fire, falloff | Only meaningful once step 1 exists. |
| Armour floors, propagation fraction, degradation curves | Decides how long a fight lasts and how it ends. |
| Bot reaction delay and aim error | Difficulty. Use the headless runner, then confirm by playing. |
| Match length and frag limit | Cheapest to change, so change it last. |
| Audio gains and the synthesiser's voicing | Nothing has ever been balanced with a person listening. |

Use the offline headless runner for everything that is not about feel — eight bots for sixty seconds
with a report at the end is a much faster loop than driving.

**This is the alpha gate.** A build that reaches the end of step 4 is worth putting in front of a
playtester. A build before it is worth putting in front of you.

### Step 5 — Options, and the rest of the shell

Once the game is worth playing, it needs the things a player expects around it: a settings screen
(resolution, volume, bindings, difficulty), a pause menu, a post-match results screen that is not the
in-match scoreboard, and persistence of the vehicle you last chose. Small, and all of it obvious once
`GameShell` exists.

### Step 6 — Sockets

What stands between here and real multiplayer, specifically:

1. **A socket transport.** One class implementing an interface that already exists — TCP for the
   reliable channel, UDP for state — behind which every other piece of replication already works.
2. **Wire the two runtime shells to build one.** Today both construct a world with no network
   endpoints, so the four networking systems are absent from every shipping schedule. Single-player
   should go through the in-process pair, which is what makes "there is no separate single-player
   code path" true in practice rather than on paper — and exercises replication every time anyone
   plays.
3. **Lag compensation.** Rewinding a target to where the shooter saw it. Fully specified, nothing
   written. Without it, hitting a moving car at 150 ms of latency means leading it by a car length.
4. **The messages nobody has needed yet** — scoreboard, match phase, chat, ping. Their slots on the
   wire are reserved so adding them cannot renumber what already ships.

**Movable.** If multiplayer matters more to you than polish, this can come before step 5, or even
before step 4 — but not before step 1, because there is no point synchronising a game with no guns.

### Step 7 — Hardening and release

The performance budgets, packaging and balance sweeps D12-S5.4 requires before anything ships.
Bandwidth is now measurable for the first time: the budget is 128 kbit/s down and 32 kbit/s up per
client in a twelve-player match, and nothing has yet counted what a real match sends. Frame-rate
budgets need a machine with a real GPU — the sandbox runs software GL at about 4 fps, which is not a
measurement of anything.

Also here: an installer (`packageWindows` currently produces an app image, not an `.msi`), a crash
reporter, and a first-run experience that does not assume the player read a README.

---

## 4. Off to the side

Real work, no fixed place in the sequence.

- **Vehicles that are not cars.** The preparation pipeline makes *cars*, and now knows exactly where
  that stops: a tank runs through cleanly and comes out as one immobile lump. Three things would
  change that, in the order they matter — a **road-wheel set** (the wheel model admits one wheel per
  corner; a tracked vehicle has six a side), a **turret label with a yaw articulation** (hinges are
  rigged, nothing rotates about a vertical axis, and a turret is the first part that is *aimed*
  rather than opened), and a **track label** whose destruction behaves like neither sheet metal nor
  rubber. Worth doing when a second vehicle *class* is actually wanted.
- **More vehicles that are cars.** A command, not a project: drop a model in `art-source/vehicles/`
  and run the pipeline. The roster is the cheapest content this project has.
- **JSON schemas enforced at load.** `schemas/` exists and nothing reads it, so malformed content
  fails on whichever field a hand-written check happens to read first.
- **Reference recordings** for the V6, V10, V12 and four-cylinder engines. One real V8 moved the
  synthesiser further than forty generic clips did; the other families are inference, and the Eclipse
  in particular has no reference at all.
- **Body resonance in the engine model.** The synthesiser models an exhaust, not a car. Part of a
  Mustang's low-order energy is panels and cabin, which nothing here reproduces.
- **Capture the client's screens in CI.** Now possible (DISC-046) and not yet wired. It would make a
  visual regression a failing build rather than something noticed three sessions later.

---

## 5. Decisions waiting on you

Not decided, and not for the assistant to decide alone.

- **What `ARMOR` means.** It currently names both "plating a player bolts on" and "any body panel —
  a door, a bonnet, a bumper". Both readings are defensible and they are not the same concept. Three
  options, cheapest first: leave it and document it; rename the category to `PANEL` and reserve
  `ARMOR`; or split it in two. **Recommendation: rename to `PANEL`** — it is a rename plus a doc
  amendment, and it stops the word promising a loadout feature the product does not have (D01-NG1).
  Full write-up in `.agent-memory/decisions/DEC-073.md`. Getting this wrong is cheap now and
  expensive once several prepared vehicles ship.
- **Whether the flat arena survives.** Once the desert is drawn, `arena_scrapyard_01` is either
  deleted, kept as a test fixture, or actually built into the scrapyard its name promises.
- **How far the garage goes.** D01-NG1 rules out a part-by-part editor and the data model permits
  one. The current garage picks a prebuilt vehicle, which is what the product says. If you want
  loadouts — pick a chassis, then bolt on weapons and plating — that is a product change, and it is
  the change that makes the `ARMOR` question above urgent rather than tidy.
- **Whether single-player should route through the loopback pair** now or at step 6. Doing it early
  costs a session and tests replication continuously; doing it late keeps the current path simple.

---

## 6. How this file is maintained

At the end of every session, before the session summary:

1. **Move the "you are here" line** in §2 and add what landed above it.
2. **Re-cut §3.** A "next" list that still describes finished work is worse than no list. If a step
   is done, delete it — the record is in `.agent-memory/progress/`, not here.
3. **Rewrite §1 if the honest answer changed.** Most sessions it will not. When it does, that is the
   most valuable paragraph in the file.
4. **Add to §5** any choice the session ran into and deliberately did not make.

Keep it sequential. The previous version of this file was a gantt chart with twenty-seven
out-of-order phases and a progress bar counting systems that all existed; it was a record of how the
work had happened rather than a plan for what to do next, and nobody could read it and know what to
pick up.
