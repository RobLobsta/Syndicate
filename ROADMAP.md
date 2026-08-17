# Syndicate — Roadmap

**Last updated:** 2026-08-17 (end of SESS-037)

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
at 1500 kg doing 0–100 in 2.9 s with a machine gun on each flank, a Stampede that is heavier, holds
speed where the Eclipse cannot, and carries a pedestal cannon on its roof with the cogs that elevate
it. Under each car's name is its armament: every mounting it has, what is on it, and left and right
to change it — only to weapons that mounting will actually take. DEPLOY drops you into an arena with
seven bots. They drive, hunt you, and ram. Panels dent, glass shatters, parts come off and change how
the car handles, sparks fly, the engine sounds like the engine it was modelled on, a chase camera
follows and a scoreboard keeps score. Escape returns you to the menu, and the whole physics world is
torn down and rebuilt when you go back in.

Five things that sentence leaves out, in the order they hurt:

1. **The guns work, the sub-parts now matter, and it has been driven — badly.** Two weapons ship, each
   an *assembly* of five or seven sub-parts. Shoot the barrel off and accuracy collapses and range
   halves; take the breech and the fire rate halves; take the receiver and the gun stops. That table
   landed this session and it is what makes a loadout a decision rather than a formality. It has now
   also been **played**, headlessly, with a scripted drive — and that first drive found two things no
   test had: at 145 km/h the desert's dunes launch the car and the anti-tunnelling clamp holds it
   airborne (DISC-063), and a road spline 50 m too long carves a canyon through the arena wall
   (DISC-062). The damage numbers are still D01's defaults, no weapon fractures when it dies, and six
   of the eight families have no content.
2. **The Desert Highway now has a highway.** A 612 m tarmac road is carved from a spline, and the
   blend either side digs cuttings and raises embankments without anybody authoring one — cover on
   both sides, and ridges you can be pushed off. Sand is slower than tarmac at the wheel now, so
   leaving the road costs you something. What the arenas still have not got is **structures**: nothing
   to take cover behind that was not extruded from a noise function. The scrapyard's haul road was
   withdrawn rather than shipped broken (DISC-062).
3. **The cars are real now, and unproven.** Each ships as thirty-odd parts — doors that hinge and
   dent, glass that shatters, lamps and grilles that come off, and now a weapon — cut from its own
   art, colour-matched to a house palette, and calibrated to a published spec sheet. Their headlights
   cast at night. Nobody has driven them in anger.
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
   ├── Headlights, beams, and a night to see them in            PROG-034
   ├── Guns: two weapons, taken apart, mounted and firing       PROG-035
   ├── Bots that orbit their target instead of shuffling        DISC-059
   └── A garage that arms the car                               DEC-084
  ─────────────────────────────────────────────────────── you are here
  NEXT
   1. Guns, finished                          ← degradation, fracture, more families
   2. Roads and structures                    ← terrain stages 3 and 4
   4. Tuning, with a console to do it from    ← the alpha gate
   5. Options, and the rest of the shell
   6. Terrain rendering, properly             ← needs a real GPU to measure
   7. Sockets
   8. Hardening and release
