/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.MaterialDef;
import dev.syndicate.core.component.BurnStackComponent;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.DamageType;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The one place hit points are ever removed
 * (docs/07_damage_destruction_model.md#D07-S5.2, #D07-S5.3, #D07-S5.4).
 *
 * <p>Three algorithms that only make sense together. {@link #apply} runs a damage event through
 * material resistance, the damage type's armour formula, and the positional modifiers, then removes
 * the hit points. {@link #updateDamageState} turns the resulting health fraction into a discrete
 * state, monotonically. {@code propagate} walks the slot graph and applies an attenuated share to
 * the neighbours, through {@link #apply} again — which is why they are one class rather than three:
 * the recursion is real, and its termination argument (D07-R14, D07-R15) is a property of how the
 * three interlock.
 *
 * <p><b>Why this is not a system.</b> Damage arrives from four places — collisions (slot 11),
 * projectiles (slot 9), burn ticks, and propagation from itself — and D07-S5.9 has a client applying
 * the very same state machine to replicated health so its parts reach the states the authority's did
 * (D07-R26). A static operation over components, like {@code PartDetachment} (DEC-016), lets all of
 * them share one implementation without any of them calling a system (D04-R13).
 *
 * <p>Instance rather than static because it needs the shared scratch of a bounded BFS and a
 * {@link HitResolution} to ask for positional multipliers; it holds no state across a call.
 */
public final class DamageApplication {

    /** Hit points below which a propagated hop is not worth applying (D07-S5.4). */
    public static final float MIN_PROPAGATED_DAMAGE = 0.5f;

    /** The fraction of raw damage that survives {@code KINETIC} armour however thick (D07-R8). */
    public static final float KINETIC_FLOOR_FRAC = 0.10f;

    /** As {@link #KINETIC_FLOOR_FRAC}, for {@code COLLISION} — a lower floor, so ramming a tank hurts less. */
    public static final float COLLISION_FLOOR_FRAC = 0.05f;

    /** The floor for {@code EXPLOSIVE}, which already only meets 40% of the armour value (D07-R8). */
    public static final float EXPLOSIVE_FLOOR_FRAC = 0.10f;

    /** The floor for {@code ENERGY}, the highest of the four: a beam always gets through (D07-R8). */
    public static final float ENERGY_FLOOR_FRAC = 0.15f;

    /** Fraction of the armour value {@code EXPLOSIVE} has to overcome (D07-R8). */
    public static final float EXPLOSIVE_ARMOR_FRAC = 0.40f;

    /** Fraction of the armour value {@code ENERGY} has to overcome (D07-R8). */
    public static final float ENERGY_ARMOR_FRAC = 0.50f;

    private final AssetIndex assets;
    private final HitResolution hits;

    /** BFS scratch for {@code propagate}. Reset at the top of every walk; never read across one. */
    private final Deque<int[]> frontier = new ArrayDeque<>();

    private final Set<Integer> visited = new TreeSet<>();
    private final TreeMap<String, Integer> neighbourScratch = new TreeMap<>();

    public DamageApplication(AssetIndex assets, HitResolution hits) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.hits = Objects.requireNonNull(hits, "hits");
    }

    /**
     * Applies one damage event and everything that follows from it (D07-S5.2 {@code applyDamage}).
     *
     * @param coverage the target vehicle's coverage map, for the {@code EXPOSED} modifier; may be
     *     null for a target that is not on a vehicle
     * @param ledger the match damage ledger, or null when nothing is keeping score
     * @param friendlyFire whether the match rules let teammates hurt each other (D01-E9)
     * @return the hit points actually removed, which is what propagation and the ledger use — never
     *     the raw amount, so overkill is not carried anywhere (D07-E2)
     */
    public float apply(
            World world, DamageEvent event, CoverageMap coverage, DamageLedger ledger, boolean friendlyFire) {
        int partEntity = event.targetPart();
        if (partEntity == EntityId.NULL || !world.isAlive(partEntity)) {
            return 0f;
        }
        HealthComponent health = world.getComponent(partEntity, HealthComponent.class);
        DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
        if (health == null || damageState == null) {
            return 0f;
        }
        // D07-R12: damage to a dead part is discarded, not redirected. Redirecting would let a
        // player kill a vehicle through an already-destroyed plate, which is unreadable (P1).
        if (damageState.state == DamageState.DESTROYED || damageState.state == DamageState.DETACHED) {
            return 0f;
        }
        PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
        int vehicleEntity = partRef == null ? EntityId.NULL : partRef.vehicleEntity;
        if (!friendlyFire && sameTeam(world, event.attackerVehicle(), vehicleEntity)) {
            // D01-E9: no team damage of any type, collision included.
            return 0f;
        }

        float amount = event.baseAmount();
        if (amount <= 0f) {
            return 0f;
        }

        // 1. Material resistance, before armour (D07-R10).
        PartStatsComponent partStats = world.getComponent(partEntity, PartStatsComponent.class);
        amount *= materialResistance(partStats, event.type());

        // 2. The damage type's armour interaction (D07-S4.3).
        amount = afterArmour(amount, health.armorValue, event.type());

        // 3. Positional modifiers. Direct hits only: a propagated hop was not struck anywhere, so
        //    there is no normal to measure and no geometry to reward (D07-S5.2 step 3).
        if (!event.isPropagated() && vehicleEntity != EntityId.NULL) {
            boolean exposed = coverage != null && partRef != null && coverage.isExposed(partRef.slotPath);
            amount *= hits.positionalMultiplier(world, vehicleEntity, event.hitNormalWorld(), exposed);
        }

        // 4. Apply.
        float before = health.currentHp;
        health.setCurrentHp(before - amount);
        float applied = before - health.currentHp;
        if (applied <= 0f) {
            return 0f;
        }
        health.lastDamageTick = event.tick();
        if (event.attackerPlayer() != EntityId.NULL) {
            health.lastAttacker = event.attackerPlayer();
        }
        if (!event.isPropagated() && event.hitNormalWorld().len2() > 0f) {
            // Kept for the detach kick of D07-S5.7, two slots later. Propagated damage has no
            // geometry, so it must not overwrite the direction the real hit came from.
            health.lastHitNormalX = event.hitNormalWorld().x;
            health.lastHitNormalY = event.hitNormalWorld().y;
            health.lastHitNormalZ = event.hitNormalWorld().z;
        }
        markDirty(world, vehicleEntity);
        if (ledger != null && vehicleEntity != EntityId.NULL && event.attackerPlayer() != EntityId.NULL) {
            ledger.record(vehicleEntity, event.attackerPlayer(), applied, event.tick());
        }
        if (event.type() == DamageType.INCENDIARY && !event.isPropagated()) {
            // Direct hits only. A propagated hop is the "spreads to the same-parent part" of
            // D01-R9 — it carries damage, not fire — and a burn tick is itself delivered as a
            // propagated event, so stacking here would make one flamer touch burn forever.
            addBurnStack(world, partEntity, event.attackerPlayer());
        }

        // 5. State transition, then propagation from the amount that was actually applied.
        updateDamageState(world, partEntity, event.tick());
        if (!event.isPropagated() && event.hopCount() < SimulationConstants.PROPAGATION_MAX_DEPTH) {
            propagate(world, partEntity, applied, event, coverage, ledger, friendlyFire);
        }
        return applied;
    }

    /**
     * Drives one part through the damage state machine (D07-S5.3 {@code updateDamageState}).
     *
     * <p>Depends on {@code healthFraction} and terminal stickiness and on nothing else — not the
     * damage type, not the attacker, not elapsed time (D07-R13). That is what lets a client run this
     * same function on replicated health and reach the state the authority reached (D07-R26).
     *
     * @return the state after the call, whether or not it changed
     */
    public DamageState updateDamageState(World world, int partEntity, long tick) {
        HealthComponent health = world.getComponent(partEntity, HealthComponent.class);
        DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
        if (health == null || damageState == null) {
            return DamageState.INTACT;
        }
        DamageState old = damageState.state;
        DamageState next = stateForHealth(health.healthFraction);

        // Monotonic guard: never step back toward health even if something raises HP (G8), and
        // never leave a terminal state (G9).
        if (next.ordinal() < old.ordinal()) {
            next = old;
        }
        if (old == DamageState.DESTROYED || old == DamageState.DETACHED) {
            next = old;
        }
        if (next == old) {
            return old;
        }

        damageState.state = next;
        damageState.stateEnteredTick = tick;
        damageState.stateVersion++;

        PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
        String slotPath = partRef == null ? "" : partRef.slotPath;
        int vehicleEntity = partRef == null ? EntityId.NULL : partRef.vehicleEntity;
        world.events().emit(new DamageStateChangedEvent(partEntity, vehicleEntity, slotPath, old, next, tick));

        if (next == DamageState.DESTROYED) {
            // Emitted once and only once: the transition is terminal, so a second damage event that
            // would also have destroyed the part is discarded before it reaches here (D07-E9).
            world.events()
                    .emitPipeline(new PartDestroyedEvent(
                            partEntity,
                            vehicleEntity,
                            slotPath,
                            isChassis(world, vehicleEntity, partEntity),
                            health.lastAttacker,
                            tick));
        }
        return next;
    }

    /** The state a health fraction maps to, before the monotonic guard (D07-S5.3). */
    public static DamageState stateForHealth(float healthFraction) {
        if (healthFraction <= SimulationConstants.DAMAGE_THRESHOLD_DESTROYED) {
            return DamageState.DESTROYED;
        }
        if (healthFraction <= SimulationConstants.DAMAGE_THRESHOLD_CRITICAL) {
            return DamageState.CRITICAL;
        }
        if (healthFraction <= SimulationConstants.DAMAGE_THRESHOLD_DAMAGED) {
            return DamageState.DAMAGED;
        }
        return DamageState.INTACT;
    }

    /** The post-armour amount for a damage type (D07-S4.3, D07-S5.2 step 2). */
    public static float afterArmour(float amount, float armorValue, DamageType type) {
        float armor = Math.max(0f, armorValue);
        return switch (type) {
            case KINETIC -> Math.max(amount - armor, KINETIC_FLOOR_FRAC * amount);
            case COLLISION -> Math.max(amount - armor, COLLISION_FLOOR_FRAC * amount);
            case EXPLOSIVE -> Math.max(amount - EXPLOSIVE_ARMOR_FRAC * armor, EXPLOSIVE_FLOOR_FRAC * amount);
            case ENERGY -> Math.max(amount - ENERGY_ARMOR_FRAC * armor, ENERGY_FLOOR_FRAC * amount);
                // Incendiary ignores armour entirely; that is the reason to carry one (D07-R8).
            case INCENDIARY -> amount;
        };
    }

    // ---- Propagation (D07-S5.4) ------------------------------------------------------

    /**
     * Spreads an attenuated share of a hit to slot-graph neighbours (D07-S5.4 {@code propagate}).
     *
     * <p>A bounded breadth-first walk, never a recursion, and the single source of secondary damage:
     * every event it produces carries {@code isPropagated}, which {@link #apply} reads as "do not
     * propagate from this" (D07-R14). Without that guard a chain of forty parts is an exponential
     * cascade rather than a 20%-then-4% ripple.
     *
     * <p>The {@code visited} set is what stops a diamond in the slot graph damaging one part twice
     * for one event (D07-R15), and it is a sorted set so that two peers walk the frontier in the same
     * order (G3).
     */
    private void propagate(
            World world,
            int sourcePart,
            float appliedAmount,
            DamageEvent origin,
            CoverageMap coverage,
            DamageLedger ledger,
            boolean friendlyFire) {

        float factor = SimulationConstants.PROPAGATION_FRACTION * origin.type().propagationFactor();
        if (factor <= 0f) {
            // ENERGY is single-part by design: a beam concentrates, it does not spread (D07-R8).
            return;
        }
        PartRefComponent sourceRef = world.getComponent(sourcePart, PartRefComponent.class);
        if (sourceRef == null || sourceRef.vehicleEntity == EntityId.NULL) {
            return;
        }
        SlotGraphComponent graph = world.getComponent(sourceRef.vehicleEntity, SlotGraphComponent.class);
        VehicleChassisComponent chassis = world.getComponent(sourceRef.vehicleEntity, VehicleChassisComponent.class);
        if (graph == null || chassis == null) {
            return;
        }
        int maxHops = Math.min(
                SimulationConstants.PROPAGATION_MAX_DEPTH, origin.type().maxHops());
        if (maxHops <= 0) {
            return;
        }

        frontier.clear();
        visited.clear();
        visited.add(sourcePart);
        frontier.addLast(new int[] {sourcePart, 0});

        while (!frontier.isEmpty()) {
            int[] current = frontier.removeFirst();
            int currentPart = current[0];
            int hop = current[1];
            if (hop >= maxHops) {
                continue;
            }
            collectNeighbours(world, graph, chassis, currentPart);
            // Safe to iterate while applying: a propagated event never propagates again (D07-R14),
            // so nothing inside this loop re-enters collectNeighbours.
            for (int neighbourEntity : neighbourScratch.values()) {
                if (!visited.add(neighbourEntity)) {
                    continue;
                }
                // Attenuate by hop: 20% at hop 1 and 4% at hop 2 for KINETIC (D07-S5.4).
                float transferred = appliedAmount * (float) Math.pow(factor, hop + 1);
                if (transferred < MIN_PROPAGATED_DAMAGE) {
                    continue;
                }
                apply(
                        world,
                        origin.propagatedTo(neighbourEntity, transferred, hop + 1),
                        coverage,
                        ledger,
                        friendlyFire);
                frontier.addLast(new int[] {neighbourEntity, hop + 1});
            }
        }
        frontier.clear();
        visited.clear();
        neighbourScratch.clear();
    }

    /**
     * Fills {@link #neighbourScratch} with a part's live slot-graph neighbours — its parent and its
     * direct children — keyed by slot path so iteration is in ascending path order (G3).
     *
     * <p><b>The chassis is a neighbour like any other</b> (D07-R16), and it takes explicit handling
     * because it is not a {@link SlotNode}: it is the root of the tree rather than an edge in it, so
     * a walk over {@code graph.nodes} alone would step around it and a vehicle would never die of
     * attrition, only of direct chassis fire.
     */
    private void collectNeighbours(
            World world, SlotGraphComponent graph, VehicleChassisComponent chassis, int partEntity) {

        neighbourScratch.clear();
        for (SlotNode node : graph.nodes) {
            if (node.parentEntity == partEntity) {
                offerNeighbour(world, node.slotPath, node.childEntity);
            } else if (node.childEntity == partEntity) {
                // This part's own edge names its parent, which may be the chassis.
                int parentEntity = node.parentEntity;
                if (parentEntity == chassis.chassisPartEntity) {
                    offerNeighbour(world, SlotChain.ROOT_SLOT_PATH, parentEntity);
                } else {
                    offerNeighbour(world, SlotChain.parentPathOf(node.slotPath), parentEntity);
                }
            }
        }
    }

    /** Adds a neighbour if it is alive and still able to take damage. */
    private void offerNeighbour(World world, String slotPath, int partEntity) {
        if (partEntity == EntityId.NULL || !world.isAlive(partEntity) || isDeadOrGone(world, partEntity)) {
            return;
        }
        neighbourScratch.put(slotPath, partEntity);
    }

    private static boolean isDeadOrGone(World world, int partEntity) {
        DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
        return damageState == null
                || damageState.state == DamageState.DESTROYED
                || damageState.state == DamageState.DETACHED;
    }

    // ---- Helpers ---------------------------------------------------------------------

    private float materialResistance(PartStatsComponent partStats, DamageType type) {
        if (partStats == null || partStats.materialId == null) {
            return MaterialDef.NEUTRAL_RESISTANCE;
        }
        MaterialDef material = assets.material(partStats.materialId);
        return material == null ? MaterialDef.NEUTRAL_RESISTANCE : material.resistanceTo(type);
    }

    /** Adds a burn stack, or refreshes the oldest when the part is already at the cap (D07-R8). */
    private static void addBurnStack(World world, int partEntity, int attackerPlayer) {
        BurnStackComponent burn = world.getComponent(partEntity, BurnStackComponent.class);
        if (burn == null) {
            burn = new BurnStackComponent();
            world.addComponent(partEntity, burn);
        }
        if (burn.stackCount < BurnStackComponent.MAX_STACKS) {
            burn.remainingS[burn.stackCount++] = BurnStackComponent.BURN_DURATION_S;
        } else {
            int oldest = 0;
            for (int i = 1; i < burn.stackCount; i++) {
                if (burn.remainingS[i] < burn.remainingS[oldest]) {
                    oldest = i;
                }
            }
            burn.remainingS[oldest] = BurnStackComponent.BURN_DURATION_S;
        }
        if (attackerPlayer != EntityId.NULL) {
            burn.lastAttacker = attackerPlayer;
        }
    }

    /**
     * Flags the vehicle's aggregate as stale.
     *
     * <p>{@code VehicleStatsSystem} recomputes unconditionally (DEC-025), so this changes nothing it
     * does; replication reads the flag, and D07-S5.2 sets it, so it is set.
     */
    private static void markDirty(World world, int vehicleEntity) {
        if (vehicleEntity == EntityId.NULL) {
            return;
        }
        VehicleStatsComponent stats = world.getComponent(vehicleEntity, VehicleStatsComponent.class);
        if (stats != null) {
            stats.dirty = true;
        }
    }

    /** Whether two vehicles are on the same side. A null team on either side means "not teammates". */
    private static boolean sameTeam(World world, int attackerVehicle, int targetVehicle) {
        if (attackerVehicle == EntityId.NULL || targetVehicle == EntityId.NULL) {
            return false;
        }
        if (attackerVehicle == targetVehicle) {
            // Self-damage is not friendly fire; a vehicle that rams a wall hurts itself either way.
            return false;
        }
        TeamComponent attackerTeam = world.getComponent(attackerVehicle, TeamComponent.class);
        TeamComponent targetTeam = world.getComponent(targetVehicle, TeamComponent.class);
        if (attackerTeam == null || targetTeam == null) {
            return false;
        }
        // FREE_FOR_ALL is a distinct value rather than "team 0" precisely so that two unteamed
        // vehicles are not friendly to each other.
        return attackerTeam.teamId != TeamComponent.FREE_FOR_ALL && attackerTeam.teamId == targetTeam.teamId;
    }

    private static boolean isChassis(World world, int vehicleEntity, int partEntity) {
        if (vehicleEntity == EntityId.NULL) {
            return false;
        }
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        return chassis != null && chassis.chassisPartEntity == partEntity;
    }
}
