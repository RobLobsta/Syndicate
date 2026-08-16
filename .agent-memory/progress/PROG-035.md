# PROG-035: content — vehicles that carry weapons, and the tool that makes them

**Date:** 2026-08-16
**Category:** progress
**Related Docs:** docs/17_weapon_system.md#D17-S5.1, docs/08_asset_pipeline.md#D08-S4.6, docs/15_vehicle_preparation_pipeline.md#D15-S5.10

**Status:** active

Supersedes: PROG-034

## Summary
The shared library is no longer empty. Two modular weapons ship — a machine gun and a pedestal cannon, cut
from downloaded models by a third Blender tool — each as an assembly of five or seven sub-parts with
its own mass, health, damage state and articulation. Three are fitted across the shipped vehicles,
the machine gun twice: once on each flank of the Eclipse, the left one a mirror of the right.

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
| Stray-geometry discard | done | `STRAY_RADIUS`; the carriage rule was wrong and is gone (DISC-060) |
| Mirroring | done | `--mirror` reflects across the bore plane; `weapon_machinegun_l_01` is the first |
| Size-class gating | done | `SizeClass`, `A316`, `A221`; five hardpoints classed by position |
| Recoil and knockback | done | Momentum formula, queued impulses, measured on the real world |
| Cosmetic articulation | done | One block, five motions; barrel recoil on both, cog elevation on the cannon |
| Shipped weapon content | done | 17 part directories in the shared library, three fitted |
| Garage slot-chain walk | done | Previously drew only chassis children |
| Sub-part degradation table | not_started | D17-S5.13 is specified; nothing reads it yet |
| Weapon fracture manifests | not_started | No weapon sub-part fractures; all detach whole |
| The other six families | not_started | Only AUTOCANNON and CANNON have content |

**`syndicate_weapon`** implements all ten stages of D17-S5.1: load and unit pre-scale, house style,
geometry repair, connected-shell separation, stray discard, the four-family weapon cue ensemble,
grouping into sub-parts, the slot graph with contact-placed seams, articulation authoring, family and
stat derivation, per-class destruction and export, then ten `WEAP-` self-verification checks. It
reuses D15's style, repair, shell and export stages unchanged and D09's fracture and morph generation
unchanged.

**`weapon_machinegun_01`** — AUTOCANNON, LIGHT, 17.7 kg at 0.9 m. Five sub-parts: the side bracket
as `mount`, receiver, barrel (three coaxial tubes collected into one), breech and muzzle. The barrel
recoils 16 mm per shot. Fitted to the Eclipse's `hardpoint_flank_r`, and `weapon_machinegun_l_01` —
the same model mirrored across its bore plane — to `hardpoint_flank_l`. The bracket is why: it is a
side mounting, and a bonnet does not suit it.

**`weapon_cannon_01`** — CANNON, HEAVY, 178.1 kg at 1.8 m. Seven sub-parts: the base plate and column
as `mount`, two cogs, receiver, barrel, muzzle and breech. The cogs `ELEVATE` on `AIM` and the barrel
recoils 21 mm on `FIRE`. Fitted to the Stampede's `turret_main`.

**Runtime.** Size-class gating (`A316`), recoil and knockback impulses, and cosmetic articulation all
exist and are tested. `GaragePreview` walks the slot chain, which it previously did not.

**One bot fix came out of this**, because arming the grid made bots reach engagement range far sooner
and exposed a driving bug that had been latent since the behaviour tree was written: a bot already at
standoff distance was handed a destination inside its own turning circle and shuffled in place for
the rest of the match. It now orbits (D11-S5.1 R7a, DISC-059).

## Rationale / Context
PROG-034 recorded the shared library as "empty, waiting for weapons" and named five hardpoints per
vehicle that nothing filled. That is the gap this closes.

## Impact
- 17 new shared-library part directories; both assemblies gained a weapon subtree.
- Class power-budget targets moved to include the fitted weapons: medium 74 → 131.5, heavy 84 → 166.6.
- Not done: no weapon has a fracture manifest yet (all `RIGID` and `STRUCTURAL`, no `GLASS`), the
  D17-S5.13 sub-part degradation table is specified and **not implemented**, and no weapon of the
  remaining six families exists.
