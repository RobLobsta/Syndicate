"""Loading, correcting and exporting a downloaded vehicle model, for the tools that need it.

This began as the second Blender tool in the project (DEC-042): one whole-car model in, a
chassis and four wheels out. That output is retired — a five-part car cannot have a door shot
off it, and every vehicle now comes out of :mod:`syndicate_prepare` as twenty-odd real parts
under the vehicle that owns them.

What survives is the half that was never about five parts: :mod:`~syndicate_dissect.dissect`
loads a model, drops foreign roots, bakes armatures and applies ``import.json``, and
:mod:`~syndicate_dissect.emit` joins objects, places origins, builds collision hulls and writes
``mesh.glb``. Both are used by ``syndicate_prepare`` on every run, and the classification code
beside them is still the only implementation of the corner model that finds a wheel.

There is no longer a CLI here. The entry point is ``python3 -m syndicate_prepare``.
"""

__all__ = ["dissect"]
