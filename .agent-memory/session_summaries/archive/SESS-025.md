# SESS-025: the last four systems, and the catalogue is closed

**Date:** 2026-08-13
**Category:** session_summaries
**Related Docs:** docs/10_networking_multiplayer.md#D10-S5.4, docs/04_entity_component_model.md#D04-S4.4

**Status:** active

## Summary
Implemented the replication layer: slots 2, 18, 19 and 20, the transport abstraction they sit on,
the bit-packed snapshot format, delta compression with per-peer baselines, the jitter buffer, input
validation and the handshake. Twenty-seven of D04-S4.4's twenty-seven systems now exist.

## Details
The session was chosen by the roadmap: every remaining unimplemented slot was networking, and the
data types for it (`NetworkId`, `InputCommand`, `TransformSample`, `ReplicationClass`,
`NetworkReplicatedComponent`, `PredictionComponent`) had been sitting unused since Phase 0.

What went in, in dependency order: `Transport` and `LoopbackTransport` (D02-R19, so single-player
runs the same replication code rather than a shortcut); `BitWriter`/`BitReader` and `Quantisation`
(D10-R8's bit granularity is a requirement, not an optimisation — 16-bit positions land on a 1.22 cm
lattice over ±400 m); `EntityState`/`SnapshotFrame`/`SnapshotCodec` (absolute values only, which is
what makes G16's idempotence a property of the representation); `NetworkAuthority` and
`NetworkClient` as the two ends; then the four systems, which are thin.

Three decisions the blueprints left open, and one they got wrong:
- D10-R18 requires a NACK the message catalogue does not contain, and D10-S5.4 requires an
  acknowledgement it does not either. Both now exist — the ACK as a field on `InputCommand`, the
  NACK as a message (DEC-058, D10-S4.2 amended).
- Parts are entities and carry replicated health, and D10-S5.10's relevance function does not
  mention them. A part inherits its vehicle's answer (DEC-060).
- A forty-part vehicle would be forty spawn messages. It is one: ids are a contiguous block, walked
  identically on both peers from the slot paths in the assembly file (DEC-059).
- D10-S5.5's replay assumes a per-body physics step, which Bullet does not offer (DEV-017).

The one refactor: slot 7's control arithmetic moved to a shared `VehicleControl` so slot 20 can
replay it, which is the third time this pattern has been needed (DEC-061) and the point at which it
became a stated rule rather than three coincidences.

**Verification.** 31 new tests across three classes, covering ten of D10's test cases and seven
acceptance criteria. `./gradlew check` green on every module that builds here.

## Rationale / Context
What is *not* done matters as much: there is one `Transport` implementation and it is in-process, so
nothing yet crosses a machine, and neither runtime shell builds the endpoints. That is a transport
plus a wiring change rather than a phase of design work, and PROG-023 says so explicitly so the next
session does not re-derive the shape of it.

## Impact
- `game-core` gains `dev.syndicate.core.net` (18 classes) and four systems; `shared-models` gains
  `dev.syndicate.model.net` (7 types).
- D10-S4.2 gains `SnapshotNack` and R4a; D04-S4.4's row 19 gains the client's send.
- DEC-058 to DEC-062, DEV-017, DEV-018, PROG-023.
