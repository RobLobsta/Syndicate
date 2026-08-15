# PROG-034: content — real vehicles, owned parts, and a shared library waiting for weapons

**Date:** 2026-08-15
**Category:** progress
**Related Docs:** docs/08_asset_pipeline.md#D08-S4.6, docs/08_asset_pipeline.md#D08-S4.2, docs/15_vehicle_preparation_pipeline.md#D15-S4.5

**Status:** active

Supersedes: PROG-028

## Summary
The shipped vehicles are what the preparation pipeline produces: 27 and 26 real part types under the
vehicles that own them, style-normalised, calibrated to their reference cars, with doors that hinge,
glass that shatters, panels that dent and five empty hardpoints each. The five-part
chassis-and-wheels content is retired. `assets/parts/` is now the shared library and is empty.

## Details

**Scope:** `assets/`, `shared-models` `AssetPaths`, `game-core` `asset`, `asset-pipeline`.

**Status of Work:**

| Area | State | Notes |
|---|---|---|
| Two-bucket asset layout | done | `AssetPaths`; A106 names both paths, A315 refuses a foreign part |
| Per-vehicle `manifest.json` | done | Beside the parts; nothing loads it, everything can read it |
| Prepared vehicle content | done | 53 parts, 2 assemblies, 0 blocking findings, index rebuilt |
| Style normalisation | done | `assets/materials/style.json`, applied at stage 1b |
| Researched calibration | done | `art-source/vehicles/*/profile.json`, held to `VehicleProfiles` by test |
| Hardpoints on every vehicle | done | 1 turret mount + 4 hardpoints, derived from the body box |
| `weapon` block loading | done | Was specified and unparsed; a WEAPON part can now actually fire |
| `module` block loading | done | `ModuleFamily`, `ModuleBlock`, two new stats |
| Weapon and module content | not_started | The library is empty; this content is authored separately |
| Active-module runtime | not_started | The data contract exists, no system consumes it |
| Vehicle art beyond two cars | not_started | The pipeline takes a third whenever one arrives |

**Key Files:**
- `shared-models/.../AssetPaths.java` — where a part lives, for all four consumers
- `assets/vehicles/<id>/parts/manifest.json` — what a vehicle is made of and what each part is worth
- `assets/materials/style.json` — the house palette
- `art-source/vehicles/<name>/profile.json` — the researched figures for that model

**Blocked On:** nothing.

## Rationale / Context
The retired content was two chassis and four wheels: a car that could lose a wheel and nothing else.
Every part of the destruction model beyond that — a door shot off, a windscreen shattered, a bonnet
dented — existed in code and had no content to act on.

## Impact
Regenerating the content exposed three real defects the hand-cut assets had been hiding (DISC-048,
DISC-049, DISC-050) and one loader defect: an empty `stats: {}` was read as a decorative part
declaring stats, which made every decorative part on both cars an A205 error.
