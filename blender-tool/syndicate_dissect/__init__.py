"""Vehicle dissection: one whole-car model in, a chassis and four wheels out.

The second Blender tool in the project, and a deliberately separate package from
``syndicate_fracture``. That one has a CLI contract fixed by D09 and a single job — take a
part and break it into shards. This one runs a stage *earlier* in the pipeline of D08-S5.1:
it takes downloaded art that is a whole vehicle and produces the per-part meshes D08-S4.2
assumes already exist (DEV-013).

They share a host and nothing else, so they are separate packages rather than a flag on one.
"""

__all__ = ["dissect"]
