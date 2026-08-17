/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.HandlingBlock;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.SlotDefinition;
import dev.syndicate.core.vehicle.StatBlock.Stat;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shipped content in {@code assets/} agrees with the profile it was derived from
 * (docs/08_asset_pipeline.md#D08-S4.2, docs/05_vehicle_part_system.md#D05-S5.6).
 *
 * <p>{@link VehicleProfile} is the research record and the JSON is the content; this is the seam
 * between them. Without it the two drift the first time somebody tunes a number in one place, and a
 * profile that documents a car the game does not actually have is worse than no profile.
 */
@Tag("integration")
class VehicleProfileContentTest {

    /**
     * Metres the art's axle spacing may differ from the manufacturer's published figures.
     *
     * <p>Widened from 5 cm when the preparation pipeline's output shipped, and the cause is a
     * change in what is being measured rather than a slip in the art. A wheel's placement is now
     * the centre of the wheel <em>part</em> — the tyre and rim alone, with the brake hub a
     * separate part beside it — where the retired dissection measured an island that included the
     * hub and therefore sat further inboard. The Stampede's art puts its wheel centre planes
     * 2.9 cm outboard of the published track per side; the real car's figure is measured at the
     * same plane, so this is the licensed-look model being a little wide, which is ordinary and
     * which the game cannot correct without visibly breaking the vehicle.
     *
     * <p>8 cm still catches every failure this exists for: a wheel on the wrong side, an axis
     * mix-up, or a track out by a metre.
     */
    private static final float ART_TRACK_TOLERANCE_M = 0.08f;

    /**
     * Metres a settled tyre may sit off the ground.
     *
     * <p>Two centimetres, which is generous for a figure that should be exact — and it is generous
     * because the radius comes from a 64-point collision hull rather than from the tyre, and a hull
     * samples a circle at a handful of directions (its measured diameter is a couple of centimetres
     * short of round on both Stampede wheels). What this catches is the failure it exists for: a
     * wheel a whole rest length out of place, which is 30 cm.
     */
    private static final float RIDE_HEIGHT_TOLERANCE_M = 0.02f;

    private static InMemoryAssetIndex assets;

    @BeforeAll
    static void loadShippedContent() {
        assumeTrue(ShippedContent.isPresent(), "shipped assets/ tree is not present");
        assets = ShippedContent.load();
    }

    /** Content that does not load is content that does not exist. */
    @Test
    void theShippedTreeLoadsWithoutErrors() {
        assertThat(ShippedContent.blockingIssues()).isEmpty();
    }

    /** Every profile has an assembly, and every assembly has a profile. */
    @Test
    void everyProfileHasContentAndEveryContentHasAProfile() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            assertThat(assets.assembly(profile.profileId()))
                    .as("assembly for profile %s", profile.profileId().value())
                    .isNotNull();
        }
        for (AssetId assemblyId : assets.assemblies().keySet()) {
            if (isRotorcraft(assemblyId)) {
                // A `VehicleProfile` records a **car's** published performance — a 0-100 time,
                // tyre codes, a braking distance, a gearbox — and a rotorcraft has none of those.
                // The Kestrel's researched figures live in its own `art-source` `profile.json`,
                // which `syndicate-prepare` reads and which is what set its 1,600 kg (DEC-090).
                // Forcing an aircraft into this record would mean inventing a 0-100 time for a
                // helicopter so that a test about cars could keep its shape.
                continue;
            }
            assertThat(VehicleProfiles.byId(assemblyId))
                    .as("profile for assembly %s", assemblyId.value())
                    .isNotNull();
        }
    }

    /** True when any part of this assembly is a rotor — the vehicles a car profile cannot describe. */
    private boolean isRotorcraft(AssetId assemblyId) {
        AssemblyDef assembly = assets.assembly(assemblyId);
        if (assembly == null) {
            return false;
        }
        for (AssemblyDef.PartPlacement placement : assembly.parts()) {
            PartType type = assets.partType(placement.partTypeId());
            if (type != null && type.category() == dev.syndicate.model.PartCategory.ROTOR) {
                return true;
            }
        }
        return false;
    }

    /** The chassis carries the profile's engine, brakes and aerodynamics. */
    @Test
    void eachChassisCarriesItsProfilesEngineAndAero() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            PartType chassis = chassisOf(profile);

            // Not `profile.chassisMassKg()`. That figure is the kerb mass less four wheels, which
            // was the whole of a vehicle when a vehicle was a chassis and four wheels. A prepared
            // vehicle is twenty-odd parts and its chassis is the balance of the kerb mass after
            // every one of them is weighed, so the contract that survives is the kerb mass itself
            // — asserted by `eachAssemblyWeighsItsProfilesKerbMass` — plus the chassis still being
            // the majority of the car, which is what makes it the chassis.
            assertThat(chassis.massKg() / profile.kerbMassKg())
                    .as("%s chassis share of kerb mass", profile.displayName())
                    .isBetween(0.45f, 0.90f);
            assertThat(chassis.stats().resolve(Stat.ENGINE_FORCE_N, 0f))
                    .as("%s engine force", profile.displayName())
                    .isCloseTo(profile.engineForceN(), within(1f));
            assertThat(chassis.stats().resolve(Stat.ENGINE_POWER_W, 0f)).isCloseTo(profile.enginePowerW(), within(1f));
            assertThat(chassis.stats().resolve(Stat.BRAKE_FORCE_N, 0f)).isCloseTo(profile.brakeForceN(), within(1f));

            HandlingBlock handling = chassis.handling();
            assertThat(handling.dragCoefficient()).isCloseTo(profile.dragCoefficientNPerMps2(), within(1e-4f));
            assertThat(handling.rollingResistance()).isCloseTo(profile.rollingResistance(), within(1e-5f));
            assertThat(handling.downforceCoefficient())
                    .isCloseTo(profile.downforceCoefficientNPerMps2(), within(1e-4f));
        }
    }

    /** The wheels carry the profile's grip, springs and steering. */
    @Test
    void eachWheelSetCarriesItsProfilesGripAndSteering() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            List<PartType> wheels = wheelsOf(profile);
            assertThat(wheels).as("%s wheel types", profile.displayName()).hasSize(4);

            for (PartType wheel : wheels) {
                assertThat(wheel.stats().resolve(Stat.FRICTION_SLIP, VehicleFactory.WHEEL_FRICTION_SLIP))
                        .as(
                                "%s grip on %s",
                                profile.displayName(), wheel.partTypeId().value())
                        .isCloseTo(profile.frictionSlip(), within(0.01f));
                assertThat(wheel.stats().resolve(Stat.SUSPENSION_STIFFNESS, VehicleFactory.WHEEL_SUSPENSION_STIFFNESS))
                        .isCloseTo(profile.suspensionStiffness(), within(0.1f));
                assertThat(wheel.handling().suspensionCompression())
                        .isCloseTo(profile.dampingCompression(), within(1e-3f));
                assertThat(wheel.handling().suspensionDamping()).isCloseTo(profile.dampingRelaxation(), within(1e-3f));
                assertThat(wheel.handling().rollInfluence()).isCloseTo(profile.rollInfluence(), within(1e-3f));
            }

            // Steering is authored on the front wheel only, because only a steering wheel
            // contributes it (D05-S5.6 phase 3 filters on isSteering).
            PartType front = wheels.get(0);
            assertThat(front.stats().resolve(Stat.MAX_STEER_RAD, VehicleStatsSystemDefaults.MAX_STEER_RAD))
                    .as("%s steering lock", profile.displayName())
                    .isCloseTo(profile.maxSteerRad(), within(1e-3f));
            assertThat(front.stats()
                            .resolve(Stat.STEER_RATE_RAD_PER_SEC, VehicleStatsSystemDefaults.STEER_RATE_RAD_PER_SEC))
                    .isCloseTo(profile.steerRateRadPerSec(), within(1e-3f));
        }
    }

    /**
     * The assembly's <em>vehicle</em> parts sum to the profile's kerb mass — the figure the real car
     * is sold on.
     *
     * <p>Vehicle parts, not every part. A kerb mass is the mass of the car, and a car with a 46 kg
     * machine gun bolted to its bonnet weighs 46 kg more than its kerb mass — correctly, and that is
     * what the physics should see. Summing the armament into this assertion would either break the
     * calibration or, worse, force the shipped weapons to weigh nothing.
     */
    @Test
    void eachAssemblyWeighsItsProfilesKerbMass() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            AssemblyDef assembly = assets.assembly(profile.profileId());
            float total = chassisOf(profile).massKg();
            for (AssemblyDef.PartPlacement placement : assembly.parts()) {
                if (isArmament(placement)) {
                    continue;
                }
                total += assets.partType(placement.partTypeId()).massKg();
            }
            assertThat(total).as("%s kerb mass", profile.displayName()).isCloseTo(profile.kerbMassKg(), within(1f));
        }
    }

    /** True for a part from the shared library rather than from this vehicle's own art (DEC-075). */
    private static boolean isArmament(AssemblyDef.PartPlacement placement) {
        return placement.partTypeId().value().startsWith("weapon_");
    }

    /** What the fitted armament adds to a vehicle, in kg. */
    private float armamentMassKg(VehicleProfile profile) {
        float total = 0f;
        for (AssemblyDef.PartPlacement placement :
                assets.assembly(profile.profileId()).parts()) {
            if (isArmament(placement)) {
                total += assets.partType(placement.partTypeId()).massKg();
            }
        }
        return total;
    }

    /** Every shipped vehicle carries a weapon, and it is not weightless (D17-S1). */
    @Test
    void everyShippedVehicleCarriesArmament() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            assertThat(armamentMassKg(profile))
                    .as("%s armament", profile.displayName())
                    .isGreaterThan(10f);
        }
    }

    /**
     * Wheel slots sit at the published wheelbase and near the profile's track.
     *
     * <p>Near, on track, and exact on wheelbase — which is not an inconsistency but the difference
     * between two things the numbers come from. A slot's X is where the art's axle is, measured off
     * the source mesh by {@code syndicate_dissect}; move it to the published figure and the wheel
     * mesh no longer sits in its arch. The art models a car 3 cm wider across the front axle than
     * the real one is sold as, which is ordinary for a licensed-look model and not something the
     * game can correct without visibly breaking the vehicle. So the published track stays here as a
     * band that would catch a wheel on the wrong side or a metre out, and the art is the authority
     * on the centimetres.
     *
     * <p>Wheelbase carries the same band, and used to carry a tighter one. The axle it is measured
     * between is now the centre of the wheel <em>part</em> rather than of the shells that voted for
     * it, which is the placement a wheel actually spins about — and on the Stampede that moved the
     * front axle by a centimetre. One authority for the axle, one tolerance for both figures.
     */
    @Test
    void chassisWheelSlotsMatchThePublishedGeometry() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            PartType chassis = chassisOf(profile);
            float frontZ = chassis.slot("wheel_fl").localTransform().position.z;
            float rearZ = chassis.slot("wheel_rl").localTransform().position.z;
            float frontHalfTrack = Math.abs(chassis.slot("wheel_fl").localTransform().position.x);
            float rearHalfTrack = Math.abs(chassis.slot("wheel_rl").localTransform().position.x);

            assertThat(frontZ - rearZ)
                    .as("%s wheelbase", profile.displayName())
                    .isCloseTo(profile.wheelbaseM(), within(ART_TRACK_TOLERANCE_M));
            assertThat(frontHalfTrack * 2f)
                    .as("%s front track", profile.displayName())
                    .isCloseTo(profile.trackFrontM(), within(ART_TRACK_TOLERANCE_M));
            assertThat(rearHalfTrack * 2f)
                    .as("%s rear track", profile.displayName())
                    .isCloseTo(profile.trackRearM(), within(ART_TRACK_TOLERANCE_M));
        }
    }

    /**
     * A wheel's slot puts its tyre on the ground, once the suspension has settled.
     *
     * <p>The check the retired content never had and that two separate bugs hid behind. A wheel
     * slot is the suspension's connection point; the wheel hangs a rest length below it and the
     * vehicle settles one static sag back up (D15-R45b). So
     * {@code slotY - (restLength - sag) - tyreRadius} is the vehicle's ride height, and it should be
     * zero, because the artist modelled the car standing on the ground.
     *
     * <p>Asserted here rather than in a physics test because it is a statement about the
     * <em>content</em>: it holds with no Bullet world, and it is the number the garage — which has
     * no world — has to reproduce to draw a car with its wheels in its arches rather than a fifth
     * of a metre up inside them.
     */
    @Test
    void everyWheelSlotStandsItsTyreOnTheGround() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            AssemblyDef assembly = assets.assembly(profile.profileId());
            PartType chassis = chassisOf(profile);
            int wheelCount = wheelsOf(profile).size();

            for (AssemblyDef.PartPlacement placement : assembly.parts()) {
                PartType wheel = assets.partType(placement.partTypeId());
                if (wheel == null || wheel.category() != PartCategory.WHEEL) {
                    continue;
                }
                SlotDefinition slot = chassis.slot(placement.parentSlotId());
                assertThat(slot).as("slot %s", placement.parentSlotId()).isNotNull();

                float stiffness =
                        wheel.stats().resolve(Stat.SUSPENSION_STIFFNESS, VehicleFactory.WHEEL_SUSPENSION_STIFFNESS);
                float axleY = slot.localTransform().position.y
                        - wheel.handling().suspensionRestLengthM()
                        + VehicleFactory.staticSagM(stiffness, wheelCount);

                Vector3 min = new Vector3();
                Vector3 max = new Vector3();
                wheel.collisionMesh().bounds(min, max);
                float radius = (max.y - min.y) * 0.5f;

                assertThat(axleY - radius)
                        .as("%s ride height at %s", profile.displayName(), placement.parentSlotId())
                        .isCloseTo(0f, within(RIDE_HEIGHT_TOLERANCE_M));
            }
        }
    }

    private static PartType chassisOf(VehicleProfile profile) {
        AssemblyDef assembly = assets.assembly(profile.profileId());
        PartType chassis = assets.partType(assembly.chassisPartTypeId());
        assertThat(chassis).as("chassis of %s", profile.displayName()).isNotNull();
        assertThat(chassis.category()).isEqualTo(PartCategory.CHASSIS);
        return chassis;
    }

    /** The four wheel part types of an assembly, front pair first, in slot-path order. */
    private static List<PartType> wheelsOf(VehicleProfile profile) {
        AssemblyDef assembly = assets.assembly(profile.profileId());
        return assembly.parts().stream()
                .map(placement -> assets.partType(placement.partTypeId()))
                .filter(type -> type != null && type.category() == PartCategory.WHEEL)
                .toList();
    }

    /**
     * Slot 6's steering defaults, mirrored so this test does not import a system.
     *
     * <p>A content test asserting against {@code VehicleStatsSystem} would fail for two unrelated
     * reasons at once — a content change and a system change — and the failure would not say which.
     */
    private static final class VehicleStatsSystemDefaults {
        static final float MAX_STEER_RAD = 0.5236f;
        static final float STEER_RATE_RAD_PER_SEC = 1.05f;
    }
}
