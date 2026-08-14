/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.HandlingBlock;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.WeaponControllerComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.Degradation;
import dev.syndicate.core.vehicle.DegradationRule;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.core.vehicle.StatBlock.Stat;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.Map;
import java.util.Objects;

/**
 * Schedule slot 6: sums a vehicle's parts into the numbers that describe the whole vehicle
 * (docs/04_entity_component_model.md#D04-S4.4, docs/05_vehicle_part_system.md#D05-S5.6).
 *
 * <p>Four phases, in the order D05-S5.6 fixes them: each part's stats after the degradation curve
 * (D05-S5.4); the utility multipliers that modify <em>other</em> parts; the vehicle-scope sums and
 * means; and finally the derived figures — acceleration and top speed — that D05-R16 forbids
 * content from authoring, because a content-declared top speed can contradict the physics that has
 * to deliver it.
 *
 * <p><b>Purity is the whole design</b> (D05-R27). The recompute reads part stats, the slot graph and
 * the total mass, and nothing else — never the previous {@code VehicleStats}. So it cannot drift,
 * cannot accumulate float error across a match, and produces bit-identical output from identical
 * input, which is what makes AC-D05-15 and a client's rewind-and-replay agree with the authority.
 *
 * <p><b>Why it recomputes every tick.</b> D05-S5.6 words the trigger as "when {@code dirty} or
 * {@code structuralVersion} changed". Remembering a version across ticks would be exactly the ad-hoc
 * system field D04-R3 prohibits, and the alternative — trusting a flag another system remembers to
 * set — makes a missed flag into a vehicle that drives on the stats it had before it lost its engine
 * mounting. Because the recompute is pure, running it when nothing changed costs work and changes
 * nothing, while skipping it when something did is a bug; the cheaper failure is chosen (DEC-025).
 * {@code dirty} is still cleared, because replication reads it.
 *
 * <p>It runs in PRE_SIM, ahead of {@code VehicleControlSystem} (7) in the same tick, so a vehicle is
 * driven on this tick's degradation rather than last tick's.
 */
