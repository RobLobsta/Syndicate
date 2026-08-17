"""The argument schema of D09-S4.2.

D09-R4 makes an unknown argument fatal. A tool whose caller is an agent cannot silently
ignore a misspelled flag: the run would succeed with default parameters and ship the wrong
asset, and nothing in the output would say so.
"""

import pytest

from syndicate_fracture.cli import (
    DEFAULT_SEED,
    DEFAULT_SHARDS,
    MAX_SHARDS_PER_PART,
    VersionRequested,
    parse,
    split_blender_args,
)
from syndicate_fracture.errors import EXIT_TRANSFORM_NOT_PERMITTED, EXIT_USAGE, ToolError

#: Every invocation needs a destruction class now: the tool refuses to fracture a class
#: D15-S5.7 gives no shards to, and cannot refuse what it was never told (DISC-068).
REQUIRED = ["--input", "in.glb", "--out", "out", "--destruction-class", "GLASS"]


class TestSeparator:
    def test_takes_everything_after_the_double_dash(self) -> None:
        # Blender's own arguments precede the separator (D09-R1).
        argv = ["--background", "--python-expr", "x", "--", "--input", "a.glb"]
        assert split_blender_args(argv) == ["--input", "a.glb"]

    def test_without_a_separator_the_whole_list_is_ours(self) -> None:
        # The `bpy`-as-module invocation has no Blender arguments to skip (DEV-002).
        assert split_blender_args(["--input", "a.glb"]) == ["--input", "a.glb"]


class TestValidation:
    def test_defaults_match_the_blueprint_table(self) -> None:
        args = parse(REQUIRED)
        assert args.seed == DEFAULT_SEED == 1337
        assert args.shards == DEFAULT_SHARDS == 24
        assert args.shard_mode == "uniform"
        assert args.destruction_class == "GLASS"
        assert args.hull_max_verts == 32
        assert args.part_hull_max_verts == 64
        assert args.mass_tolerance == pytest.approx(0.02)

    def test_unknown_argument_is_fatal(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse([*REQUIRED, "--shard", "24"])
        assert caught.value.code == EXIT_USAGE
        assert "--shard" in caught.value.message

    def test_input_and_out_are_required(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse(["--input", "in.glb"])
        assert caught.value.code == EXIT_USAGE

    def test_shard_count_is_clamped_to_the_budget(self) -> None:
        assert parse([*REQUIRED, "--shards", "500"]).shards == MAX_SHARDS_PER_PART
        assert parse([*REQUIRED, "--shards", "1"]).shards == 2

    def test_impact_mode_requires_an_impact_point(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse([*REQUIRED, "--shard-mode", "impact_biased"])
        assert caught.value.code == EXIT_USAGE

    def test_impact_point_parses_as_a_vec3(self) -> None:
        args = parse([*REQUIRED, "--shard-mode", "impact_biased", "--impact-point", "1,-2,0.5"])
        assert args.impact_point == (1.0, -2.0, 0.5)

    @pytest.mark.parametrize("value", ["1,2", "a,b,c", "1,2,3,4"])
    def test_malformed_impact_point_is_a_usage_error(self, value: str) -> None:
        with pytest.raises(ToolError) as caught:
            parse([*REQUIRED, "--shard-mode", "impact_biased", "--impact-point", value])
        assert caught.value.code == EXIT_USAGE

    def test_contradictory_flags_are_rejected(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse([*REQUIRED, "--no-export", "--verify-only"])
        assert caught.value.code == EXIT_USAGE

    @pytest.mark.parametrize(
        "flags",
        [
            ["--hull-max-verts", "3"],
            ["--mass-tolerance", "0"],
        ],
    )
    def test_out_of_range_values_are_usage_errors(self, flags: list[str]) -> None:
        with pytest.raises(ToolError) as caught:
            parse([*REQUIRED, *flags])
        assert caught.value.code == EXIT_USAGE

    def test_version_short_circuits(self) -> None:
        with pytest.raises(VersionRequested):
            parse(["--version"])

    def test_parameters_block_echoes_the_invocation(self) -> None:
        # D09-R8: a run must be reproducible from the manifest alone.
        args = parse([*REQUIRED, "--seed", "99", "--shards", "8", "--shard-mode", "surface_biased"])
        block = args.parameters_block()
        assert block == {
            "shards": 8,
            "shardMode": "surface_biased",
            "hullMaxVerts": 32,
            "minShardVolumeM3": pytest.approx(1e-6),
            "shellThicknessM": pytest.approx(0.0),
        }


class TestTransformIsolation:
    """The tool authors FRACTURE and nothing else (D00-S6, D15-S5.7, DISC-068)."""

    def test_a_class_that_does_not_fracture_is_refused(self) -> None:
        # Not a usage error: the invocation is well formed and the content decision behind it
        # is what is wrong, so an agent is sent to the label rather than to the flags.
        for destruction_class in ("SHEET_METAL", "STRUCTURAL", "RIGID", "NONE"):
            with pytest.raises(ToolError) as caught:
                parse(["--input", "in.glb", "--out", "out",
                       "--destruction-class", destruction_class])
            assert caught.value.code == EXIT_TRANSFORM_NOT_PERMITTED
            assert destruction_class in caught.value.message

    def test_the_class_is_required(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse(["--input", "in.glb", "--out", "out"])
        assert caught.value.code == EXIT_USAGE
        assert "--destruction-class" in caught.value.message

    def test_an_unknown_class_is_a_usage_error(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse(["--input", "in.glb", "--out", "out", "--destruction-class", "CHEESE"])
        assert caught.value.code == EXIT_USAGE

    def test_the_class_is_case_insensitive(self) -> None:
        assert parse(["--input", "in.glb", "--out", "out",
                      "--destruction-class", "glass"]).destruction_class == "GLASS"

    @pytest.mark.parametrize(
        "flags", [["--damage-morphs", "4"], ["--morph-amplitude", "0.06"]]
    )
    def test_the_deform_flags_name_the_other_tool(self, flags: list[str]) -> None:
        # Dropping them silently would leave every old invocation looking like it still worked
        # while quietly authoring one transform instead of two — the same failure in the other
        # direction. They are still parsed, and saying where they went is their whole job.
        with pytest.raises(ToolError) as caught:
            parse([*REQUIRED, *flags])
        assert caught.value.code == EXIT_USAGE
        assert "syndicate_deform" in caught.value.message
