/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import com.badlogic.gdx.math.Quaternion;
import dev.syndicate.model.net.NetConstants;

/**
 * The field encodings of docs/10_networking_multiplayer.md#D10-S4.3.
 *
 * <p>Quantisation is what makes the bandwidth budget of AC-D10-17 reachable, and every encoding
 * here is lossy by design: a position lands on a 1.2 cm lattice, a quaternion component on one of
 * 1,024 steps, a health fraction on one of 256. The losses are chosen to sit below what a player
 * can see at the ranges the arena allows.
 *
 * <p>Two properties matter more than the compression:
 *
 * <ul>
 *   <li><b>Every value is absolute, never an increment.</b> That is what makes applying a snapshot
 *       twice a no-op (G16, D10-R10). A delta encoding that sent differences would accumulate error
 *       and would make a duplicate packet corrupt state.
 *   <li><b>Encode-then-decode is idempotent.</b> {@code decode(encode(decode(encode(v))))} equals
 *       {@code decode(encode(v))}: the second round trip cannot drift, because the first already
 *       landed on the lattice. Without that, a value that survived a baseline would keep moving
 *       every time it was re-encoded and no delta would ever be empty.
 * </ul>
 *
 * <p>Out-of-range values are <b>clamped, not rejected</b>. A vehicle that leaves the arena bounds
 * is a bug in the arena or a kill plane that has not fired yet, and refusing to encode it would
 * take the whole snapshot down with it — the clamp puts it at the boundary, visibly wrong, and the
 * match continues.
 */
public final class Quantisation {

    /** {@code 1/sqrt(2)}: the largest a quaternion's <em>smallest</em> three components can be. */
    private static final float SMALLEST_THREE_RANGE = 0.70710678f;

    private Quantisation() {
        throw new AssertionError("no instances");
    }

    // ---- Symmetric scalar ranges ---------------------------------------------------

    /**
     * Encodes a value in {@code [-range, +range]} into {@code bits}, clamping outside it.
     *
     * <p>The mapping puts zero exactly on a lattice point when {@code bits} is even, which matters:
     * a stationary vehicle must encode to the same value every tick or every snapshot would carry
     * a velocity delta for a car that is parked.
     */
    public static int encodeRanged(float value, float range, int bits) {
        int steps = (1 << bits) - 1;
        float clamped = Math.max(-range, Math.min(range, value));
        float normalised = (clamped + range) / (2f * range);
        return Math.round(normalised * steps);
    }

    /** The inverse of {@link #encodeRanged}. */
    public static float decodeRanged(int quantised, float range, int bits) {
        int steps = (1 << bits) - 1;
        float normalised = (float) quantised / (float) steps;
        return normalised * 2f * range - range;
    }

    /** Encodes a value in {@code [0,1]} into {@code bits}. */
    public static int encodeUnit(float value, int bits) {
        int steps = (1 << bits) - 1;
        float clamped = Math.max(0f, Math.min(1f, value));
        return Math.round(clamped * steps);
    }

    /** The inverse of {@link #encodeUnit}. */
    public static float decodeUnit(int quantised, int bits) {
        int steps = (1 << bits) - 1;
        return (float) quantised / (float) steps;
    }

    // ---- The named encodings of D10-S4.3 -------------------------------------------

    /** One position axis: 16 bits over ±{@code POSITION_RANGE_M}, a step of about 1.2 cm. */
    public static int encodePositionAxis(float metres) {
        return encodeRanged(metres, NetConstants.POSITION_RANGE_M, NetConstants.POSITION_BITS);
    }

    public static float decodePositionAxis(int quantised) {
        return decodeRanged(quantised, NetConstants.POSITION_RANGE_M, NetConstants.POSITION_BITS);
    }

    /** One linear velocity axis: 12 bits over ±60 m/s. */
    public static int encodeLinearVelocityAxis(float metresPerSecond) {
        return encodeRanged(metresPerSecond, NetConstants.LINEAR_VELOCITY_RANGE_MPS, NetConstants.VELOCITY_BITS);
    }

    public static float decodeLinearVelocityAxis(int quantised) {
        return decodeRanged(quantised, NetConstants.LINEAR_VELOCITY_RANGE_MPS, NetConstants.VELOCITY_BITS);
    }

    /** One angular velocity axis: 12 bits over ±30 rad/s. */
    public static int encodeAngularVelocityAxis(float radiansPerSecond) {
        return encodeRanged(
                radiansPerSecond, NetConstants.ANGULAR_VELOCITY_RANGE_RAD_PER_S, NetConstants.VELOCITY_BITS);
    }

    public static float decodeAngularVelocityAxis(int quantised) {
        return decodeRanged(quantised, NetConstants.ANGULAR_VELOCITY_RANGE_RAD_PER_S, NetConstants.VELOCITY_BITS);
    }

    /** A health fraction: 8 bits over {@code [0,1]} (D07-S5.9). */
    public static int encodeHealthFraction(float fraction) {
        return encodeUnit(fraction, NetConstants.HEALTH_BITS);
    }

