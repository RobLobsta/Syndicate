/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.core.asset.HandlingBlock;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.EngineConfiguration;
import dev.syndicate.model.SimulationConstants;
import java.util.Objects;

/**
 * One vehicle's handling character, and the real-world measurements it was derived from
 * (docs/05_vehicle_part_system.md#D05-S5.6, docs/06_physics_simulation.md#D06-S4.5).
 *
 * <p>A profile is <b>not</b> a second copy of the content in {@code assets/vehicles/}. It is the
 * <em>research record</em>: the published figures for a real car, the arithmetic that turns them
 * into the simulation's parameters, and the calibration targets a test can hold the simulation to.
 * {@code VehicleProfileContentTest} asserts that the authored {@code part.json} files agree with the
 * profile they were derived from, so the two cannot drift; {@code VehicleProfileCalibrationTest}
 * drives each profile in the real physics world and asserts it reproduces its measured 0-100 time.
 *
 * <p><b>Why the real car's numbers are usable at all.</b> The simulation's vehicle model is a
 * ray-cast chassis with a constant tractive force capped by an engine power limit (DEC-032). That is
 * a poor model of a gearbox and a very good model of everything else, so:
 *
 * <ul>
 *   <li>{@link #engineForceN} is calibrated so the sim reproduces the car's published 0-100 km/h.
 *   <li>{@link #enginePowerW} is crank power times a driveline efficiency, and it is what makes the
 *       derived top speed come out near the published one instead of several times it.
 *   <li>{@link #dragCoefficientNPerMps2} is {@code ½·ρ·Cd·A} from the published {@code Cd} where
 *       there is one, and from the class's typical {@code Cd·A} where there is not.
 *   <li>{@link #brakeForceN} is {@code m·v²/2d} from the published 100-0 braking distance.
 * </ul>
 *
 * <p><b>Names.</b> {@link #displayName} is the in-game vehicle and {@link #referenceVehicle} is the
 * real car its physics come from. They are deliberately different: measured performance figures are
 * facts and free to use, whereas a manufacturer's name and model are trademarks, and shipping a
 * vehicle called after one is a licensing question rather than an engineering one (DEC-033).
 */
