"""CLI: ``python3 -m syndicate_weapon --model <file.glb> --out <dir>``.

One JSON document on stdout and nothing else (D17-R17, D09-R2): an agent runs this and parses the
result, and a stray progress line in the middle of the document is a parse error rather than a
warning. Blender writes to the real stdout at the C level whatever Python does about it (DISC-002),
so fd 1 is redirected to stderr for the duration and the document goes to a private duplicate.

Exit codes are D17-R19's, which extend D09-S4.3's scheme in the reserved 80-89 range.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from . import EXIT_BLENDER_ERROR, EXIT_INPUT_MISSING, EXIT_OK, EXIT_USAGE


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="syndicate-weapon", description=__doc__)
    parser.add_argument("--model", required=True, type=Path, help="a .glb or .gltf weapon model")
    parser.add_argument("--out", type=Path, default=None,
                        help="write the sub-parts here, normally assets/parts/<weaponId>. Without "
                             "it the run classifies and reports but exports nothing, which is the "
                             "form to run when a threshold changed")
    parser.add_argument("--id", dest="weapon_id", default=None,
                        help="the weapon's asset id; derived from the filename if absent")
    parser.add_argument("--family", default=None,
                        choices=["AUTOCANNON", "CANNON", "SHOTGUN", "ROCKET", "MORTAR", "FLAMER",
                                 "LASER"],
                        help="override the derived family. ROCKET, MORTAR and FLAMER are reachable "
                             "only this way, because nothing in a static mesh distinguishes them "
                             "(D17-R50)")
    parser.add_argument("--size", dest="size_class", default=None,
                        choices=["LIGHT", "MEDIUM", "HEAVY"],
                        help="override the derived size class (D17-S4.3)")
    parser.add_argument("--target-length", type=float, default=None,
                        help="metres along the bore to scale to, overriding the size class target")
    parser.add_argument("--seed", type=int, default=1,
                        help="seed for the damage morphs and any fracture (D17-R64)")
    parser.add_argument("--style-table", type=Path, default=Path("assets/materials/style.json"),
                        help="the house style every source material is normalised into (D15-S9)")
    parser.add_argument("--no-style", action="store_true",
                        help="skip style normalisation and keep the source's own materials")
    parser.add_argument("--material-table", type=Path,
                        default=Path("assets/materials/materials.json"))
    parser.add_argument("--report", type=Path, default=None,
                        help="also write the report here, so a run read by a human keeps a copy")
    parser.add_argument("--strict", action="store_true",
                        help="fail on a weakly held family classification as well as on a check")
    parser.add_argument("--verbose", action="store_true", help="diagnostics on stderr")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--" in argv:  # `blender --background --python ... -- --model ...`
        argv = argv[argv.index("--") + 1:]
    try:
        args = parse_args(argv)
    except SystemExit:
        return EXIT_USAGE
    if not args.model.is_file():
        print(f"model {args.model} does not exist", file=sys.stderr)
        return EXIT_INPUT_MISSING

    real_stdout = os.dup(1)
    os.dup2(2, 1)  # DISC-002: keep Blender's C-level chatter out of the document
    report = None
    code = EXIT_OK
    try:
        from .weapon import Options, WeaponError, run

        try:
            report = run(Options(
                model=args.model,
                out=args.out,
                weapon_id=args.weapon_id,
                family=args.family,
                size_class=args.size_class,
                target_length_m=args.target_length,
                seed=args.seed,
                style_table=args.style_table,
                normalise_style=not args.no_style,
                strict=args.strict,
                material_table=args.material_table,
            ))
        except WeaponError as error:
            # A WeaponError carries its own code because the code *is* the contract (D17-R19): an
            # agent scripting this wants "the seams did not close" and "Blender fell over" to be
            # different answers, not both 70. It also carries whatever report it got as far as, and
            # that is written to stdout as usual — a failed run that says nothing about what it
            # decided is a run that has to be repeated under a debugger.
            print(f"syndicate-weapon: {error}", file=sys.stderr)
            code = error.code
            report = error.report
    except Exception as exc:
        print(f"weapon preparation failed: {exc}", file=sys.stderr)
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
    return code


if __name__ == "__main__":  # pragma: no cover
    # DISC-003: bpy's teardown segfaults after a run that loaded and freed meshes, which would
    # overwrite whatever this decided. Exit before the interpreter can.
    exit_code = main()
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(exit_code)