public final class VehicleStatsSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 6;

    /**
     * Radians. The steering lock a wheel has unless its part type says otherwise — 30°.
     *
     * <p>D05-S4.5 makes {@code maxSteerRad} a wheel-contributed, vehicle-scope stat but authors no
     * value, and D06-S4.5's tuning table has no entry for it either. Thirty degrees is a road car's
     * lock; more turns an arcade vehicle into one that spins on the spot at speed, which reads as
     * broken rather than as agile (DEC-027). Content overrides it through the stat block, so a
     * nimble buggy is authored rather than compiled in.
     */
    public static final float DEFAULT_MAX_STEER_RAD = 0.5236f;

    /**
     * Radians per second. How fast steering moves toward full lock — about half a second from centre
     * to {@link #DEFAULT_MAX_STEER_RAD}, which is what stops a digital key press from snapping the
     * wheels over and flipping the vehicle (DEC-027).
     */
    public static final float DEFAULT_STEER_RATE_RAD_PER_SEC = 1.05f;

    /**
     * Newtons per (m/s)². Aerodynamic drag on the chassis, {@code k_drag} in D05-S5.6 phase 4.
     *
     * <p>D05-S5.6 takes this "from the chassis part", but no part schema field carries it (D08-R5)
     * and no other document names one, so it is a constant with a named default (DEC-027). Twelve
     * puts a 20 kN chassis's top speed at roughly 40 m/s — the speed D06-S5.5's anti-tunnelling
     * clamp is set at, so the drag curve and the clamp agree instead of the clamp being what a
     * vehicle actually drives against.
     */
    public static final float CHASSIS_DRAG_COEFFICIENT = HandlingBlock.REFERENCE_DRAG_COEFFICIENT;

    /** Dimensionless. Rolling resistance, {@code k_roll} in D05-S5.6 phase 4; a road tyre's figure. */
    public static final float CHASSIS_ROLLING_RESISTANCE = HandlingBlock.REFERENCE_ROLLING_RESISTANCE;

    /**
     * The {@code max(kDrag, 1e-4)} of D05-S5.6 phase 4. A zero drag coefficient is arithmetic that
     * yields an infinite top speed, which propagates into the HUD and into bot threat assessment.
     */
    public static final float MIN_DRAG_COEFFICIENT = 1e-4f;

    private final AssetIndex assets;

    private Family vehicles;

    /**
     * The product of every live utility's multipliers, rebuilt per vehicle (D05-S5.6 phase 2).
     *
     * <p>Scratch, not state: it is reset at the top of every {@link #recompute} and never read
     * across a tick boundary, so it does not make this system stateful in the D04-R3 sense.
     */
    private final StatBlock utilityMultipliers = new StatBlock();

    public VehicleStatsSystem(AssetIndex assets) {
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    @Override
    public Phase phase() {
        return Phase.PRE_SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        vehicles = world.family(ComponentQuery.all(
                VehicleChassisComponent.class, SlotGraphComponent.class, VehicleStatsComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        int count = vehicles.size();
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < count; i++) {
            recompute(world, entityIds[i]);
        }
    }

    /**
     * The aggregation of D05-S5.6 for one vehicle.
     *
     * <p>Public so a test can assert AC-D05-15 — that recomputing twice from the same state yields
     * identical stats — without stepping a whole world to do it. It is not a system-to-system entry
     * point: D04-R13 prohibits another system calling this, and nothing does.
     */
    public void recompute(World world, int vehicleEntity) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        VehicleStatsComponent stats = world.getComponent(vehicleEntity, VehicleStatsComponent.class);
        if (chassis == null || graph == null || stats == null) {
            return;
        }
        SlotChain chain = SlotChain.of(graph, chassis);

        recomputePartEffectiveStats(world, chain);
        collectUtilityMultipliers(world, chain);
        applyUtilityMultipliers(world, chain);
        aggregate(world, chain, chassis, stats);
        HandlingBlock handling = chassisHandlingOf(world, chassis);
        stats.downforceCoefficient = handling.downforceCoefficient();
        deriveFromMass(chassis, stats, handling);
        stats.powerBudget = powerBudgetOf(world, chain);
        stats.dirty = false;
    }

    // ---- Phase 1: per-part effective stats (D05-S5.4) --------------------------------

    /**
     * Applies the degradation curve to every part, and to the scalars a part keeps outside its stat
     * block.
     *
     * <p>A destroyed or detached part is reset to identity rather than degraded to its floor: the
     * floor is what remains just <em>before</em> destruction, and at zero health the part
     * contributes nothing at all (D05-R28, D05-E9). Setting health back above zero afterwards does
     * not revive the contribution, because the reset keys on the damage state rather than on the
     * health value (T-D05-20).
     */
    private void recomputePartEffectiveStats(World world, SlotChain chain) {
        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            int partEntity = entry.getValue();
            PartStatsComponent partStats = world.getComponent(partEntity, PartStatsComponent.class);
            if (partStats == null) {
                continue;
            }
            Map<Stat, DegradationRule> overrides = overridesOf(world, partEntity);
            float health = healthFractionOf(world, partEntity);

            if (isGone(world, partEntity)) {
                partStats.effectiveStats.reset();
                zeroPartScalars(world, partEntity, overrides);
                continue;
            }
            Degradation.degrade(partStats.baseStats, partStats.effectiveStats, partStats.category, health, overrides);
            degradePartScalars(world, partEntity, partStats.category, health, overrides);
        }
    }

    /**
     * Degrades the per-part scalars D04-S4.4 row 6 names as this system's output:
     * {@code WheelController.effective*} and {@code WeaponController.effective*}.
     *
     * <p>These do not come out of the stat block. Both are resolved at spawn against an engine
     * default that content only adjusts — {@code WHEEL_FRICTION_SLIP} and the rest (DEC-022) — so
     * the authored {@code add} term is not the whole base and degrading the block would leave the
     * default undegraded. Degrading the stored scalar is also the form T-D05-7 and T-D05-8 state the
     * expected values in.
     */
    private void degradePartScalars(
            World world, int partEntity, PartCategory category, float health, Map<Stat, DegradationRule> overrides) {

        WheelControllerComponent wheel = world.getComponent(partEntity, WheelControllerComponent.class);
        if (wheel != null) {
            wheel.effectiveFrictionSlip =
                    Degradation.degradeScalar(category, Stat.FRICTION_SLIP, wheel.frictionSlip, health, overrides);
        }
        WeaponControllerComponent weapon = world.getComponent(partEntity, WeaponControllerComponent.class);
        if (weapon != null) {
            weapon.effectiveFireIntervalS = Degradation.degradeScalar(
                    category, Stat.FIRE_INTERVAL_S, weapon.baseFireIntervalS, health, overrides);
        }
    }

    /**
     * The same scalars for a part that is destroyed or detached.
     *
     * <p>The two are deliberately not treated alike. A destroyed wheel has no grip, and zero is both
     * the true answer and a usable one. A destroyed weapon has no fire <em>rate</em>, and a zero
     * interval means an infinite one — so its interval is held at the curve's zero-health value
     * instead, and whether it may fire at all is a question {@code WeaponSystem} (8) answers from
     * the damage state, not from a sentinel hidden in an interval.
     */
    private void zeroPartScalars(World world, int partEntity, Map<Stat, DegradationRule> overrides) {
        WheelControllerComponent wheel = world.getComponent(partEntity, WheelControllerComponent.class);
        if (wheel != null) {
            wheel.effectiveFrictionSlip = 0f;
        }
        WeaponControllerComponent weapon = world.getComponent(partEntity, WeaponControllerComponent.class);
        if (weapon != null) {
            weapon.effectiveFireIntervalS = Degradation.degradeScalar(
                    PartCategory.WEAPON, Stat.FIRE_INTERVAL_S, weapon.baseFireIntervalS, 0f, overrides);
        }
    }

    // ---- Phase 2: utility multipliers (D05-S5.6) -------------------------------------

    /**
     * Multiplies together every live utility's multipliers.
     *
     * <p>Utility effects are multiplicative and commutative, so the order among them cannot change
     * the answer — but the iteration is still by ascending slot path, because float multiplication
     * is not associative and an order that varied between peers would put two clients a few ulps
     * apart on a value the authority replicates (G3).
     */
    private void collectUtilityMultipliers(World world, SlotChain chain) {
        utilityMultipliers.reset();
        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            int partEntity = entry.getValue();
            PartStatsComponent partStats = world.getComponent(partEntity, PartStatsComponent.class);
            if (partStats == null || partStats.category != PartCategory.UTILITY || isGone(world, partEntity)) {
                continue;
            }
            for (int i = 0; i < Stat.COUNT; i++) {
                Stat stat = Stat.at(i);
                utilityMultipliers.setMul(stat, utilityMultipliers.mul(stat) * partStats.effectiveStats.mul(stat));
            }
        }
    }

    /** Folds the collected multipliers into every part's effective stats (D05-S5.6 phase 2). */
    private void applyUtilityMultipliers(World world, SlotChain chain) {
        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            int partEntity = entry.getValue();
            PartStatsComponent partStats = world.getComponent(partEntity, PartStatsComponent.class);
            if (partStats == null || isGone(world, partEntity)) {
                continue;
            }
            for (int i = 0; i < Stat.COUNT; i++) {
                Stat stat = Stat.at(i);
                partStats.effectiveStats.setMul(
                        stat, partStats.effectiveStats.mul(stat) * utilityMultipliers.mul(stat));
            }
        }
        applyUtilityMultipliersToScalars(world, chain);
    }

    /**
     * The scalar half of phase 2: a buffed weapon's fire interval and a buffed wheel's grip.
     *
     * <p>Without this an {@code ammo_feed} would change the numbers on the stat block and nothing
     * the game reads, and T-D05-16 — destroying the feed returns fire intervals to their un-buffed
     * values — would pass vacuously.
     */
    private void applyUtilityMultipliersToScalars(World world, SlotChain chain) {
        float frictionMul = utilityMultipliers.mul(Stat.FRICTION_SLIP);
        float intervalMul = utilityMultipliers.mul(Stat.FIRE_INTERVAL_S);
        if (frictionMul == 1f && intervalMul == 1f) {
            return;
        }
        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            int partEntity = entry.getValue();
            if (isGone(world, partEntity)) {
                continue;
            }
            WheelControllerComponent wheel = world.getComponent(partEntity, WheelControllerComponent.class);
            if (wheel != null) {
                wheel.effectiveFrictionSlip *= frictionMul;
            }
            WeaponControllerComponent weapon = world.getComponent(partEntity, WeaponControllerComponent.class);
            if (weapon != null) {
                weapon.effectiveFireIntervalS *= intervalMul;
            }
        }
    }

    // ---- Phase 3: vehicle-scope aggregation (D05-S5.6) -------------------------------

    private void aggregate(World world, SlotChain chain, VehicleChassisComponent chassis, VehicleStatsComponent stats) {

        float engineForceN = 0f;
        float enginePowerW = 0f;
        float brakeForceN = 0f;
        float steerSum = 0f;
        float steerRateSum = 0f;
        int steerWheels = 0;
        float armorSum = 0f;
        int armorParts = 0;

        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            int partEntity = entry.getValue();
            PartStatsComponent partStats = world.getComponent(partEntity, PartStatsComponent.class);
            if (partStats == null || isGone(world, partEntity)) {
                // R28: a destroyed or detached part contributes exactly zero, never a residual.
                continue;
            }
            engineForceN += partStats.effectiveStats.resolve(Stat.ENGINE_FORCE_N, 0f);
            enginePowerW += partStats.effectiveStats.resolve(Stat.ENGINE_POWER_W, 0f);
            brakeForceN += partStats.effectiveStats.resolve(Stat.BRAKE_FORCE_N, 0f);

            WheelControllerComponent wheel = world.getComponent(partEntity, WheelControllerComponent.class);
            if (wheel != null && wheel.isSteering) {
                // Steering has an engine default like grip does, so it degrades from the resolved
                // base rather than from the block's add term alone — the same reason as
                // degradePartScalars.
                float health = healthFractionOf(world, partEntity);
                Map<Stat, DegradationRule> overrides = overridesOf(world, partEntity);
                steerSum += Degradation.degradeScalar(
                                partStats.category,
                                Stat.MAX_STEER_RAD,
                                partStats.baseStats.resolve(Stat.MAX_STEER_RAD, DEFAULT_MAX_STEER_RAD),
                                health,
                                overrides)
                        * utilityMultipliers.mul(Stat.MAX_STEER_RAD);
                steerRateSum += Degradation.degradeScalar(
                                partStats.category,
                                Stat.STEER_RATE_RAD_PER_SEC,
                                partStats.baseStats.resolve(
                                        Stat.STEER_RATE_RAD_PER_SEC, DEFAULT_STEER_RATE_RAD_PER_SEC),
                                health,
                                overrides)
                        * utilityMultipliers.mul(Stat.STEER_RATE_RAD_PER_SEC);
                steerWheels++;
            }

            if (partStats.category == PartCategory.PANEL) {
                HealthComponent health = world.getComponent(partEntity, HealthComponent.class);
                float baseArmor =
                        partStats.baseStats.resolve(Stat.ARMOR_VALUE, health == null ? 0f : health.armorValue);
                armorSum += Degradation.degradeScalar(
                        partStats.category,
                        Stat.ARMOR_VALUE,
                        baseArmor,
                        healthFractionOf(world, partEntity),
                        overridesOf(world, partEntity));
                armorParts++;
            }
        }

        stats.engineForceN = engineForceN;
        stats.enginePowerW = enginePowerW;
        stats.brakeForceN = brakeForceN;
        // D05-E1 and D05-E12: no live steering wheel and no live armour are both ordinary states of
        // a vehicle late in a fight. Both are answered with an explicit zero rather than a sum over
        // an empty set, which is where the NaN would come from.
        stats.maxSteerRad = steerWheels == 0 ? 0f : steerSum / steerWheels;
        stats.steerRateRadPerSec = steerWheels == 0 ? 0f : steerRateSum / steerWheels;
        stats.armorRatingAvg = armorParts == 0 ? 0f : armorSum / armorParts;
    }

    // ---- Phase 4: derived stats (D05-R16) --------------------------------------------

    /**
     * Acceleration and top speed, which content may never author (D05-R16).
     *
     * <p>Top speed is where the engine's tractive force balances drag and rolling resistance. Which
     * force that is depends on speed: a vehicle is traction-limited at a standstill and power-limited
     * once {@code enginePowerW / v} falls below {@code engineForceN} (DEC-032). Both cases are solved
     * by bisection on {@code F(v) = k_drag·v² + k_roll·m·g}, which is monotonic in {@code v}, so
     * forty halvings put the answer well inside a metre per second and no discriminant has to be
     * checked.
     *
     * <p>The result is then clamped to the arena's own limit. A vehicle derived from a real road car
     * has a top speed near 340 km/h and will never see it — {@code MAX_VEHICLE_SPEED_MPS} caps every
     * vehicle at 40 m/s (D06-S5.5) — so reporting the unclamped figure would put a number on the HUD
     * that the game cannot deliver.
     */
    private void deriveFromMass(VehicleChassisComponent chassis, VehicleStatsComponent stats, HandlingBlock handling) {
        // D05-R29: the mass is asserted above MIN_BODY_MASS_KG at spawn and at every detach, so the
        // division cannot be by zero. The guard is what keeps that true if it ever stops being.
        float massKg = Math.max(chassis.totalMassKg, SimulationConstants.MIN_BODY_MASS_KG);
        stats.accelerationMps2 = stats.engineForceN / massKg;
        stats.maxSpeedMps = Math.min(
                topSpeedFor(stats.engineForceN, stats.enginePowerW, massKg, handling),
                VehicleControlSystem.MAX_VEHICLE_SPEED_MPS);
    }

    /** The speed at which available tractive force equals drag plus rolling resistance. */
    static float topSpeedFor(float engineForceN, float enginePowerW, float massKg, HandlingBlock handling) {
        float gravity = Math.abs(SimulationConstants.WORLD_GRAVITY_Y);
        float rolling = handling.rollingResistance() * massKg * gravity;
        float drag = Math.max(handling.dragCoefficient(), MIN_DRAG_COEFFICIENT);
        if (engineForceN <= rolling) {
            // An engine that cannot overcome its own rolling resistance never moves. Zero rather
            // than the square root of a negative number (D05-E12's spirit, one field over).
            return 0f;
        }
        float low = 0f;
        float high = engineForceN / drag + 1f;
        for (int i = 0; i < 40; i++) {
            float mid = (low + high) * 0.5f;
            float available = enginePowerW > 0f ? Math.min(engineForceN, enginePowerW / mid) : engineForceN;
            if (available > drag * mid * mid + rolling) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5f;
    }

    /**
     * The chassis part's handling block, or D06-S4.5's reference when the type is not loaded.
     *
     * <p>D05-S5.6 phase 4 takes drag and rolling resistance "from the chassis part", which before
     * DEC-031 nothing could express. A vehicle whose chassis type failed to load still needs to
     * derive a speed, so the reference stands in rather than the whole aggregation failing.
     */
    private HandlingBlock chassisHandlingOf(World world, VehicleChassisComponent chassis) {
        PartType type = partTypeOf(world, chassis.chassisPartEntity);
        return type == null ? HandlingBlock.REFERENCE : type.handling();
    }

    /**
     * The vehicle's balance budget: the sum of its live parts' authored power costs (D05-S5.7).
     *
     * <p>D05-S5.7 sums over an assembly, which is the build-time reading that AC-D05-18 checks.
     * Summing over the live parts instead is the same number for an undamaged vehicle and follows
     * R28 once it is damaged, which is what a HUD or a bot's threat estimate wants from a vehicle
     * that has lost half its guns (DEC-025).
     */
    private float powerBudgetOf(World world, SlotChain chain) {
        float budget = 0f;
        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            int partEntity = entry.getValue();
            if (isGone(world, partEntity)) {
                continue;
            }
            PartType type = partTypeOf(world, partEntity);
            if (type != null) {
                budget += type.powerCost();
            }
        }
        return budget;
    }

    // ---- Shared lookups ---------------------------------------------------------------

    /** True when a part is destroyed or detached, and so contributes nothing (D05-R28). */
    private static boolean isGone(World world, int partEntity) {
        DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
        if (damageState == null) {
            return false;
        }
        return damageState.state == DamageState.DESTROYED || damageState.state == DamageState.DETACHED;
    }

    private static float healthFractionOf(World world, int partEntity) {
        HealthComponent health = world.getComponent(partEntity, HealthComponent.class);
        // A part with no health component cannot be damaged, so it is at full health by definition.
        return health == null ? 1f : health.healthFraction;
    }

    private PartType partTypeOf(World world, int partEntity) {
        PartRefComponent ref = world.getComponent(partEntity, PartRefComponent.class);
        return ref == null || ref.partTypeId == null ? null : assets.partType(ref.partTypeId);
    }

    /**
     * A part type's authored degradation overrides, or an empty map.
     *
     * <p>Empty rather than a throw when the type is not loaded: the same reasoning as
     * {@link AssetIndex#partType} — a part whose type failed to load still has to degrade, and the
     * table is a complete answer without the overrides.
     */
    private Map<Stat, DegradationRule> overridesOf(World world, int partEntity) {
        PartType type = partTypeOf(world, partEntity);
        return type == null ? Map.of() : type.degradationOverrides();
    }
}
