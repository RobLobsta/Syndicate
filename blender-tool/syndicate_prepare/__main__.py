"""CLI: ``python3 -m syndicate_prepare --model <dir> --vehicle <name> [--strict]``.

One JSON document on stdout and nothing else, for the same reason the fracture tool does it
(D09-R2): an agent runs this and parses the result, and a stray progress line in the middle of
the document is a parse error rather than a warning. Blender writes to the real stdout at the C
level whatever Python does about it (DISC-002), so fd 1 is redirected to stderr for the
duration and the document is written to a private duplicate.

Exit codes follow the fracture tool's (D09-S4.3), plus one of this tool's own: 65 means the
model could not be labelled well enough in strict mode (D15-R13), which is a report about the
*model* rather than a failure of the tool, and an operator scripting the pipeline wants to tell
those apart.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from . import EXIT_BLENDER_ERROR, EXIT_INPUT_MISSING, EXIT_OK, EXIT_UNDER_LABELLED, EXIT_USAGE


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="syndicate-prepare", description=__doc__)
    parser.add_argument("--model", required=True, type=Path, help="art-source/vehicles/<name>")
    parser.add_argument("--vehicle", required=True, help="short vehicle name, e.g. eclipse")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="exit 65 when the labelled triangle fraction is below the D15-R13 minimum",
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=None,
        help="also write the report here, so a run that is being read by a human keeps a copy",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="write the parts here (assets/parts). Without it the run classifies and reports "
        "but exports nothing, which is the form to run when a threshold changed",
    )
    parser.add_argument(
        "--vehicles",
        type=Path,
        default=None,
        help="write the assembly here (assets/vehicles)",
    )
    parser.add_argument("--mass", type=float, default=None,
                        help="the vehicle's kerb mass in kg; inferred from its footprint if absent")
    parser.add_argument("--display-name", default=None, help="the vehicle's in-game name")
    parser.add_argument("--seed", type=int, default=1,
                        help="seed for the damage morphs and the glass fracture (D09-R30)")
    parser.add_argument("--material-table", type=Path,
                        default=Path("assets/materials/materials.json"))
    parser.add_argument("--balance-table", type=Path, default=Path("assets/balance/classes.json"))
    parser.add_argument(
        "--no-write-import",
        action="store_true",
        help="do not record the derived correction in the model's import.json (DEC-036)",
    )
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
        from .prepare import Options, run

        report = run(
            Options(
                model_dir=args.model,
                vehicle=args.vehicle,
                strict=args.strict,
                seed=args.seed,
                mass_kg=args.mass,
                display_name=args.display_name,
                out=args.out,
                vehicles_out=args.vehicles,
                material_table=args.material_table,
                balance_table=args.balance_table,
                write_import=not args.no_write_import,
            )
        )
    except Exception as exc:
        # Broad on purpose: the exit code is this tool's contract (D09-S4.3), and a Blender
        # operator can raise anything at all. A traceback goes to stderr so the failure is
        # diagnosable; stdout stays a valid document or stays empty.
        print(f"preparation failed: {exc}", file=sys.stderr)
        import traceback

        traceback.print_exc(file=sys.stderr)
        return EXIT_BLENDER_ERROR
    finally:
        sys.stdout.flush()
        os.dup2(real_stdout, 1)
        os.close(real_stdout)

    document = json.dumps(report, indent=2, sort_keys=True)
    sys.stdout.write(document + "\n")
    sys.stdout.flush()
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(document + "\n")

    if args.strict and not report.get("confidence", {}).get("meets", False):
        return EXIT_UNDER_LABELLED
    return EXIT_OK


if __name__ == "__main__":  # pragma: no cover
    # DISC-003: bpy's module teardown segfaults after a run that loaded and freed meshes,
    # which would overwrite whatever this decided. Exit before the interpreter can.
    code = main()
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(code)
