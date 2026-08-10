"""CLI: ``python3 -m syndicate_dissect --model <dir> --vehicle <name> --out <dir>``.

Reports one JSON document on stdout and nothing else, for the same reason the fracture tool
does (D09-R2): an agent runs this and parses the result, and a stray progress line in the
middle of the document is a parse error rather than a warning. Blender writes to the real
stdout at the C level whatever Python does about it (DISC-002), so fd 1 is redirected to
stderr for the duration and the document is written to a private duplicate.

Exit codes follow the fracture tool's (D09-S4.3): 0 success, 64 bad arguments, 66 input
missing, 70 a Blender-side failure.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

EXIT_OK = 0
EXIT_USAGE = 64
EXIT_INPUT_MISSING = 66
EXIT_BLENDER_ERROR = 70

#: Which corner supplies the canonical mesh for each axle's shared part type.
#: The shipped assemblies give both front wheels one part type and both rear wheels another
#: (`wheel_eclipse_front_01`), so only one side is exported and the other is the same part
#: in a mirrored slot. Taking the right-hand wheel is arbitrary and fixed so the choice
#: cannot drift between runs.
CANONICAL_CORNER = {"front": "fr", "rear": "rr"}


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="syndicate-dissect", description=__doc__)
    parser.add_argument("--model", required=True, type=Path, help="art-source/vehicles/<name>")
    parser.add_argument("--vehicle", required=True, help="short vehicle name, e.g. eclipse")
    parser.add_argument("--out", type=Path, default=Path("assets/parts"), help="asset parts root")
    parser.add_argument("--dry-run", action="store_true", help="classify and report; write nothing")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--" in argv:  # `blender --background --python ... -- --model ...`
        argv = argv[argv.index("--") + 1 :]
    try:
        args = parse_args(argv)
    except SystemExit:
        return EXIT_USAGE
    if not args.model.is_dir():
        print(f"model directory {args.model} does not exist", file=sys.stderr)
        return EXIT_INPUT_MISSING

    real_stdout = os.dup(1)
    os.dup2(2, 1)  # DISC-002: keep Blender's C-level chatter out of the document
    try:
        report = run(args)
    except Exception as exc:
        # Broad on purpose: the exit code is this tool's contract (D09-S4.3), and a Blender
        # operator can raise anything at all. A traceback goes to stderr so the failure is
        # diagnosable; stdout stays a valid document or stays empty.
        print(f"dissection failed: {exc}", file=sys.stderr)
        import traceback

        traceback.print_exc(file=sys.stderr)
        return EXIT_BLENDER_ERROR
    finally:
        sys.stdout.flush()
        os.dup2(real_stdout, 1)
        os.close(real_stdout)

    json.dump(report, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    sys.stdout.flush()
    return EXIT_OK if report["ok"] else EXIT_BLENDER_ERROR


def run(args: argparse.Namespace) -> dict:
    from . import dissect, emit

    dissect.load_model(args.model)
    islands = dissect.collect_islands()
    groups, chassis = dissect.classify(islands)

    report = {
        "vehicle": args.vehicle,
        "model": str(args.model),
        "toolVersion": _tool_version(),
        "islands": len(islands),
        "wheels": {},
        "chassis": {},
        "ok": False,
        "warnings": [],
    }
    for corner in ("fl", "fr", "rl", "rr"):
        group = groups.get(corner)
        if group is None:
            report["warnings"].append(f"no wheel found at corner {corner}")
            continue
        centre = group.centre()
        report["wheels"][corner] = {
            "islands": len(group.islands),
            "triangles": sum(i.triangles for i in group.islands),
            "centreM": _vec(centre),
            "diameterM": round(group.radius() * 2, 4),
            "widthM": round(group.width(), 4),
        }
    report["chassis"] = {
        "islands": len(chassis),
        "triangles": sum(i.triangles for i in chassis),
    }
    if len(report["wheels"]) != 4:
        report["warnings"].append(f"expected 4 wheels, classified {len(report['wheels'])}")
    if args.dry_run:
        report["ok"] = len(report["wheels"]) == 4
        return report

    written = {}
    for axle, corner in CANONICAL_CORNER.items():
        group = groups.get(corner)
        if group is None:
            continue
        part_id = f"wheel_{args.vehicle}_{axle}_01"
        obj = emit.join(group.islands, part_id)
        centre = group.centre()
        # Blender-space axle position: game (x, y, z) is blender (x, -z, y).
        emit.recentre_on(obj, (centre.x, -centre.z, centre.y))
        collision = emit.build_collision_hull(obj, f"{part_id}_col")
        written[part_id] = str(emit.export_part(obj, collision, args.out / part_id))

    chassis_id = f"chassis_{args.vehicle}_01"
    obj = emit.join(chassis, chassis_id)
    if obj is not None:
        # The chassis origin is the centreline at ground level: the space D08-S4.2's slot
        # `localPosition` values are authored in, and the space the wheel centres above are
        # reported in, so the two line up without a second frame to reconcile.
        emit.recentre_on(obj, (0.0, 0.0, 0.0))
        collision = emit.build_collision_hull(obj, f"{chassis_id}_col")
        written[chassis_id] = str(emit.export_part(obj, collision, args.out / chassis_id))

    report["written"] = written
    report["ok"] = len(report["wheels"]) == 4 and len(written) == 3
    return report


def _vec(v) -> dict:
    return {"x": round(v.x, 4), "y": round(v.y, 4), "z": round(v.z, 4)}


def _tool_version() -> str:
    try:
        import bpy

        return f"blender {bpy.app.version_string}"
    except ImportError:  # pragma: no cover
        return "unknown"


if __name__ == "__main__":
    code = main()
    # DISC-003: bpy's interpreter teardown segfaults after a run that loaded and freed
    # meshes, and the process then exits 139 whatever this tool decided. Flush and leave
    # without running it, so the exit code above is the one the caller sees.
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(code)
