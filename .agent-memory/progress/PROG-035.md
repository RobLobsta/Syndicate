# PROG-035: content — vehicles that carry weapons, and the tool that makes them

**Date:** 2026-08-16
**Category:** progress
**Related Docs:** docs/17_weapon_system.md#D17-S5.1, docs/08_asset_pipeline.md#D08-S4.6, docs/15_vehicle_preparation_pipeline.md#D15-S5.10

**Status:** active

Supersedes: PROG-034

## Summary
The shared library is no longer empty. Two modular weapons ship — a machine gun and a cannon, cut
from downloaded models by a third Blender tool — each as an assembly of five or six sub-parts with
its own mass, health, damage state and articulation. Both are fitted to the shipped vehicles, both
render in the garage and in a match, and both have been photographed there.

## Details

**Scope:** `docs/`, `blender-tool/syndicate_weapon`, `assets/parts/`, `assets/vehicles/`,
`shared-models`, `game-core`, `game-client`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| D17 blueprint | done | Eighteenth document; D00, D05 and D08 amended in the same commit |
| `syndicate_weapon` tool | done | Ten stages, ten `WEAP-` self-checks, run on both models |
| Weapon sub-part taxonomy | done | Nine labels, closed, each with category/slot/class/material |
| Bore-axis correction | done | Two normalisations before labelling (DISC-057) |
| Cue ensemble | done | Four families; the axial cue does most of the work |
| Seam placement | done | Contact-region centroid, verified as contact rather than as a gap |
| Carriage discard | done | D17-R27's cylinder; 11 shells and 5,204 triangles off the cannon |
| Size-class gating | done | `SizeClass`, `A316`, `A221`; five hardpoints classed by position |
| Recoil and knockback | done | Momentum formula, queued impulses, measured on the real world |
| Cosmetic articulation | done | One block, five motions; barrel recoil ships on both weapons |
| Shipped weapon content | done | 11 part directories in the shared library, both fitted |
| Garage slot-chain walk | done | Previously drew only chassis children |
| Sub-part degradation table | not_started | D17-S5.13 is specified; nothing reads it yet |
| Weapon fracture manifests | not_started | No weapon sub-part fractures; all detach whole |
| The other six families | not_started | Only AUTOCANNON and CANNON have content |

**`syndicate_weapon`** implements all ten stages of D17-S5.1: load and unit pre-scale, house style,
geometry repair, connected-shell separation, carriage discard, the four-family weapon cue ensemble,
grouping into sub-parts, the slot graph with contact-placed seams, articulation authoring, family and
stat derivation, per-class destruction and export, then ten `WEAP-` self-verification checks. It
reuses D15's style, repair, shell and export stages unchanged and D09's fracture and morph generation
unchanged.

**`weapon_machinegun_01`** — AUTOCANNON, LIGHT, 45.6 kg at 0.9 m. Five sub-parts: a synthesised
mount, receiver, barrel (three coaxial tubes collected into one), breech and muzzle. The barrel
recoils 32 mm per shot. Fitted to the Eclipse's `hardpoint_bonnet`.

**`weapon_cannon_01`** — CANNON, HEAVY, 273.9 kg at 1.8 m. Six sub-parts; the carriage — four road
wheels, axle, base plate and trail, 5,204 of 8,898 triangles — is discarded by D17-R27 and named in
the report. Fitted to the Stampede's `turret_main`.

**Runtime.** Size-class gating (`A316`), recoil and knockback impulses, and cosmetic articulation all
exist and are tested. `GaragePreview` walks the slot chain, which it previously did not.

## Rationale / Context
PROG-034 recorded the shared library as "empty, waiting for weapons" and named five hardpoints per
vehicle that nothing filled. That is the gap this closes.

## Impact
- 11 new shared-library part directories; both assemblies gained a weapon subtree.
- Class power-budget targets moved to include the fitted weapon: medium 74 → 102.7, heavy 84 → 153.0.
- Not done: no weapon has a fracture manifest yet (all `RIGID` and `STRUCTURAL`, no `GLASS`), the
  D17-S5.13 sub-part degradation table is specified and **not implemented**, and no weapon of the
  remaining six families exists.
