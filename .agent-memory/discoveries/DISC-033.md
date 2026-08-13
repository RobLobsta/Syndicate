# DISC-033: a matching sub-order total hid a lope in the wrong place

**Date:** 2026-08-13
**Category:** discoveries
**Related Docs:** docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R38a16, D15-R38a17

**Status:** active

## Summary
DISC-030 fixed the ratio of sub-order to firing-order modulation and the user still reported no
feelable rumble. They were right. The total was correct and every percentage point of it was at the
wrong order.

## Details
Per-order envelope modulation at idle, ours against the reference Mustang:

| order | rate | real | ours |
|---|---|---|---|
| 1 | 6 Hz | 4.8–8.1% | 0.9% |
| 2 | 12 Hz | 8.9–16.3% | 1.1% |
| 3 | 19 Hz | 6.7–7.3% | 13.6% |

A real V8's lope lives at orders 1 and 2 — six and twelve times a second, which is the rate a
listener *feels*. Ours put almost all of it at order 3, and worse, order 3 was where it landed only
because the random per-cylinder trims happened to peak there for that seed: **the lope's pitch was
effectively random per vehicle.**

Two mechanisms were missing, and neither is a tuning knob:

**The crank does not turn at a constant speed.** Each power stroke is an impulse the flywheel only
partly smooths, so an idling engine surges and drops within every cycle — 28% peak to peak for an
eight here, 8% by 3,000 rpm. A flywheel is a low-pass on torque impulses, so the ripple lands on
the lowest orders *by construction*. Nothing else in the model preferred them. It also makes a
misfiring engine limp for free: a cylinder that does not fire does not kick.

**A cross-plane V8 rocks on its mounts once per revolution.** Its crank throws sit at 90° and the
reciprocating masses do not balance end to end. That is order 2, and nothing else produces it — the
bank imbalance lands on odd orders, the flywheel on order 1. With it, order 2 goes 1.1% to 11.8%.

## Rationale / Context
The general lesson is sharper than the specific one: a summed metric can be satisfied by the wrong
distribution. DISC-030's ratio was a real improvement and a genuine measurement, and it was still
the wrong number to optimise on its own. When a metric aggregates over a dimension, check the
distribution along that dimension before believing it.

## Impact
- `EngineSynth`: flywheel speed ripple and rocking couple added; both fade with engine speed and
  are off while cranking, because neither has a source there.
- V8 at idle: order 1 0.9% → 4.8%, order 2 1.1% → 11.8%, order 3 13.6% → 9.2%.
- D15-R38a16, R38a17 added; T-D15-23 added.