    public static float decodeHealthFraction(int quantised) {
        return decodeUnit(quantised, NetConstants.HEALTH_BITS);
    }

    /** A control axis in {@code [-1,1]}: throttle and steer. */
    public static int encodeControlAxis(float value) {
        return encodeRanged(value, 1f, NetConstants.CONTROL_AXIS_BITS);
    }

    public static float decodeControlAxis(int quantised) {
        return decodeRanged(quantised, 1f, NetConstants.CONTROL_AXIS_BITS);
    }

    /** An aim angle in radians, 16 bits over ±π. */
    public static int encodeAngle(float radians) {
        return encodeRanged(wrapAngle(radians), (float) Math.PI, NetConstants.AIM_BITS);
    }

    public static float decodeAngle(int quantised) {
        return decodeRanged(quantised, (float) Math.PI, NetConstants.AIM_BITS);
    }

    // ---- Smallest-three quaternions -------------------------------------------------

    /**
     * Packs a unit quaternion into 32 bits: a 2-bit index naming the largest component, then the
     * other three at {@code ROTATION_COMPONENT_BITS} each (D10-S4.3).
     *
     * <p>The dropped component is recovered as {@code sqrt(1 - x² - y² - z²)}, which needs its sign
     * to be known — so the <em>largest</em> component is the one dropped and the quaternion is
     * negated when that component is negative. {@code q} and {@code -q} are the same rotation, so
     * nothing is lost by the flip.
     *
     * <p>The remaining three are bounded by {@code 1/sqrt(2)} rather than by 1, which is where the
     * technique earns its accuracy: the same 10 bits cover a range 41% narrower.
     */
    public static int packRotation(Quaternion rotation) {
        float x = rotation.x;
        float y = rotation.y;
        float z = rotation.z;
        float w = rotation.w;

        int largest = 0;
        float largestAbs = Math.abs(x);
        if (Math.abs(y) > largestAbs) {
            largest = 1;
            largestAbs = Math.abs(y);
        }
        if (Math.abs(z) > largestAbs) {
            largest = 2;
            largestAbs = Math.abs(z);
        }
        if (Math.abs(w) > largestAbs) {
            largest = 3;
            largestAbs = Math.abs(w);
        }

        float largestValue =
                switch (largest) {
                    case 0 -> x;
                    case 1 -> y;
                    case 2 -> z;
                    default -> w;
                };
        float sign = largestValue < 0f ? -1f : 1f;
        x *= sign;
        y *= sign;
        z *= sign;
        w *= sign;

        float a;
        float b;
        float c;
        switch (largest) {
            case 0 -> {
                a = y;
                b = z;
                c = w;
            }
            case 1 -> {
                a = x;
                b = z;
                c = w;
            }
            case 2 -> {
                a = x;
                b = y;
                c = w;
            }
            default -> {
                a = x;
                b = y;
                c = z;
            }
        }

        int bits = NetConstants.ROTATION_COMPONENT_BITS;
        int packed = largest;
        packed |= encodeRanged(a, SMALLEST_THREE_RANGE, bits) << 2;
        packed |= encodeRanged(b, SMALLEST_THREE_RANGE, bits) << (2 + bits);
        packed |= encodeRanged(c, SMALLEST_THREE_RANGE, bits) << (2 + 2 * bits);
        return packed;
    }

    /** How many bits {@link #packRotation} produces. */
    public static int rotationBits() {
        return 2 + 3 * NetConstants.ROTATION_COMPONENT_BITS;
    }

    /** Unpacks {@link #packRotation} into {@code out}, which is returned. */
    public static Quaternion unpackRotation(int packed, Quaternion out) {
        int bits = NetConstants.ROTATION_COMPONENT_BITS;
        int mask = (1 << bits) - 1;
        int largest = packed & 0b11;
        float a = decodeRanged((packed >>> 2) & mask, SMALLEST_THREE_RANGE, bits);
        float b = decodeRanged((packed >>> (2 + bits)) & mask, SMALLEST_THREE_RANGE, bits);
        float c = decodeRanged((packed >>> (2 + 2 * bits)) & mask, SMALLEST_THREE_RANGE, bits);
        // Clamped at zero: quantisation can push the sum a hair past 1 for a rotation that sits
        // exactly between two lattice points, and a NaN here would propagate into a transform.
        float remaining = (float) Math.sqrt(Math.max(0f, 1f - (a * a + b * b + c * c)));

        switch (largest) {
            case 0 -> out.set(remaining, a, b, c);
            case 1 -> out.set(a, remaining, b, c);
            case 2 -> out.set(a, b, remaining, c);
            default -> out.set(a, b, c, remaining);
        }
        return out;
    }

    /** Folds an angle into {@code [-π, π]} so the encoder's clamp never truncates a wrapped one. */
    public static float wrapAngle(float radians) {
        float twoPi = (float) (2.0 * Math.PI);
        float wrapped = (float) Math.IEEEremainder(radians, twoPi);
        if (wrapped > Math.PI) {
            wrapped -= twoPi;
        } else if (wrapped < -Math.PI) {
            wrapped += twoPi;
        }
        return wrapped;
    }
}
