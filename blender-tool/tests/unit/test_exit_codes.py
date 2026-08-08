"""The exit codes of D09-S4.3 must not drift, because CI asserts specific values.

D12-AC-19 requires the tool's negative tests to assert a specific code rather than merely
non-zero, so a change here silently reclassifies a failure in CI.
"""

import syndicate_fracture as sf
from syndicate_fracture.errors import (
    EXIT_NAMES,
    EXIT_VERIFICATION_FAILED,
    ToolError,
    worst_exit_code,
)


def test_exit_codes_match_blueprint() -> None:
    # T-D12-19 (docs/12_testing_validation_ci.md#D12-S6)
    assert sf.EXIT_OK == 0
    assert sf.EXIT_USAGE == 64
    assert sf.EXIT_INPUT_INVALID == 65
    assert sf.EXIT_INPUT_GEOMETRY_INVALID == 66
    assert sf.EXIT_MATERIAL_UNRESOLVED == 67
    assert sf.EXIT_FRACTURE_FAILED == 68
    assert sf.EXIT_SHAPEKEY_FAILED == 69
    assert sf.EXIT_BLENDER_ERROR == 70
    assert sf.EXIT_HULL_FAILED == 71
    assert sf.EXIT_MASS_IMPLAUSIBLE == 72
    assert sf.EXIT_VERIFICATION_FAILED == 73
    assert sf.EXIT_EXPORT_FAILED == 74
    assert sf.EXIT_OUTPUT_WRITE_FAILED == 75
    assert sf.EXIT_DETERMINISM_VIOLATION == 76


def test_legacy_aliases_still_resolve() -> None:
    # The skeleton named two codes differently; both names must keep working so an
    # existing invocation does not silently branch on a missing attribute.
    assert sf.EXIT_MATERIAL_UNKNOWN == sf.EXIT_MATERIAL_UNRESOLVED
    assert sf.EXIT_BLENDER_NOT_FOUND == sf.EXIT_BLENDER_ERROR


def test_exit_codes_are_distinct() -> None:
    codes = list(EXIT_NAMES)
    assert len(set(codes)) == len(codes)


def test_specific_code_beats_general_verification_failure() -> None:
    # D09-R6: a specific code always wins, so an agent can branch without parsing.
    assert worst_exit_code([EXIT_VERIFICATION_FAILED, sf.EXIT_HULL_FAILED]) == sf.EXIT_HULL_FAILED
    assert worst_exit_code([EXIT_VERIFICATION_FAILED]) == EXIT_VERIFICATION_FAILED
    assert worst_exit_code([]) == sf.EXIT_OK


def test_earliest_stage_wins_among_specific_codes() -> None:
    # A bad fracture causes bad masses; reporting the mass failure would send the caller
    # to the wrong stage.
    codes = [sf.EXIT_MASS_IMPLAUSIBLE, sf.EXIT_FRACTURE_FAILED]
    assert worst_exit_code(codes) == sf.EXIT_FRACTURE_FAILED


def test_tool_error_reports_machine_readable_payload() -> None:
    error = ToolError(sf.EXIT_HULL_FAILED, "hull too big", source="shard_000", vertexCount=99)
    report = error.report(stage="pipeline")
    assert report["ok"] is False
    assert report["exitCode"] == sf.EXIT_HULL_FAILED
    assert report["exitName"] == "HULL_FAILED"
    assert report["stage"] == "pipeline"
    assert report["details"]["source"] == "shard_000"
