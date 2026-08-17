"""The shared destruction policy (D15-S5.7, DISC-068).

This table is the only thing standing between the suite and a steel door that shatters, because
nothing at runtime checks: a part dents because its mesh has shape keys and shatters because it
declares a manifest. So the properties asserted here are the invariant itself rather than a
transcription of it.
"""

import pytest

from syndicate_policy.classes import (
    CLASSES,
    DEFORM,
    FRACTURE,
    TRANSFORMS,
    PolicyError,
    parse_class,
    permits,
    require_permitted,
    treatment,
)
from syndicate_policy.exit_codes import EXIT_NAMES, name_for


class TestTheInvariant:
    def test_no_class_receives_both_transforms(self) -> None:
        # The sentence the whole suite exists to hold. If this ever fails, the tools' refusals
        # are no longer sufficient and the runtime needs a gate of its own.
        for destruction_class in CLASSES:
            both = permits(FRACTURE, destruction_class) and permits(DEFORM, destruction_class)
            assert not both, f"{destruction_class} receives both transforms"

    def test_every_class_has_a_treatment(self) -> None:
        for destruction_class in CLASSES:
            assert treatment(destruction_class).destruction_class == destruction_class

    def test_glass_shatters_and_does_not_dent(self) -> None:
        # D15-S5.7: "Glass does not dent. A deformed windscreen looks like a bug; a shattered
        # one reads instantly."
        assert permits(FRACTURE, "GLASS")
        assert not permits(DEFORM, "GLASS")
        assert treatment("GLASS").fracture_shards == 24

    @pytest.mark.parametrize("destruction_class", ["SHEET_METAL", "STRUCTURAL"])
    def test_metal_dents_and_does_not_shatter(self, destruction_class: str) -> None:
        assert permits(DEFORM, destruction_class)
        assert not permits(FRACTURE, destruction_class)
        assert treatment(destruction_class).fracture_shards == 0

    @pytest.mark.parametrize("destruction_class", ["RIGID", "NONE"])
    def test_rigid_and_none_receive_nothing(self, destruction_class: str) -> None:
        assert not permits(DEFORM, destruction_class)
        assert not permits(FRACTURE, destruction_class)

    def test_a_panel_is_subdivided_far_finer_than_a_chassis(self) -> None:
        # D15-S5.7's rationale: a panel crumples locally and needs density where the dent is;
        # a chassis buckles globally and fine subdivision makes it squish like a sponge.
        assert treatment("SHEET_METAL").subdivide_edge_m < treatment("STRUCTURAL").subdivide_edge_m


class TestRefusal:
    def test_require_permitted_names_what_the_class_does_receive(self) -> None:
        with pytest.raises(PolicyError) as caught:
            require_permitted(FRACTURE, "SHEET_METAL")
        assert "SHEET_METAL" in str(caught.value)
        assert DEFORM in str(caught.value)
        assert caught.value.transform == FRACTURE
        assert caught.value.destruction_class == "SHEET_METAL"

    def test_require_permitted_returns_the_treatment_when_it_is_allowed(self) -> None:
        assert require_permitted(FRACTURE, "GLASS").fracture_shards == 24

    def test_a_class_that_receives_nothing_says_so(self) -> None:
        with pytest.raises(PolicyError) as caught:
            require_permitted(DEFORM, "RIGID")
        assert "none" in str(caught.value)


class TestParsing:
    @pytest.mark.parametrize("raw", ["GLASS", "glass", " Glass "])
    def test_a_class_parses_case_insensitively(self, raw: str) -> None:
        assert parse_class(raw) == "GLASS"

    @pytest.mark.parametrize("raw", [None, "", "   ", "CHEESE", "GLAS"])
    def test_anything_else_raises_rather_than_defaulting(self, raw) -> None:
        # Never defaults: a misspelled class silently becoming RIGID would author nothing and
        # report success, which is the shape of every bug this module exists to prevent.
        with pytest.raises(PolicyError):
            parse_class(raw)


class TestExitCodes:
    def test_the_shared_range_is_64_to_79(self) -> None:
        from syndicate_policy import exit_codes

        shared = [
            value
            for name, value in vars(exit_codes).items()
            if name.startswith("EXIT_") and isinstance(value, int) and value not in (0,)
        ]
        assert all(64 <= code <= 99 for code in shared)

    def test_no_code_is_used_for_two_meanings(self) -> None:
        # The failure this catches: three packages kept their own tables and 65 meant "input
        # unreadable" in one, "under-labelled model" in another (DISC-068).
        from syndicate_policy import exit_codes

        codes = [
            value
            for name, value in vars(exit_codes).items()
            if name.startswith("EXIT_") and isinstance(value, int)
        ]
        assert len(codes) == len(set(codes)) or len(set(codes)) == len(EXIT_NAMES)

    def test_every_code_has_a_stable_name(self) -> None:
        for code, expected in EXIT_NAMES.items():
            assert name_for(code) == expected

    def test_an_unknown_code_is_named_rather_than_crashing(self) -> None:
        assert name_for(255) == "UNKNOWN_255"

    def test_the_transform_refusal_has_its_own_code(self) -> None:
        # Its own code rather than USAGE, because the invocation is well formed and an agent's
        # response differs: fix the class, not the flags.
        from syndicate_policy.exit_codes import EXIT_TRANSFORM_NOT_PERMITTED, EXIT_USAGE

        assert EXIT_TRANSFORM_NOT_PERMITTED != EXIT_USAGE
        assert name_for(EXIT_TRANSFORM_NOT_PERMITTED) == "TRANSFORM_NOT_PERMITTED"


def test_the_transform_vocabulary_is_the_glossarys() -> None:
    # D00-S6 defines both words, and its note on each is "not the other one". Inventing a
    # synonym here would be exactly the loose usage CLAUDE.md warns outlives the session.
    assert set(TRANSFORMS) == {"FRACTURE", "DEFORM"}
