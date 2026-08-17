"""The deform tool's argument schema, and its half of the isolation rule.

The mirror of ``test_cli.py``: each tool refuses the other's transform, and says which tool to
run instead rather than reporting an unknown argument that reads like a typo.
"""

import pytest

from syndicate_deform.cli import (
    DEFAULT_AMPLITUDE,
    DEFAULT_LEVELS,
    DEFAULT_SEED,
    VersionRequested,
    parse,
)
from syndicate_fracture.errors import EXIT_TRANSFORM_NOT_PERMITTED, EXIT_USAGE, ToolError

REQUIRED = ["--input", "in.glb", "--out", "out", "--destruction-class", "SHEET_METAL"]


class TestValidation:
    def test_defaults(self) -> None:
        args = parse(REQUIRED)
        assert args.seed == DEFAULT_SEED == 1337
        assert args.levels == DEFAULT_LEVELS == 4
        assert args.amplitude == pytest.approx(DEFAULT_AMPLITUDE)
        assert args.destruction_class == "SHEET_METAL"
        assert args.subdivide is True

    def test_unknown_argument_is_fatal(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse([*REQUIRED, "--level", "4"])
        assert caught.value.code == EXIT_USAGE

    def test_input_and_out_are_required(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse(["--input", "in.glb", "--destruction-class", "SHEET_METAL"])
        assert caught.value.code == EXIT_USAGE

    @pytest.mark.parametrize("flags", [["--levels", "0"], ["--levels", "5"], ["--amplitude", "0"]])
    def test_out_of_range_values_are_usage_errors(self, flags: list[str]) -> None:
        with pytest.raises(ToolError) as caught:
            parse([*REQUIRED, *flags])
        assert caught.value.code == EXIT_USAGE

    def test_version_short_circuits(self) -> None:
        with pytest.raises(VersionRequested):
            parse(["--version"])

    def test_parameters_block_echoes_the_invocation(self) -> None:
        args = parse([*REQUIRED, "--levels", "2", "--amplitude", "0.03", "--no-subdivide"])
        assert args.parameters_block() == {
            "levels": 2,
            "amplitude": pytest.approx(0.03),
            "subdivide": False,
        }


class TestTransformIsolation:
    """The tool authors DEFORM and nothing else (D00-S6, D15-S5.7, DISC-068)."""

    @pytest.mark.parametrize("destruction_class", ["GLASS", "RIGID", "NONE"])
    def test_a_class_that_does_not_deform_is_refused(self, destruction_class: str) -> None:
        with pytest.raises(ToolError) as caught:
            parse(["--input", "in.glb", "--out", "out",
                   "--destruction-class", destruction_class])
        assert caught.value.code == EXIT_TRANSFORM_NOT_PERMITTED
        assert destruction_class in caught.value.message

    @pytest.mark.parametrize("destruction_class", ["SHEET_METAL", "STRUCTURAL"])
    def test_a_class_that_deforms_is_accepted(self, destruction_class: str) -> None:
        assert parse(["--input", "in.glb", "--out", "out",
                      "--destruction-class", destruction_class]).destruction_class == (
            destruction_class
        )

    def test_the_class_is_required(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse(["--input", "in.glb", "--out", "out"])
        assert caught.value.code == EXIT_USAGE
        assert "--destruction-class" in caught.value.message

    def test_asking_for_shards_names_the_other_tool(self) -> None:
        with pytest.raises(ToolError) as caught:
            parse([*REQUIRED, "--shards", "24"])
        assert caught.value.code == EXIT_USAGE
        assert "syndicate_fracture" in caught.value.message
