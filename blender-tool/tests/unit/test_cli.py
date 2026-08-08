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
from syndicate_fracture.errors import EXIT_USAGE, ToolError

REQUIRED = ["--input", "in.glb", "--out", "out"]


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
        assert args.damage_morphs == 4
        assert args.morph_amplitude == pytest.approx(0.06)
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
            ["--damage-morphs", "5"],
            ["--damage-morphs", "-1"],
            ["--morph-amplitude", "0"],
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
            "damageMorphs": 4,
            "morphAmplitude": pytest.approx(0.06),
            "hullMaxVerts": 32,
            "minShardVolumeM3": pytest.approx(1e-6),
        }
