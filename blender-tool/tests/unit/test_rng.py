"""Seeded randomness (D09-S8, G11).

The determinism guarantee is only as strong as the generator underneath it, and a
generator that silently changes stream between runs produces a pipeline that looks
deterministic in a single session and is not.
"""

import math

import pytest

from syndicate_fracture.rng import Pcg32, mix, stable_hash


class TestPcg32:
    def test_same_seed_yields_the_same_stream(self) -> None:
        a = [Pcg32(1337).next_uint32() for _ in range(50)]
        b = [Pcg32(1337).next_uint32() for _ in range(50)]
        assert a == b

    def test_different_seeds_diverge(self) -> None:
        a = [Pcg32(1337).next_uint32() for _ in range(50)]
        b = [Pcg32(1338).next_uint32() for _ in range(50)]
        assert a != b

    def test_output_stays_in_the_uint32_range(self) -> None:
        rng = Pcg32(1)
        for _ in range(500):
            value = rng.next_uint32()
            assert 0 <= value < (1 << 32)

    def test_floats_are_in_the_unit_interval(self) -> None:
        rng = Pcg32(7)
        for _ in range(500):
            value = rng.next_float()
            assert 0.0 <= value < 1.0

    def test_uniform_spans_its_range(self) -> None:
        rng = Pcg32(9)
        samples = [rng.uniform(-2.0, 5.0) for _ in range(2000)]
        assert all(-2.0 <= s < 5.0 for s in samples)
        assert min(samples) < -1.5
        assert max(samples) > 4.5

    def test_next_int_is_unbiased_across_buckets(self) -> None:
        # Rejection sampling matters here: the modulo shortcut skews the low buckets, and
        # a skew in site placement biases every fracture in the same direction.
        rng = Pcg32(11)
        counts = [0] * 7
        for _ in range(70_000):
            counts[rng.next_int(7)] += 1
        for count in counts:
            assert abs(count - 10_000) < 600

    def test_next_int_rejects_a_non_positive_bound(self) -> None:
        with pytest.raises(ValueError):
            Pcg32(1).next_int(0)

    def test_unit_vectors_are_normalised_and_cover_the_sphere(self) -> None:
        rng = Pcg32(13)
        vectors = [rng.unit_vector() for _ in range(2000)]
        for v in vectors:
            assert math.sqrt(sum(c * c for c in v)) == pytest.approx(1.0, abs=1e-12)
        # Uniform over the sphere means the mean is near the origin; normalising three
        # uniform coordinates instead would bunch directions toward the cube diagonals.
        mean = [sum(v[axis] for v in vectors) / len(vectors) for axis in range(3)]
        assert all(abs(m) < 0.05 for m in mean)


class TestSeedDerivation:
    def test_mix_is_order_sensitive(self) -> None:
        assert mix(1, 2) != mix(2, 1)

    def test_mix_is_stable_across_calls(self) -> None:
        assert mix(1337, 42) == mix(1337, 42)

    def test_stable_hash_is_pinned_to_a_known_value(self) -> None:
        # Python randomises str hashing per process, so a seed derived from `hash(name)`
        # would differ between two runs of the same command — G11's failure mode. Pinning
        # one value catches a change to the FNV constants, which would silently renumber
        # every existing fixture's shards and invalidate every golden manifest.
        assert stable_hash("test_cube_1m") == 0x90012C97E143AD2E
        assert stable_hash("shard_000") != stable_hash("shard_001")

    def test_per_object_seeds_differ(self) -> None:
        seed = 1337
        assert mix(seed, stable_hash("cube")) != mix(seed, stable_hash("plate"))
