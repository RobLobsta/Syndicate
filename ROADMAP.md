# Syndicate — Roadmap

**Last updated:** 2026-08-15 (end of SESS-033)

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

1. **There are no weapons.** Not "the weapons are unbalanced" — there is no weapon part in
   `assets/parts/`. The firing, tracking, impact and scoring systems are all implemented and tested
   against nothing. Combat today is ramming. Everything *around* a weapon is now ready for one: the
   loader reads a weapon's definition, both cars offer a turret mount and four hardpoints, and
   `assets/parts/` is a shared library any vehicle can draw from. What is missing is the content.
2. **The arenas are real places now, but bare ones.** Both generate from a theme and a seed — a
   Desert Highway of dunes and scoured rock, a Scrapyard of flat compacted yard with spoil heaps
   across it — and both are drawn. What they have not got is roads, structures, or anything to take
   cover behind that was not extruded from a noise function.
3. **The cars are real now, and unproven.** Each ships as twenty-odd parts — doors that hinge and
   dent, glass that shatters, lamps and grilles that come off — cut from its own art, colour-matched
   to a house palette, and calibrated to a published spec sheet. Their headlights cast at night.
   Nobody has driven them in anger.
4. **Nothing has been tuned by a person.** Handling is a real supercar's published figures. Damage
   numbers are blueprint defaults. Bot difficulty is a scale nobody has lost to. The audio mix is a
   set of first guesses.
5. **Multiplayer talks only to itself.** Snapshots, deltas, prediction and reconciliation all work,
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
   ├── Content: two real vehicles, 53 owned parts, a style       PROG-034
   ├── Bots, match flow, headless runner                        PROG-031
   ├── Client: render, HUD, camera, input, engine audio         PROG-027
   ├── Replication over loopback                                PROG-032
   ├── Terrain: generation, themes, and the ground drawn        PROG-030
   ├── Main menu, garage, and a build you can double-click      PROG-027
   ├── The real cars, and hardpoints to hang weapons on         PROG-034
   └── Headlights, beams, and a night to see them in            PROG-034
  ─────────────────────────────────────────────────────── you are here
  NEXT
   1. Guns                                    ← the game is not a game without these
   2. Roads and structures                    ← terrain stages 3 and 4
   3. Tuning, with a console to do it from    ← the alpha gate
   4. Options, and the rest of the shell
   5. Terrain rendering, properly             ← needs a real GPU to measure
   6. Sockets
   7. Hardening and release
