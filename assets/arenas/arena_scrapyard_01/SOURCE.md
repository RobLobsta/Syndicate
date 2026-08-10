# arena_scrapyard_01 — Scrapyard

The first arena, and the one `D03-S4.2` names as the default a server loads when nobody says
otherwise. It is deliberately the least interesting place that is still a place: a flat 300 × 300 m
floor, a wall on each side, a kill plane 40 m below, and six spawn points. No scrap yet — the id and
the display name are the blueprint's, and what is in it is a decision nobody has made.

## Why it is empty

Everything the game has built so far — suspension, braking, degradation, collision damage, weapons —
has been simulated against a test fixture's ground box. This arena is that ground box, promoted to
content, so the same figures hold when a real process loads a real world. Cover, hazards and shape are
design decisions (`ROADMAP.md` §4) that should be made after somebody has driven in it, not before.

## Numbers, and where they come from

| Field | Value | Why |
|---|---|---|
| bounds | ±150 m on X and Z | Roughly 4 seconds at the Stampede's top speed from centre to wall — long enough for a chase, short enough that two vehicles find each other |
| `groundY` | 0.0 | The height every existing test fixture's ground sits at, so calibration figures carry over unchanged |
| `killPlaneY` | −40.0 m | 10 m below `boundsMin.y`, which clears the A405 warning and is far enough that a vehicle bounced off the floor cannot reach it |
| `clearanceRadiusM` | 12.0 m | Above `MIN_SPAWN_SEPARATION_M` (8 m) with room for the 4.9 m Stampede plus its debris |
| spawn spacing | 20 m between a team's two points, ~170 m between the two teams | Two vehicles from the same team never overlap; the two teams start out of weapon range of everything but the 300 m cannon |

## Collision

There is no `collision.glb`. The floor and the four walls are generated from `boundsMin`/`boundsMax`
by `ArenaFactory`, which is recorded as `DEV-014`: an arena mesh needs a concave triangle-mesh
collision shape, and nothing in the project owns one yet. Everything in this file that the simulation
reads — the floor height, the walls, the kill plane, the spawn points — is exactly what is authored
here, so replacing the generated boxes with a mesh later changes the shape of the arena and not its
contract.

## Modes

Declared for `DEATHMATCH`, `TEAM_DEATHMATCH`, `LAST_MACHINE` and `TIME_TRIAL` — every mode the
`GameMode` enum currently has except one. Not `PAYLOAD`: that mode needs a `payloadPath`, and an
arena with no cover would make escorting one pointless.
