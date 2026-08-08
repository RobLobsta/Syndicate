"""Module entry point (D09-S4.1).

Two supported invocations, both running the same Blender 4.2 code::

    blender --background --factory-startup --python -m syndicate_fracture -- <args>
    python3 -m syndicate_fracture <args>          # with the `bpy` PyPI module

The second exists because no ``blender`` executable is available in every environment this
tool must run in; see DEV-002.

stdout carries exactly one JSON document and nothing else, on success and on failure alike
(D09-R2). Diagnostics go to stderr. An agent reads the exit code first and the document only
when it needs detail.
"""

from __future__ import annotations

import os
import sys

from .cli import TOOL_VERSION, VersionRequested, parse
from .errors import EXIT_BLENDER_ERROR, EXIT_OK, ToolError, claim_stdout, emit_json, log


def main(argv: list[str] | None = None) -> int:
    claim_stdout()
    try:
        args = parse(argv)
    except VersionRequested:
        emit_json({"toolVersion": TOOL_VERSION})
        return EXIT_OK
    except ToolError as error:
        emit_json(error.report(stage="arguments"))
        return error.code

    stage = "startup"
    try:
        from . import pipeline  # imported late so --version and --help work without bpy

        if args.dry_run:
            emit_json(pipeline.plan(args))
            return EXIT_OK

        stage = "pipeline"
        emit_json(pipeline.run(args))
        return EXIT_OK
    except ToolError as error:
        emit_json(error.report(stage=stage))
        log("ERROR", error.message)
        return error.code
    except Exception as error:
        # An escaping traceback would give an agent exit code 1 and no way to decide what to
        # do next, which is exactly what the D09-S4.3 code table exists to prevent.
        emit_json(
            ToolError(
                EXIT_BLENDER_ERROR, f"unhandled {type(error).__name__}: {error}"
            ).report(stage=stage)
        )
        log("ERROR", f"unhandled {type(error).__name__}: {error}")
        return EXIT_BLENDER_ERROR


if __name__ == "__main__":
    code = main()
    sys.stdout.flush()
    sys.stderr.flush()
    # `os._exit` rather than `sys.exit`: tearing down the Blender interpreter after a run
    # that loaded and freed meshes segfaults in the `bpy` module host, which would replace
    # the tool's carefully chosen exit code with 139. Both streams are flushed above, so
    # skipping interpreter shutdown costs nothing the caller can observe.
    os._exit(code)
