# DISC-042: a tank classifies as one rigid chassis, and the corner model is why

**Date:** 2026-08-14
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S5.4, docs/15_vehicle_preparation_pipeline.md#D15-S4.1

**Status:** active

## Summary
Run against a tank, the pipeline exits 0 and produces a **single rigid chassis part**: no
wheels, no turret, no tracks, nothing hinged, 5,218 kg in one piece. The cue ensemble is not
what fails — it labels all sixteen road wheels correctly at high confidence — and then the
four-corner wheel model throws them away.

## Details
Measured on a synthetic mid-century medium tank: 8.4 m long, 3.55 m wide, hull and turret as
separate objects, a 3.6 m gun, six road wheels a side plus a sprocket and an idler, two track
slabs, fenders, headlights and a vision block. Built to be ordinary rather than favourable —
one object per visible assembly, materials named after their look, no `parts.json`.

What the cues get right:

| Thing | Label | Confidence |
|---|---|---|
| 12 road wheels | `wheel` | 1.70 |
| 4 sprockets and idlers | `wheel` | 1.00 |
| 2 headlights | `light` / `head` | 0.70 |
| vision block | `glass` / `lens` | 0.59 |

What they get wrong, and each for a different reason:

- **The turret is `chassis`.** At 2.6 × 3.0 × 0.75 m it spans more than a third of the vehicle,
  which is C1's rule for structure, and nothing in the taxonomy distinguishes a body that turns
  from the body it turns on.
- **The gun barrel is `chassis`**, by the same rule at 3.6 m long.
- **The tracks are `panel` with role `sill`.** A 7.4 m slab down the flank at wheel height is,
  by every measurement `_role_of` takes, a rocker panel.
- **The fenders are `panel` with role `boot`** — a 6.5 m fender read as a bootlid.
- The mantlet, cupola, stowage box and exhaust are `unclassified`.

Then the running gear is destroyed downstream. D15-S5.4 groups wheels into four corners; six
road wheels in a continuous line have no front/rear split, so all eight round things a side
were captured into **one** corner, giving a wheel of **diameter 7.04 m**. The dissolve check
correctly concluded that a 7 m disc does not rotate about an axle and dissolved both corners,
dumping 20 shells into the chassis. `corners: []`, and with no wheels there is no drivetrain.

Mass is the documented `--mass` fallback — 175 kg/m² over the footprint — which gives 5.2 t
against a real tank's 40 t. That one is expected, not a finding.

## Rationale / Context
Recorded to answer the question directly rather than by inference, and to mark where the line
actually falls. The pipeline's cue ensemble is more general than the structures built on it:
labelling generalises to a tracked vehicle almost unchanged, and everything that assumes *a
car* — four corners, a sill at flank height, a hinged door, a bootlid at the back — does not.

Supporting a tank is therefore not a tuning job on the cues. It needs a `turret` label with a
yaw articulation (D15-S5.6 rigs hinges only), a `track` label whose destruction class is
neither sheet metal nor rubber, and a wheel model that admits a road-wheel *set* per side
instead of one wheel per corner. None of that is in D15 today, and none of it should be added
speculatively — but the next session should not assume "drop in a tank" works because "drop in
a model" does.

## Impact
- A tank produces a valid, loadable, immobile asset. It fails no check, which is the point:
  nothing here is a crash to find.
- The four-corner assumption in D15-S5.4 is now known to be a vehicle-class assumption rather
  than a general one.
- The fixture lives in the scratchpad, not the repository; rebuild it from the session summary
  if it is needed again.
