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

# The shared table of `syndicate_policy.exit_codes`, so 64-79 mean the same thing in every tool
# of the suite (D09-R5 invites an agent to branch on the code by integer division, which only
# works if they agree). Two numbers moved when the table was unified — see below.
from syndicate_policy.exit_codes import (
    EXIT_BLENDER_ERROR,
    EXIT_OK,
    EXIT_UNDER_LABELLED,
    EXIT_USAGE,
)

#: The model directory or a file it needs is missing.
#:
#: Was 66, which D09-S4.3 spends on "mesh not watertight, zero volume, NaN coordinates" — an
#: agent branching on the shared meaning would have gone looking for broken geometry in a model
#: it had not managed to open. 65 is D09's "input file unreadable", which is what this is.
EXIT_INPUT_MISSING = 65
