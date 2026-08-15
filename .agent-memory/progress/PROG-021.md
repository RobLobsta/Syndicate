# PROG-021: every sound family plays, and two cars breathe differently

**Date:** 2026-08-12
**Category:** progress
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.5

**Status:** superseded (by PROG-027)

## Summary
The bank goes from 52 sounds to 74 and from six silent-in-practice families to none. Engines are rebuilt as exhaust pulse trains with formants and real firing geometry, forced induction exists as a second voice, and the three families that had correct files and no triggers — tyre, weapon, debris settle — now have them.

## Details

**Scope:** `shared-models`, `asset-pipeline` `audio`, `game-core`, `game-client` `audio`, `assets/audio/`, D15-S8.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Engine loops (D15-R37a1) | done | Pulse train per firing angle, per bank, through three exhaust formants (DEC-052) |
| Loop seam (D15-R38a1) | done | Was broken on I4, V8, V10; all six now hold exactly 15 engine cycles (DISC-023) |
| Induction (D15-R36a) | done | Turbo and supercharger loops plus a blow-off; both shipped cars marked (DEC-053) |
| Start / stop / overrun | done | One of each per configuration, 18 files, pitched by the car's own idle |
| Fire loop | done | One file; driven by `BurnStackComponent`, which had run since Phase 5 with nothing attached |
| Tyre roll / skid | done | `VehicleControlSystem` (7) mirrors contact, load and slip out of `btWheelInfo` |
| Weapon fire / impact | done | `WeaponFiredEvent` from slot 8, `WeaponImpactEvent` from slot 9, both deferred |
| Debris settle | done | `DebrisSettledEvent` on the transition into `ISLAND_SLEEPING`, not on despawn |

**Measured, not asserted.** A DFT over the committed WAV files established every claim here. All six loops hold a whole number of engine cycles to within 1e-6. The V8 carries orders 3, 5, 7, 9, 11, 13 and 15 with order 3 as its spectral peak; V10 and V12 carry nothing below their firing order and the V6 keeps one order-3 offbeat. Every loop has resonant peaks where the old spectra fell monotonically.

**What the pair sounds like now.** The Eclipse is a twin-turbo V6 to 8,000: higher, busier, audibly off-boost on a lift. The Stampede is a supercharged cross-plane V8 to 7,600: a 640 Hz blower whine over an exhaust note with real odd-order rumble. Neither was true before — they differed only in firing rate and gain.

**Not verified.** `game-client` could not be built by Gradle here (DISC-024). Its audio package was type-checked with `javac` against cached jars, catching three real defects, but the module's tests did not run. CI is the first place that will be established.

## Rationale / Context
PROG-019 recorded the bank as done and playback as `not_started`; PROG-020 wired five families and named the rest as blocked on triggers that did not exist. This closes that gap and supersedes neither — PROG-019 owns the preparation pipeline, PROG-020 the client shell.

The Blender tool was deliberately untouched. D15-S5.1 stages 6, 7 and 8 remain unimplemented and are still the largest named gap in the art path.

## Impact
- `assets/audio/`: 74 files (was 52) plus a regenerated manifest; every engine loop's bytes changed.
- `shared-models`: new `Induction`; `EngineConfiguration` and `AudioEvent` extended.
- `game-core`: three new events, `WheelControllerComponent` and `DebrisTagComponent` and `ProjectileComponent` extended, slots 7, 8, 9, 13, 14 and 16 emit or mirror.
- `game-client`: `AudioSystem` runs up to five looping voices per vehicle.
- `docs/15_vehicle_preparation_pipeline.md#D15-S8`: R36 table extended; R36a, R36b, R36c, R37a1, R37a2, R38a1 added.
