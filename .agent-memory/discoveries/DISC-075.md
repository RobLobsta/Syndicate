# DISC-075: a Blender 5.0 .blend is "not a blend file" to Blender 4.2

**Date:** 2026-08-18
**Category:** discoveries
**Related Docs:** docs/09_blender_destruction_tool.md#D09-S4.1, docs/02_technical_architecture.md#D02-S5.5, docs/08_asset_pipeline.md#D08-S4.1

**Status:** active

## Summary
`city_alley_kit.blend`, written by Blender 5.0, is rejected outright by the pinned Blender 4.2 with
`Error: Failed to read blend file ..., not a blend file`. The file is fine. Blender 5.0 changed the
`.blend` header format, and 4.2 does not recognise the new one — it reports a **corrupt file**,
which is the wrong diagnosis and the reason this cost an hour rather than a minute.

## Details
The file on disk is Zstandard-compressed (which 4.2 handles: it has read zstd `.blend` files since
3.0). Decompressing it by hand shows the header:

```
$ python3 -c "import zstandard; print(zstandard.ZstdDecompressor().stream_reader(open(f,'rb')).read()[:16])"
b'BLENDER17-01v050'
```

The classic header is `BLENDER` + a pointer-size character + an endianness character + three version
digits — `BLENDER-v302`. Blender 5.0's is longer and 4.2's sniffer does not match it, so the file
falls through to "not a blend file" rather than to "written by a newer version". A **4.4** file, by
contrast, opens with the familiar `WARNING File written by newer Blender binary (404.32), expect
loss of data!` — which is what `turret.blend` did, and what made the alley kit's failure look like a
different class of problem.

The workaround is a second Blender, used for one job:

```bash
curl -fsSL -o /tmp/b50.tar.xz https://download.blender.org/release/Blender5.0/blender-5.0.1-linux-x64.tar.xz
mkdir -p /opt/blender50 && tar -xf /tmp/b50.tar.xz -C /opt/blender50 --strip-components=1
/opt/blender50/blender --background file.blend --python export.py --python-exit-code 1 -- out.glb
```

Export to glTF there, and run the pinned 4.2 pipeline on the `.glb`. Do **not** be tempted to move
the whole toolchain to 5.0: D09 and D02-S5.5 pin 4.2 LTS, four packages are calibrated against its
operators, and the reason to fetch 5.0 is that it can open one file.

## Rationale / Context
This is worth an entry because the error message actively misleads. "Not a blend file" reads as a
corrupt upload, and the natural next step is to ask for the file again — which produces the same
bytes and the same message. Nothing about the message says "version", and the version is the whole
problem.

## Impact
- Any `.blend` arriving from a modern Blender needs converting before this project can read it, and
  `art-source/structures/README.md` records that as the reason the two uploaded `.blend` files were
  extracted to `scene.glb` and then removed.
- `tools/install-blender.sh` still installs 4.2 and should keep doing so. The 5.0 fetch is a manual,
  one-off step for a conversion, not part of the toolchain.