public record VehicleProfile(
        AssetId profileId,
        String displayName,
        String referenceVehicle,
        String referenceSource,
        String vehicleClass,

        // ---- Published figures for the reference vehicle -----------------------------
        float kerbMassKg,
        float enginePowerKw,
        float engineTorqueNm,
        float zeroToHundredS,
        float topSpeedKph,
        float brakingHundredToZeroM,
        float dragCoefficientCd,
        float frontalAreaM2,
        float wheelbaseM,
        String tyreFront,
        String tyreRear,
        EngineConfiguration engineConfiguration,
        float idleRpm,
        float redlineRpm,

        // ---- Derived simulation parameters -------------------------------------------
        float chassisMassKg,
        float wheelMassKg,
        float engineForceN,
        float enginePowerW,
        float brakeForceN,
        float dragCoefficientNPerMps2,
        float rollingResistance,
        float downforceCoefficientNPerMps2,
        float maxSteerRad,
        float steerRateRadPerSec,
        float frictionSlip,
        float suspensionStiffness,
        float dampingCompression,
        float dampingRelaxation,
        float rollInfluence,
        float wheelRadiusFrontM,
        float wheelRadiusRearM,
        float trackFrontM,
        float trackRearM,
        float frontMassFraction) {

    /** Air density at sea level, kg/m³. The {@code ρ} of {@code ½·ρ·Cd·A}. */
    public static final float AIR_DENSITY_KG_PER_M3 = 1.225f;

    /** 100 km/h in m/s — the speed every published acceleration and braking figure is quoted to. */
    public static final float HUNDRED_KPH_MPS = 27.7778f;

    public VehicleProfile {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(referenceVehicle, "referenceVehicle");
    }

    /** {@code ½·ρ·Cd·A}, in newtons per (m/s)² — the derivation behind {@link #dragCoefficientNPerMps2}. */
    public static float dragFromCdA(float cd, float frontalAreaM2) {
        return 0.5f * AIR_DENSITY_KG_PER_M3 * cd * frontalAreaM2;
    }

    /**
     * The constant tractive force that reproduces a 0-100 km/h time.
     *
     * <p>{@code F = m·v/t + k_roll·m·g + k_drag·v²/3}. The last term is the mean drag over a run
     * whose speed rises roughly linearly, since {@code mean(v²) = v_max²/3} — small at 100 km/h, and
     * the reason to include it anyway is that leaving it out makes a fast car's calibration
     * optimistic by exactly the amount it is hardest to notice.
     */
    public static float engineForceFor(float massKg, float zeroToHundredS, float kDrag, float kRoll) {
        float gravity = Math.abs(SimulationConstants.WORLD_GRAVITY_Y);
        return massKg * (HUNDRED_KPH_MPS / zeroToHundredS)
                + kRoll * massKg * gravity
                + kDrag * (HUNDRED_KPH_MPS * HUNDRED_KPH_MPS / 3f);
    }

    /** The brake force that stops the car in a published 100-0 distance: {@code F = m·v²/2d}. */
    public static float brakeForceFor(float massKg, float brakingHundredToZeroM) {
        return massKg * HUNDRED_KPH_MPS * HUNDRED_KPH_MPS / (2f * brakingHundredToZeroM);
    }

    /**
     * The speed at which power and drag balance, in m/s — the model's own top speed, before the
     * arena clamp.
     *
     * <p>Solves {@code P/v = k_drag·v² + k_roll·m·g} by bisection rather than by the cubic formula:
     * the equation is monotonic in {@code v}, forty iterations put it well inside a metre per second,
     * and nobody has to check a discriminant.
     */
    public float derivedTopSpeedMps() {
        float gravity = Math.abs(SimulationConstants.WORLD_GRAVITY_Y);
        float rolling = rollingResistance * totalMassKg() * gravity;
        float low = 0f;
        float high = 200f;
        for (int i = 0; i < 40; i++) {
            float mid = (low + high) * 0.5f;
            float demand = dragCoefficientNPerMps2 * mid * mid * mid + rolling * mid;
            if (demand < enginePowerW) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5f;
    }

    /** The derived top speed in km/h, for comparison against {@link #topSpeedKph}. */
    public float derivedTopSpeedKph() {
        return derivedTopSpeedMps() * 3.6f;
    }

    /** The speed above which the engine is power-limited rather than traction-limited, in m/s. */
    public float powerCrossoverMps() {
        return enginePowerW / engineForceN;
    }

    /** Total mass: the chassis plus four wheels. Equals {@link #kerbMassKg} by construction. */
    /**
     * This vehicle's engine sound, as parameters (D15-S8, D15-R37).
     *
     * <p>Derived rather than authored separately, so a car cannot sound like something its own
     * specification says it is not: the configuration and rev range are published facts about the
     * reference car, and the power is the same {@code enginePowerW} the physics accelerates it with.
     * A car that gets faster gets louder in the same commit.
     */
    public EngineVoice engineVoice() {
        return new EngineVoice(engineConfiguration, idleRpm, redlineRpm, enginePowerW);
    }

    public float totalMassKg() {
        return chassisMassKg + 4f * wheelMassKg;
    }

    /** Standing acceleration at full throttle, m/s². */
    public float accelerationMps2() {
        return engineForceN / totalMassKg();
    }

    /** Newtons per kilogram — the figure that actually predicts which car wins a drag race. */
    public float forceToWeightNPerKg() {
        return engineForceN / totalMassKg();
    }

    /** The chassis part's handling block: this profile's aerodynamics (D08-R5). */
    public HandlingBlock chassisHandling() {
        return HandlingBlock.REFERENCE.withChassisAero(
                dragCoefficientNPerMps2, rollingResistance, downforceCoefficientNPerMps2);
    }

    /** A wheel part's handling block: this profile's suspension corner (D08-R5). */
    public HandlingBlock wheelHandling() {
        return HandlingBlock.REFERENCE.withSuspension(dampingCompression, dampingRelaxation, rollInfluence);
    }

    /** Starts a profile. Every field must be set; there is no sensible default for a real car. */
    public static Builder builder(AssetId profileId, String displayName, String referenceVehicle) {
        return new Builder(profileId, displayName, referenceVehicle);
    }

    /**
     * Assembles a {@link VehicleProfile}.
     *
     * <p>A builder rather than the canonical constructor for the reason {@code PartType} has one: a
     * thirty-argument constructor of floats is a call site where two transposed values compile
     * silently, and here they would compile into a car that handles wrongly rather than into an
     * error.
     */
    public static final class Builder {

        private final AssetId profileId;
        private final String displayName;
        private final String referenceVehicle;

        private String referenceSource = "";
        private String vehicleClass = "medium";
        private float kerbMassKg;
        private float enginePowerKw;
        private float engineTorqueNm;
        private float zeroToHundredS;
        private float topSpeedKph;
        private float brakingHundredToZeroM;
        private float dragCoefficientCd;
        private float frontalAreaM2;
        private float wheelbaseM;
        private String tyreFront = "";
        private String tyreRear = "";
        private EngineConfiguration engineConfiguration = EngineConfiguration.V6;
        private float idleRpm = 800f;
        private float redlineRpm = 7000f;
        private float drivelineEfficiency = 0.90f;
        private float wheelMassKg = 38f;
        private float rollingResistance = HandlingBlock.REFERENCE_ROLLING_RESISTANCE;
        private float downforceCoefficientNPerMps2 = HandlingBlock.REFERENCE_DOWNFORCE_COEFFICIENT;
        private float maxSteerRad;
        private float steerRateRadPerSec;
        private float frictionSlip = 2.0f;
        private float suspensionStiffness = 30f;
        private float dampingCompression = HandlingBlock.REFERENCE_SUSPENSION_COMPRESSION;
        private float dampingRelaxation = HandlingBlock.REFERENCE_SUSPENSION_DAMPING;
        private float rollInfluence = HandlingBlock.REFERENCE_ROLL_INFLUENCE;
        private float wheelRadiusFrontM;
        private float wheelRadiusRearM;
        private float trackFrontM;
        private float trackRearM;
        private float frontMassFraction = 0.5f;

        private Builder(AssetId profileId, String displayName, String referenceVehicle) {
            this.profileId = profileId;
            this.displayName = displayName;
            this.referenceVehicle = referenceVehicle;
        }

        public Builder referenceSource(String value) {
            this.referenceSource = value;
            return this;
        }

        public Builder vehicleClass(String value) {
            this.vehicleClass = value;
            return this;
        }

        /** The published kerb or homologated mass, in kilograms. */
        public Builder kerbMassKg(float value) {
            this.kerbMassKg = value;
            return this;
        }

        /** Published crank power and torque. */
        public Builder engine(float powerKw, float torqueNm) {
            this.enginePowerKw = powerKw;
            this.engineTorqueNm = torqueNm;
            return this;
        }

        /** Published 0-100 km/h, top speed and 100-0 braking distance. */
        public Builder performance(float zeroToHundredS, float topSpeedKph, float brakingHundredToZeroM) {
            this.zeroToHundredS = zeroToHundredS;
            this.topSpeedKph = topSpeedKph;
            this.brakingHundredToZeroM = brakingHundredToZeroM;
            return this;
        }

        /** Published or class-typical drag coefficient and frontal area. */
        public Builder aero(float cd, float frontalAreaM2, float downforceCoefficientNPerMps2) {
            this.dragCoefficientCd = cd;
            this.frontalAreaM2 = frontalAreaM2;
            this.downforceCoefficientNPerMps2 = downforceCoefficientNPerMps2;
            return this;
        }

        /**
         * How much crank power reaches the road. A dual-clutch road car loses about 10%, a
         * sequential race transaxle about 8%.
         */
        public Builder drivelineEfficiency(float value) {
            this.drivelineEfficiency = value;
            return this;
        }

        public Builder geometry(float wheelbaseM, float trackFrontM, float trackRearM, float frontMassFraction) {
            this.wheelbaseM = wheelbaseM;
            this.trackFrontM = trackFrontM;
            this.trackRearM = trackRearM;
            this.frontMassFraction = frontMassFraction;
            return this;
        }

        /**
         * How the engine is arranged and how far it revs — what the car sounds like (D15-S8).
         *
         * <p>On the profile rather than in the audio bank, because it is a published fact about
         * the reference car, and because it is the field that makes two vehicles of the same
         * weight class sound like different cars rather than the same one twice.
         */
        public Builder engineVoice(EngineConfiguration configuration, float idleRpm, float redlineRpm) {
            this.engineConfiguration = configuration;
            this.idleRpm = idleRpm;
            this.redlineRpm = redlineRpm;
            return this;
        }

        public Builder wheels(String tyreFront, String tyreRear, float radiusFrontM, float radiusRearM, float massKg) {
            this.tyreFront = tyreFront;
            this.tyreRear = tyreRear;
            this.wheelRadiusFrontM = radiusFrontM;
            this.wheelRadiusRearM = radiusRearM;
            this.wheelMassKg = massKg;
            return this;
        }

        public Builder steering(float maxSteerRad, float steerRateRadPerSec) {
            this.maxSteerRad = maxSteerRad;
            this.steerRateRadPerSec = steerRateRadPerSec;
            return this;
        }

        public Builder suspension(
                float frictionSlip,
                float stiffness,
                float dampingCompression,
                float dampingRelaxation,
                float rollInfluence) {
            this.frictionSlip = frictionSlip;
            this.suspensionStiffness = stiffness;
            this.dampingCompression = dampingCompression;
            this.dampingRelaxation = dampingRelaxation;
            this.rollInfluence = rollInfluence;
            return this;
        }

        public Builder rollingResistance(float value) {
            this.rollingResistance = value;
            return this;
        }

        /** Runs the derivations and produces the profile. */
        public VehicleProfile build() {
            float kDrag = dragFromCdA(dragCoefficientCd, frontalAreaM2);
            float engineForceN = engineForceFor(kerbMassKg, zeroToHundredS, kDrag, rollingResistance);
            return new VehicleProfile(
                    profileId,
                    displayName,
                    referenceVehicle,
                    referenceSource,
                    vehicleClass,
                    kerbMassKg,
                    enginePowerKw,
                    engineTorqueNm,
                    zeroToHundredS,
                    topSpeedKph,
                    brakingHundredToZeroM,
                    dragCoefficientCd,
                    frontalAreaM2,
                    wheelbaseM,
                    tyreFront,
                    tyreRear,
                    engineConfiguration,
                    idleRpm,
                    redlineRpm,
                    kerbMassKg - 4f * wheelMassKg,
                    wheelMassKg,
                    engineForceN,
                    enginePowerKw * 1000f * drivelineEfficiency,
                    brakeForceFor(kerbMassKg, brakingHundredToZeroM),
                    kDrag,
                    rollingResistance,
                    downforceCoefficientNPerMps2,
                    maxSteerRad,
                    steerRateRadPerSec,
                    frictionSlip,
                    suspensionStiffness,
                    dampingCompression,
                    dampingRelaxation,
                    rollInfluence,
                    wheelRadiusFrontM,
                    wheelRadiusRearM,
                    trackFrontM,
                    trackRearM,
                    frontMassFraction);
        }
    }
}
