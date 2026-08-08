"""Module entry point: ``blender --background --python -m syndicate_fracture -- <args>``.

The invocation form is fixed by D02-S5.2 step 2 and D09-S4.1.
"""

import sys

from syndicate_fracture import EXIT_USAGE


def main(argv: list[str] | None = None) -> int:
    """Parse arguments and run the tool.

    Not implemented yet; the CLI contract is D09-S4.1 and the argument schema D09-S4.2.
    Returning a usage code rather than crashing keeps CI's negative tests (D12-S5.3)
    meaningful once the real parser lands.
    """
    del argv
    print(
        "syndicate_fracture is not implemented yet "
        "(docs/09_blender_destruction_tool.md#D09-S4.1)",
        file=sys.stderr,
    )
    return EXIT_USAGE


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
