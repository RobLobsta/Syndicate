# PROG-041: simulation core — and the first vehicle that does not touch the ground

**Date:** 2026-08-17
**Category:** progress
**Related Docs:** docs/06_physics_simulation.md#D06-S5.5, docs/05_vehicle_part_system.md#D05-S4.2, docs/04_entity_component_model.md#D04-S4.4

**Status:** active

Supersedes: PROG-038

## Summary
Everything PROG-038 recorded still holds. What is new is that the simulation is no longer only about
things with wheels: `RotorControl` flies a rotorcraft through the same slot, the same shared
operation and the same reconciliation path that `VehicleControl` drives a car through, and the
Kestrel is on the shipped roster.

## Details

**Scope:** `game-core` — `physics`, `vehicle`, `damage`, `system`, `ecs`, `asset`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Rotorcraft flight model | done | Thrust, cyclic, torque, anti-torque, drag; `RotorControl` (DEC-090) |
| `ROTOR` category and `ROTOR_MOUNT` slot | done | In the compound shape, detaches on destroy |
| `rotor` block and `rotorThrustN` stat | done | Block is identity, stat is what degrades (DEC-039's split) |
| `collective` input axis | done | Keyboard, scripted; gamepad not yet bound |
| Terrain-aware spawn placement | done | `liftOntoTerrain`; fixes a defect cars had absorbed (DISC-071) |
| Speed clamp on a rotorcraft | done | Was missing entirely — the rotor branch returned before it |

**~~One known defect (DISC-071).~~ Fixed, 2026-08-18 (DEC-096).** A Kestrel parked on a slope used
to destroy its own rotor: neutral collective trimmed to a hover, and a tilted aircraft with no wheels
slid until the disc met the ground. The trim now engages only off the ground — 43.9 m of slide down a
12° gradient becomes none, and it still takes off. Both tests are on a **slope**; the flat-ground one
passes with the defect present. Finding the ground under a resting body is its own trap: DISC-072.

**What a rotorcraft does not have yet**, and none of it is blocked:

- **No bot can fly one.** `BotDecisionSystem` writes `throttle`, `steer` and `brake`; it never writes
  `collective`, so a bot handed the Kestrel sits at a hover at whatever height it spawned. The
  behaviour tree needs an altitude term before an aircraft can be an opponent (D11-S5.2).
- ~~**No gamepad binding** for the collective.~~ Bound to the left stick's vertical axis
  (2026-08-18). It wrote nothing at all before, so `collective` kept whatever the keyboard or the
  previous match left there; `InputRouter`'s idle path had the same hole.
- **No weapons fitted.** The Kestrel's chassis carries the same five synthesised hardpoints every
  prepared vehicle gets (D15-R42), so a gun can be fitted; nothing has been balanced for firing from
  a moving hover.
- **Ground effect, autorotation and translational lift** are all absent. The model is arcade by
  intent; these are the three things a player who flies helicopters would notice missing.

**Not blocked, but worth knowing:** the flight model's constants are tuned against one aircraft. A
second rotorcraft is the test of whether `TORQUE_PER_THRUST_M`, `ATTITUDE_STIFFNESS_NM_PER_RAD` and
`VERTICAL_DAMPING_PER_MPS` are constants or were the Kestrel's numbers wearing a constant's name.

## Rationale / Context
The reason this is a new entry rather than an edit to PROG-038 is DEC-023: a progress entry tracks one
subsystem's state, and "the simulation can fly" is a change in what the subsystem *is*, not a detail
of how far along it is. The next session reading PROG-038 alone would build the AI's vehicle handling
around a wheel model that is now one of two.

## Impact
22 physics-tagged tests pass, of which 8 are new and are the record of the flight model:
`RotorFlightTest` (hover, climb, descend, lose the main rotor, lose the tail rotor) and
`KestrelFlightTest` (the shipped content loads as a rotorcraft, climbs and holds, and is speed
clamped).
