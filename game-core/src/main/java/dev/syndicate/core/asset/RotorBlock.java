/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Vector3;

/**
 * What makes a rotor part a particular rotor (docs/08_asset_pipeline.md#D08-S4.2,
 * docs/05_vehicle_part_system.md#D05-S4.2).
 *
 * <p>Here for exactly the reason {@link HandlingBlock} and {@link WeaponBlock} are (DEC-031,
 * DEC-039): a disc radius and a spin axis are properties of <em>one</em> rotor, not quantities a
 * vehicle sums across its parts. Fitting a second rotor must not add the two radii together, and
 * a rotor's <em>role</em> — whether it lifts or whether it stops the fuselage spinning — is an
 * identity rather than a number.
 *
 * <p><b>Thrust is the one number that is a stat.</b> {@code ROTOR_THRUST_N} lives in
 * {@code StatBlock} so that degradation (D05-S5.4) and utility multipliers (D05-S5.6 phase 2)
 * reach it, in the same split {@link WeaponBlock} makes: the block says what the rotor
 * <em>is</em>, the stats say what it currently <em>does</em>. A rotor shot to half health lifts
 * less because its stat degraded, and nothing in this record changes.
 *
 * @param role whether this rotor lifts the vehicle or opposes the torque of one that does
 * @param radiusM the disc's radius in metres, from the hub to the blade tip. Used for the swept
 *     collision hull (DEC-091) and for the tail rotor's moment arm.
 * @param spinAxisLocal the axis the disc turns about, in the part's local space, unit length.
 *     {@code +Y} for a conventional main rotor, a lateral axis for a tail rotor.
 * @param bladeCount how many blades, which is cosmetic — the client spaces them for the spin
 *     articulation (DEC-083) — and is carried here because it is a fact about the part rather than
 *     about the vehicle.
 * @param maxRpm the disc's governed speed. Rotor speed is very nearly constant in real flight;
 *     this is what the client spins the blades at and what the thrust curve is quoted against.
 */
public record RotorBlock(Role role, float radiusM, Vector3 spinAxisLocal, int bladeCount, float maxRpm) {

    /** What a rotor is for. */
    public enum Role {
        /**
         * Lifts the vehicle. Its thrust acts along the rotor's own axis, and it drags the fuselage
         * around with it — the torque a {@link #TAIL} rotor exists to cancel.
         */
        MAIN,

        /**
         * Opposes the main rotor's torque and steers in yaw. Its thrust acts sideways at the end of
         * the tail boom, so it is a couple rather than a lift force, and its authority scales with
         * how far behind the centre of mass it sits.
         */
        TAIL
    }

    /** Revolutions per minute for a rotor that authors no {@code maxRpm}; a light helicopter's. */
    public static final float DEFAULT_MAX_RPM = 394f;

    /** Newtons of thrust a rotor produces at full collective when it authors no stat. */
    public static final float DEFAULT_THRUST_N = 20000f;

    public RotorBlock {
        if (spinAxisLocal == null || spinAxisLocal.isZero()) {
            throw new IllegalArgumentException("rotor spin axis must be a non-zero vector");
        }
        // Copied and normalised on construction: the caller's vector is theirs to mutate, and an
        // un-normalised axis silently scales every force taken along it.
        spinAxisLocal = new Vector3(spinAxisLocal).nor();
        if (radiusM <= 0f) {
            throw new IllegalArgumentException("rotor radius must be positive, was " + radiusM);
        }
        if (maxRpm <= 0f) {
            maxRpm = DEFAULT_MAX_RPM;
        }
    }

    /** True when this rotor is what holds the vehicle up. */
    public boolean isMain() {
        return role == Role.MAIN;
    }
}
