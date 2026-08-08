"""Session teardown for the in-Blender suite.

Blender's data-block teardown at interpreter exit segfaults in the `bpy` module host once a
session has loaded and freed meshes — DISC-003, the same reason the tool's entry point calls
``os._exit``. Under pytest that turns a green run into exit 139, which Gradle reports as a
failed task even though every test passed.

So the session ends by hard-exiting with pytest's own status, after flushing. Nothing is
lost: pytest has already written its report and returned the status by this point.
"""

from __future__ import annotations

import os
import sys


def pytest_sessionfinish(session, exitstatus):
    del session
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(int(exitstatus))
