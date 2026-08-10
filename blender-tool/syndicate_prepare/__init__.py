"""Turn one downloaded vehicle model into labelled, game-ready parts.

``docs/09_blender_destruction_tool.md`` says what to do with **one part** once you have it.
This package is how you *get* the parts, and the two problems have opposite characters
(D15-S1): fracturing a part is a well-posed geometry problem with a right answer, and
segmenting a car is an inference problem where the information needed to name a door is not
reliably present in the file.

The design principle the whole package is arranged around:

    Infer what geometry can prove. Read what the file happens to say. Ask a human for the
    rest, once per material rather than once per part.

Stage order is D15-S5.1's and is not interchangeable — see :mod:`syndicate_prepare.prepare`.
"""

from __future__ import annotations

__all__ = [
    "EXIT_BLENDER_ERROR",
    "EXIT_INPUT_MISSING",
    "EXIT_OK",
    "EXIT_UNDER_LABELLED",
    "EXIT_USAGE",
]

#: Success. Follows the fracture tool's exit-code contract (D09-S4.3).
EXIT_OK = 0

#: Bad arguments.
EXIT_USAGE = 64

#: The model directory or a file it needs is missing.
EXIT_INPUT_MISSING = 66

#: A Blender-side failure.
EXIT_BLENDER_ERROR = 70

#: Strict mode only: the ensemble could not name enough of the model (D15-R13).
#:
#: A distinct code because it is not an error in the tool — it is the tool reporting, loudly,
#: that this model needs a ``parts.json``. An operator scripting the pipeline wants to tell
#: that apart from a crash.
EXIT_UNDER_LABELLED = 65