```

---

## 3. The plan, in order

### Step 1 — Guns

**Why first:** everything else on this list makes a better version of a game whose central verb is
missing. D01 specifies eight weapon families; `assets/parts/` is empty. The systems are written,
tested and idle.

Everything except the gun itself is now in place. A weapon's definition is read by the loader rather
than silently discarded; `assets/parts/` is the shared library a weapon lives in, so one autocannon
fits every car; and both vehicles offer `turret_main` plus four hardpoints, each rated for about 120
kg and listed with its position in the vehicle's `parts/manifest.json`.

1. **One weapon part, authored by hand.** A machine gun: a `part.json` with a `weapon` block and a
   `mesh.glb`, dropped into `assets/parts/`. It needs no change to either car — the mounts exist.
   This is the smallest change that turns `WeaponSystem` and `ProjectileSystem` from tested code
   into gameplay.
2. **Wire it to input and to the bots.** Both already produce a `fireMask`; nothing consumes it
   because nothing is mounted.
3. **Then a second weapon that is not a third machine gun** — a shotgun or a mortar — so damage
   types, falloff and the armour table have more than one caller.
4. **A fracture manifest per weapon**, so a destroyed gun breaks up rather than vanishing.

**Done when:** you can shoot a bot, its bonnet dents, and it shoots back.

### Step 2 — Roads and structures

Stages 3 and 4 of the four D16 defines. Stages 1 and 2a are done: the ground generates from a theme,
collides, and is drawn.

- **2a. Roads and surfaces** (D16-S5.4, S5.10). The spline carve, cut and fill, and per-surface grip
  at the wheel. This is what turns a landscape into an arena: a raised tarmac ribbon with sand either
  side, cuttings you can be pushed into, and — finally — sand that is slower than tarmac and sounds
  different under the tyres. The surface grid it needs exists and is populated; what is missing is
  the carve that puts tarmac in it and the four lines in the wheel code that read it. It is also
  what makes the Desert *Highway* deserve its name.
- **2b. Structures** (D16-S7). A factory, a placement pass, and four or five things to place.
  Everything that breaks them already exists (DEC-071), so the work is two new pieces plus a fracture
  run per object. This matters more for the Scrapyard than for the desert: a breaker's yard with no
  wrecks in it is a quarry.
- **2c. A third theme, once those two exist.** Themes are cheap now — a relief layer, a palette and
  eight numbers — and the marginal one costs a day rather than a session. Worth waiting until roads
  and structures are in, so a new theme arrives complete rather than as more empty ground.

### Step 3 — Tuning, with a console to do it from ★ the alpha gate

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

### Step 4 — Options, and the rest of the shell

Once the game is worth playing, it needs the things a player expects around it: a settings screen
(resolution, volume, bindings, difficulty), a pause menu, a post-match results screen that is not the
in-match scoreboard, and persistence of the vehicle you last chose. Small, and all of it obvious once
`GameShell` exists.

### Step 5 — Terrain rendering, properly

What exists draws the ground as one mesh, decimated to a stride, with a material per surface. That is
enough to look at, drive on and judge — and it is not D16-S6. What is missing is everything that
makes a 600 m arena hold a frame rate: chunking, frustum culling, two levels of detail with skirts,
and generated tiling textures in place of flat colours. Plus the analytic sky driving the skybox, the
image-based lighting and the fog from one sun (D16-S6.3), which is what stops the horizon being a
flat grey band.

**This one genuinely needs hardware.** The development sandbox runs software GL at four to ten frames
per second, which is the renderer being emulated and says nothing about how the real thing performs.
Doing this work without a machine that can measure it is guessing.

The decimation is also visible today: the desert's dune slip faces read as jagged where the stride
skips them. That is the stride, not the classifier, and chunking fixes it.

### Step 6 — Sockets

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

- **How far the garage goes.** D01-NG1 rules out a part-by-part editor and the data model permits
  one. The current garage picks a prebuilt vehicle, which is what the product says. If you want
  loadouts — pick a chassis, then bolt on weapons and plating — that is a product change, and it is
  the change that makes the `ARMOR` question above urgent rather than tidy.
- **Whether single-player should route through the loopback pair** now or at step 7. Doing it early
  costs a session and tests replication continuously; doing it late keeps the current path simple.
- **Whether `armorValue` should follow `ARMOR` into a rename.** The category became `PANEL`; the
  stat stayed `armorValue`, because it is the protection a part gives and every category carries it,
  including wheels and the chassis. That separation is deliberate and now leaves the word "armour"
  free for fitted plating if the garage ever offers a choice of it. If you would rather the word
  disappeared entirely, the rename is mechanical but touches the JSON schema and the Blender tool.
- **What the house style should actually look like.** `assets/materials/style.json` is ten surface
  roles with a colour band and a reflectance each, set to a grimy industrial reading of the brief:
  desaturated paint, dull chrome, black rough rubber, everything dusted. It is one file and every
  number in it is a taste decision made without you. The current result is a charcoal car with a
  warm cast, which you have now seen — move the numbers if it is not what you meant. The pipeline
  re-runs in about ninety seconds a vehicle.
- **When it should be dark.** Night is a launch option (`--night`) and the `N` key today, because
  headlights needed something to be visible against. Time of day is properly an arena's property —
  D16-S4 already reserves a `sky` block — and whether a match picks its hour, cycles through one, or
  always plays at the same time is a design question nobody has answered.
- **Whether a `weapon` label should be inferred or declared.** A tank's barrel is labelled by a
  geometric vote today, and on a model whose materials are named it can also be declared in
  `parts.json`. Neither has been tried against a real tank with a real turret, because there is no
  such model in `art-source/` yet. When one arrives, that first run is the answer.
- **Whether a match should be able to pick its theme**, rather than its arena. `--arena` names a
  file; nothing stops a mode from naming a theme and generating a fresh map with spawn points placed
  to suit. That is a small change to the arena loader and a real change to what a "map" is.

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