```

---

## 3. The plan, in order

### Step 1 — What driving it found

The client can now be **driven from a script** (`--script`) and photographed at several frames of one
run (`--capture-frames`), which is what finally closed the hole every other capture flag was working
around: a capture has no keyboard, so the half of the game that is verbs could never be looked at.
The first two drives found two things eleven passing test suites had not.

1. **The speed clamp does not know the car is airborne** (DISC-063). Full throttle in the desert
   reaches 145 km/h, launches off a dune, and the car is still flying 130 frames later reading exactly
   145 — which is `MAX_VEHICLE_SPEED_MPS` to the digit. The clamp exists to stop a chassis tunnelling
   through geometry, which a car with no wheel in contact cannot do. Exempting an airborne vehicle is
   a few lines; the interesting part is driving it again afterwards and seeing whether the dunes are
   still a problem once landings are landings.
2. **A road that reaches the border rise digs a canyon through it** (DISC-062). Measured: ±290 m of
   spline cuts 31 m, ±240 m cuts 3 m. It is a threshold at the foot of the rim, not a gradient — and
   the two guards that fire are about spawn connectivity and rim containment, neither of which
   mentions roads. The carve now logs its cut and fill, which makes it a one-line diagnosis; the real
   fix is a load-time guard so a too-long spline is an authoring error rather than eight silently
   rejected seeds.

Both are cheap. Both are the kind of thing only driving finds, which is the argument for doing this
step before anything else on the list.

### Step 2 — Structures

Stage 4 of the four D16 defines; stages 1, 2a and 3 are done. A factory, a placement pass, and four or
five things to place. Everything that *breaks* them already exists (DEC-071), so the work is two new
pieces plus a fracture run per object — and that fracture run is the catch: it needs Blender, which
the development sandbox does not have.

This matters more for the Scrapyard than for the desert. A breaker's yard with no wrecks in it is a
quarry, and it is currently a quarry.

Also here, and smaller: **the scrapyard's haul road**, withdrawn this session rather than shipped
broken. Its extent has to be measured against its own rim, which is at a different place from the
desert's because the two arenas have different cell sizes — 1 m against 2 m.

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
| Weapon damage, rate of fire, falloff | Now fully wired, including what a damaged gun does. Nothing has been tuned. |
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

- **Reference recordings** for the V6, V10, V12 and four-cylinder engines. One real V8 moved the
  synthesiser further than forty generic clips did; the other families are inference, and the Eclipse
  in particular has no reference at all.
- **Body resonance in the engine model.** The synthesiser models an exhaust, not a car. Part of a
  Mustang's low-order energy is panels and cabin, which nothing here reproduces.
- **Capture the client's screens in CI.** Now possible (DISC-046) and not yet wired. It would make a
  visual regression a failing build rather than something noticed three sessions later.
- **JSON schemas at load, still.** `schemas/` is an empty directory that D08-R18 makes a hard
  requirement and that D09-R7, D09-AC-7, D14-R7 and D14-AC-6 all cite. Eight schemas and a validator;
  it cannot be deleted, because five requirements point at it.

---

## 5. Decisions waiting on you

Not decided, and not for the assistant to decide alone.

- **How much further the garage goes.** You asked for weapon loadouts and they exist (D01-NG1a): the
  hardpoints are yours, everything else is the vehicle the artist authored. The next rung is
  *plating* — letting a player choose armour as well as guns — and that is a bigger step than it
  sounds, because a panel has geometry a weapon does not and it is the change that makes the
  `armorValue` naming question below urgent rather than tidy. Two smaller ones are also open: the
  loadout is remembered for the session and **not** saved to disk, and nothing stops you deploying a
  car with every mounting empty.
- **~~Whether single-player should route through the loopback pair~~ — deferred, deliberately.** You
  chose to leave it until the sockets step. Recording it here so the next session reads it as a
  decision rather than as an omission: four of the 27 systems have still never run in a shipping
  configuration, and that is understood and accepted for now.
- **~~Whether `armorValue` should follow `ARMOR` into a rename.~~ Decided: it stays.** You chose to
  keep it — it is the protection a part gives and every category carries it, including wheels and the
  chassis, and keeping it leaves the word "armour" free for fitted plating if the garage ever offers a
  choice of it. DEC-073 is closed on that reading.
- **What the house style should actually look like.** `assets/materials/style.json` is ten surface
  roles, a six-hue palette and a luminance band, aimed at Crossout's wasteland. Every number in it
  is a taste decision; the pipeline re-runs in about ninety seconds a vehicle, and the report says
  what the result measured. The hue list is the highest-leverage thing to change.
- **~~Whether the ground should follow the same palette.~~ Decided: it does.** Both of DEC-079's
  mechanisms — the six-hue snap and the luminance band — now apply to an arena theme's albedo, with a
  test that fails if the constants and `style.json` drift apart. The desert measured 0.561 luma
  against a 0.409 ceiling and has come down into the band; the other two themes were already under it
  and are untouched. **Still open:** whether a ground should sit at two thirds of the vehicles' ceiling
  (the number chosen) or somewhere else. It is a composition call, and it is one line.
- **When it should be dark.** Night is a launch option (`--night`) and the `N` key today, because
  headlights needed something to be visible against. Time of day is properly an arena's property —
  D16-S4 already reserves a `sky` block — and whether a match picks its hour, cycles through one, or
  always plays at the same time is a design question nobody has answered.
- **Whether a `weapon` label should be inferred or declared.** A tank's barrel is labelled by a
  geometric vote today, and on a model whose materials are named it can also be declared in
  `parts.json`. Neither has been tried against a real tank with a real turret, because there is no
  such model in `art-source/` yet. When one arrives, that first run is the answer.
- **How much a weapon should cost a build.** Fitting the cannon took the Stampede's power budget
  from 84 to 167 — the gun is 40% of the whole vehicle, and the Eclipse's pair of machine guns is
  22% of its 131. That is a defensible answer (a weapon should be the biggest single choice on a
  build) and it is a balance decision nobody has made deliberately; the number came out of a formula
  in D17-R53. If loadouts ever exist, this number is what stops every car carrying a cannon.
- **How hard bots should try to keep their distance.** They now orbit a target at 15 m rather than
  shuffling on the spot, which is both better tactics and the reason every bot in a match covers two
  to four times the ground it used to. Whether 15 m is the right range, whether it should vary by
  weapon (a cannon wants more, a flamer far less), and whether difficulty should scale it are all
  open — the number predates any weapon existing to justify it.
- **Whether a weapon should be aimable independently of the car.** Both shipped weapons are rigidly
  bolted in yaw: they point where the car points, though the cannon's cogs elevate as it aims. A
  turret ring that traverses is the obvious next thing — but turret traverse is explicitly not in
  D17 (NG1), and whether the product wants it is a design question
  about how the game plays, not a gap in the pipeline.
- **Where the cannon's carriage went.** `syndicate_weapon` discards geometry more than 40% of the
  weapon's length from its bore — on the shipped cannon that is four road wheels, an axle, a base
  plate and the trail, over half its triangles. That was your call ("drop the carriage") and it is
  the right one for a roof-mounted gun. A towed-artillery weapon type, if you ever wanted one, would
  need that geometry kept and a different rule.
- **How fast a car should be allowed to leave the ground.** DISC-063's clamp is a physics fix, but
  behind it is a design question nobody has answered: the desert at full throttle is a jump course,
  and whether that is a bug or the best thing in the game is a matter of taste. A car that flies 40 m
  off a dune is either the moment people remember or the reason the arena feels uncontrolled. Worth
  driving once the clamp no longer decides it for you.

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
