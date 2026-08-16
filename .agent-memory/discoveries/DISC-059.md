# DISC-059: a bot at its standoff distance was shuffling, not driving

**Date:** 2026-08-16
**Category:** discoveries
**Related Docs:** docs/11_ai_bots_and_match_simulation.md#D11-S5.1

**Status:** active

## Summary
Fitting the shipped vehicles with weapons turned `HeadlessMatchTest.everyBotDrives` red: the armed
Stampede covered 18.4 m against a 20 m threshold while its opponents covered 118 m. The cannon was
not the cause. A bot already at engagement range was being handed a destination a couple of metres
from where it stood — inside its own turning circle — and it ground back and forth at walking pace
for the entire match.

## Details
`maintainEngagementRange` returned a point on the line to the target at `ENGAGE_STANDOFF_M` (15 m).
For a bot 11–19 m out that point is 4 m away, usually *behind* it — inside the turning circle, so no
steering angle reaches it. The solver answered with full lock, and because the bot never got above
`CREEP_SPEED_MPS` the creep floor kept feeding it just enough throttle to scrub round without ever
accelerating. Steer flipped between +1 and −1 for a thousand ticks.

Three experiments separated content from code, in this order:

1. Halving the cannon's mass (389 → 237 → 178 kg) moved the distance by 0.1 m. Not mass.
2. Stripping the cannon made it **worse** (18.4 → 10.3 m). Not the weapon.
3. Reverting *both* assemblies to their pre-weapon state made the test pass with every runtime change
   still in place. So: content triggered it, code did not cause it.

A per-tick probe showed the mechanism directly: throttle pinned at 0.45 (the 0.6 creep floor times
NORMAL's 0.75 aggression), steer at full lock, destination 4 m away and moving.

The fix is a standoff **band**. Outside ±8 m the bot closes or backs off along the radius as before;
inside it the bot orbits, aiming 60° around the standoff circle — a 15 m chord, outside
`ARRIVE_RADIUS_M` and far outside any turning circle it has. Direction comes from the bot's own nose,
deterministic (G3) and varying between bots without touching a random stream.

## Rationale / Context
The failing test named a vehicle and a number, so the effort went into the vehicle and the number.
What the metric actually measured was how long a bot had a *far* destination, and arming the grid
changed how quickly bots closed to engagement range. When a gameplay metric moves after a content
change, the content is the trigger and rarely the cause.

## Impact
- Every bot drives further, not just the Stampede: across seven seeds the Eclipse went from 34–168 m
  to 108–251 m and the Stampede from 15–27 m to 18–78 m.
- D11-S5.1 gained `maintainEngagementRange` as pseudo-code and requirement R7a; E19 and T-D11-23 were
  added in the same commit. `BehaviourTreeEngagementTest` is the test.
- `everyBotDrives` was thin at one seed and would have failed at seed 1 even unarmed (8.9 m). The
  orbit is what makes it a real assertion rather than a coin toss.
