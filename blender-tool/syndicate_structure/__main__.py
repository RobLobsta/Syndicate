"""CLI: ``python3 -m syndicate_structure --model art-source/structures/<name> --out assets/``.

One JSON document on stdout and nothing else (D09-R2). Blender writes to the real stdout at the C
level whatever Python does about it (DISC-002), so fd 1 is redirected to stderr for the duration and
the document goes to a private duplicate.

Exit codes are the suite's shared 64-79 block (D09-S4.3); this tool reserves none of its own,
because every way it can fail is a way one of the others can fail too.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from syndicate_policy.exit_codes import (
    EXIT_BLENDER_ERROR,
    EXIT_INPUT_INVALID,
    EXIT_OK,
    EXIT_USAGE,
)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="syndicate-structure", description=__doc__)
    parser.add_argument("--model", required=True, type=Path,
                        help="a directory under art-source/structures/ holding scene.glb")
    parser.add_argument("--out", type=Path, default=None,
                        help="write the structure here, normally assets/structures. Without it the "
                             "run cuts and reports but exports nothing, which is the form to run "
                             "when a threshold changed")
    parser.add_argument("--id", dest="structure_id", default=None,
                        help="the structure's asset id; derived from the directory name if absent")
    parser.add_argument("--name", dest="display_name", default=None, help="the name a player sees")
    parser.add_argument("--band-target", type=float, default=None,
                        help="metres of structure per band, before rounding (default 6)")
    parser.add_argument("--seed", type=int, default=1, help="seed for morphs and any fracture")
    parser.add_argument(
        "--material-table", type=Path, default=Path("assets/materials/materials.json")
    )
    parser.add_argument("--style-table", type=Path, default=Path("assets/materials/style.json"))
    parser.add_argument("--no-style", action="store_true",
                        help="keep the source's own materials instead of the house style")
    parser.add_argument("--report", type=Path, default=None, help="also write the report here")
    parser.add_argument(
        "--strict", action="store_true", help="fail on a finding as well as on a check"
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--" in argv:  # `blender --background --python ... -- --model ...`
        argv = argv[argv.index("--") + 1:]
    try:
        args = parse_args(argv)
    except SystemExit:
        return EXIT_USAGE
    if not args.model.exists():
        print(f"model {args.model} does not exist", file=sys.stderr)
        return EXIT_INPUT_INVALID

    real_stdout = os.dup(1)
    os.dup2(2, 1)  # DISC-002
    report = None
    code = EXIT_OK
    try:
        from .bands import BAND_TARGET_M
        from .structure import Options, StructureError, run

        try:
            report = run(Options(
                model=args.model,
                out=args.out,
                structure_id=args.structure_id,
                display_name=args.display_name,
                seed=args.seed,
                band_target_m=args.band_target or BAND_TARGET_M,
                material_table=args.material_table,
                style_table=args.style_table,
                normalise_style=not args.no_style,
                strict=args.strict,
            ))
        except StructureError as error:
            print(f"syndicate-structure: {error}", file=sys.stderr)
            code = error.code
            report = error.report
    except Exception as exc:
        print(f"structure preparation failed: {exc}", file=sys.stderr)
        import traceback

        traceback.print_exc(file=sys.stderr)
        code = EXIT_BLENDER_ERROR
    finally:
        sys.stdout.flush()
        os.dup2(real_stdout, 1)
        os.close(real_stdout)

    if report is not None:
        document = json.dumps(report, indent=2, sort_keys=True)
        sys.stdout.write(document + "\n")
        sys.stdout.flush()
        if args.report is not None:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(document + "\n")
        if args.strict and report.get("findings"):
            code = code or EXIT_INPUT_INVALID
    return code


if __name__ == "__main__":  # pragma: no cover
    # DISC-003: bpy's teardown segfaults after a run that loaded and freed meshes.
    exit_code = main()
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(exit_code)
