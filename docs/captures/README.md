# Verification captures

Frames written by `syndicate-verify` in visual mode (docs/14_test_environment.md#D14-S5.11).

These are not illustrations. Each frame comes from the same `DestructionScene` the headless
checks run — the same Bullet bodies, the same convex hulls, the same seeded scatter — so what
the image shows is what the checks measured. Both assets pass 31/31 checks in the same run
that produced the frame.

| File | Asset | Moment | Command |
|---|---|---|---|
| `cube_explosion.png` | `test_cube_1m`, 12 shards, 7850.0 kg | 12 ticks (0.2 s) after fracture | `--asset build/fixtures-out/test_cube_1m --capture-tick 12 --capture-scatter 1.6` |
| `sphere_explosion.png` | `test_sphere_r0.5`, 16 shards, 4024.4 kg | 10 ticks (0.17 s) after fracture | `--asset build/fixtures-out/test_sphere_r0.5 --capture-tick 10 --capture-scatter 1.8` |
| `cube_100_shards.png` | `test_cube_1m`, **100 shards**, 7850.0 kg | 14 ticks (0.23 s) after fracture | `--asset build/fixtures-out/test_cube_100 --capture-tick 14 --capture-scatter 1.9` |
| `sphere_100_shards.png` | `test_sphere_r0.5`, **100 shards**, 4024.4 kg | 12 ticks (0.2 s) after fracture | `--asset build/fixtures-out/sphere_100 --capture-tick 12 --capture-scatter 2.0` |

The 100-shard assets are produced by passing `--shards 100` to the fracture tool; the cap is
`MAX_SHARDS_PER_PART = 256` (DEC-009). Both still pass 31/31, conserve mass exactly, and
inherit momentum to within float noise — a 100-shard fracture is not a special case of the
pipeline, just a larger one.

Shards are coloured by index on a golden-angle hue ramp, which is the `shardcolor` overlay of
D14-S5.11 — neighbouring shards never share a colour, at any shard count.

To reproduce from a clean tree:

```
./gradlew :blender-tool:processFixtures        # fracture the fixture meshes (D14-S7.3 step 1)
./gradlew :test-environment:verifyFixtures     # 31/31 checks per asset (D14-S7.3 step 2)
./gradlew :test-environment:installDist
xvfb-run -a -s "-screen 0 1280x720x24" \
  test-environment/build/install/syndicate-verify/bin/syndicate-verify \
  --asset build/fixtures-out/test_cube_1m --capture out.png --capture-tick 12 --capture-scatter 1.6
```

`xvfb-run` is only needed where there is no display; the harness's headless mode never creates
a GL context at all (D14-S5.13, G17).
