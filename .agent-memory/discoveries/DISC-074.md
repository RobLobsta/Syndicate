# DISC-074: the physics test scene drove every vehicle with no PhysicsWorld

**Date:** 2026-08-18
**Category:** discoveries
**Related Docs:** docs/16_procedural_arena_generation.md#D16-S5.10, docs/06_physics_simulation.md#D06-S5.5, docs/12_testing_validation_ci.md#D12-S4.1

**Status:** active

## Summary
`DestructionTestScene` built `new VehicleControlSystem()` — the no-terrain overload — while holding a
perfectly good `PhysicsWorld` it handed to all eleven of its other systems. Both features that read
that world from inside the control operation were therefore inert in every physics test in the
repository: D16's per-surface grip (DEC-070) and, once written, the rotorcraft's ground state
(DEC-096).

## Details
`VehicleControlSystem()` delegating to `VehicleControlSystem(null)` is legitimate and documented —
"a control system with no terrain: every wheel keeps the grip its part authored" — which is exactly
why the call site read as deliberate for four sessions. Nothing distinguishes "this scene has no
terrain" from "this scene forgot to pass the world it has".

Found by accident and only because of the signal: a fix to `RotorControl` produced a slide distance
of `43.85887f`, identical to the run before it to the last digit. A behavioural change that measures
byte-identical did not execute.

Injecting `physics` is behaviour-neutral for every wheeled test — the scene generates no height field
and the surface read falls back to the part's authored grip when `terrain()` is null — and all 24
physics tests pass unchanged with it in. It is only the seed-locked determinism of those tests that
makes that statement checkable.

## Rationale / Context
The class of bug is what matters: **a test scaffold that opts out of a dependency silently opts out
of every feature that dependency carries**, including features written years after the scaffold. The
grip read has been shipped and tested since PROG-036 and has never once run under a physics test.

A future session adding anything that `VehicleControl` reads off `PhysicsWorld` — ground effect,
terrain-relative flight, per-surface tyre wear — would otherwise write it, test it, watch the test
pass, and ship it dead.

## Impact
`game-core` test scaffolding (`DestructionTestScene`). Worth checking the other scenes —
`PhysicsTestScene`, `ShippedContentScene` — for the same shape whenever a system gains a constructor
argument. The general rule: a scene that owns a `PhysicsWorld` should pass it to every system that
will take one, and a system's null-tolerant overload belongs to production code paths that genuinely
lack the dependency, not to tests that merely did not bother.
