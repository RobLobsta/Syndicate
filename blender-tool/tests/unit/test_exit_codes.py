"""The exit codes of D09-S4.3 must not drift, because CI asserts specific values.

D12-AC-19 requires the tool's negative tests to assert a specific code rather than merely
non-zero, so a change here silently reclassifies a failure in CI.
"""

import syndicate_fracture as sf


def test_exit_codes_match_blueprint() -> None:
    # T-D12-19 (docs/12_testing_validation_ci.md#D12-S6)
    assert sf.EXIT_OK == 0
    assert sf.EXIT_USAGE == 64
    assert sf.EXIT_INPUT_INVALID == 66
    assert sf.EXIT_MATERIAL_UNKNOWN == 67
    assert sf.EXIT_BLENDER_NOT_FOUND == 70


def test_exit_codes_are_distinct() -> None:
    codes = [
        sf.EXIT_OK,
        sf.EXIT_USAGE,
        sf.EXIT_INPUT_INVALID,
        sf.EXIT_MATERIAL_UNKNOWN,
        sf.EXIT_BLENDER_NOT_FOUND,
    ]
    assert len(set(codes)) == len(codes)
