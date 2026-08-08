"""Seeded PCG32, the tool's only source of randomness (D09-S8, G11).

Deliberately not ``random`` or ``numpy.random``: G11 requires two runs on two machines with
the same seed to produce byte-identical topology, and the stdlib generator's stream is a
CPython implementation detail. PCG32 is a fixed, published algorithm, so this implementation
and the Java ``Pcg32`` in ``game-core`` produce the same sequence from the same seed — which
is what lets a fixture generated here be reproduced by a test there.
"""

from __future__ import annotations

import math

_MULT = 6364136223846793005
_MASK64 = (1 << 64) - 1
_MASK32 = (1 << 32) - 1


class Pcg32:
    """The PCG-XSH-RR 64/32 variant."""

    __slots__ = ("_inc", "_state")

    def __init__(self, seed: int, sequence: int = 1) -> None:
        self._inc = ((sequence << 1) | 1) & _MASK64
        self._state = 0
        self.next_uint32()
        self._state = (self._state + (seed & _MASK64)) & _MASK64
        self.next_uint32()

    def next_uint32(self) -> int:
        old = self._state
        self._state = (old * _MULT + self._inc) & _MASK64
        xorshifted = (((old >> 18) ^ old) >> 27) & _MASK32
        rot = (old >> 59) & 31
        return ((xorshifted >> rot) | (xorshifted << ((-rot) & 31))) & _MASK32

    def next_float(self) -> float:
        """A float in ``[0, 1)``, using 24 bits — the exactly-representable range."""
        return (self.next_uint32() >> 8) * (1.0 / (1 << 24))

    def uniform(self, low: float, high: float) -> float:
        return low + (high - low) * self.next_float()

    def next_int(self, bound: int) -> int:
        """A value in ``[0, bound)``, debiased by rejection.

        The modulo shortcut would skew the low values, and while the skew is invisible in
        one draw it is not in site placement, where it would bias every fracture in the
        same direction.
        """
        if bound <= 0:
            raise ValueError("bound must be positive")
        threshold = (1 << 32) % bound
        while True:
            value = self.next_uint32()
            if value >= threshold:
                return value % bound

    def unit_vector(self) -> tuple[float, float, float]:
        """A direction sampled uniformly over the sphere.

        Sampling ``z`` uniformly and the azimuth uniformly is the correct construction;
        normalising three uniform coordinates would cluster directions toward the cube's
        corners and give impact-biased fracture a visible bias along the diagonals.
        """
        z = self.uniform(-1.0, 1.0)
        theta = self.uniform(0.0, 2.0 * math.pi)
        r = math.sqrt(max(0.0, 1.0 - z * z))
        return (r * math.cos(theta), r * math.sin(theta), z)


def mix(*values: int) -> int:
    """Combine values into a seed with SplitMix64 finalisation.

    Used to derive a per-object or per-stage seed from the master seed, so two objects in
    one file fracture differently while each stays reproducible. ``hash()`` is unusable
    here: Python randomises string hashing per process, so a seed derived from an object
    name would differ between two runs of the same command (G11).
    """
    acc = 0
    for value in values:
        acc = (acc ^ (value & _MASK64)) & _MASK64
        acc = (acc + 0x9E3779B97F4A7C15) & _MASK64
        acc = ((acc ^ (acc >> 30)) * 0xBF58476D1CE4E5B9) & _MASK64
        acc = ((acc ^ (acc >> 27)) * 0x94D049BB133111EB) & _MASK64
        acc = acc ^ (acc >> 31)
    return acc


def stable_hash(text: str) -> int:
    """A process-independent hash of a string, for deriving per-object seeds (G11)."""
    acc = 0xCBF29CE484222325
    for byte in text.encode("utf-8"):
        acc = ((acc ^ byte) * 0x100000001B3) & _MASK64
    return acc
