# arena_desert_01 — Dune Sea

Generated ground, not authored geometry (`docs/16_procedural_arena_generation.md#D16-S5.1`).

Nothing in this directory is art. The whole arena is a seed and thirteen numbers: the height of
every square metre, what that metre is made of, and whether a vehicle can be on it are all computed
at load and identically on every peer, which is why the terrain never crosses the network (DEC-069).

| | |
|---|---|
| Span | 600 x 600 m, one height sample per metre |
| Seed | 20260814 |
| Relief | about -5 m to +51 m above `groundY`, the top of which is the border rim |
| Drivable | 73% of the arena |
| Surfaces | 66% sand, 20% rock, 14% gravel |
| Dune slip faces | 32.7 deg mean, at the angle of repose by construction |

**There are no walls.** The border rises into dunes over the outer 60 m, which stand well past what
a vehicle can climb. It is a soft boundary on purpose (D16-R39) — a car at speed gets part way up
and slides back, rather than stopping dead against something invisible. `killPlaneY` is the hard
backstop for anything that leaves anyway.

Each spawn point levels a pad of its own `clearanceRadiusM`, with a ramp out of it wide enough to be
drivable (D16-S9 E2). Without that, whether a given spawn lands on a climbable face is a property of
the seed, and would be found by playing rather than by loading.

**This arena is not yet the default.** `arena_scrapyard_01` still is, because the renderer draws the
flat arena's floor and walls and knows nothing about a height field yet — stage 2 of the four in
`ROADMAP.md` §3. Load this one with `--arena arena_desert_01` to drive on it.

No roads and no structures: those are stages 3 and 4. A `roads` block added to this file is all the
highway will need.
