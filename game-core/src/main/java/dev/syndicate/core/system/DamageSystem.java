/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.component.BurnStackComponent;
import dev.syndicate.core.component.DamageLedgerComponent;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.damage.CoverageMap;
import dev.syndicate.core.damage.DamageApplication;
import dev.syndicate.core.damage.DamageEvent;
import dev.syndicate.core.damage.DamageLedger;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.DamageType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Schedule slot 12: applies every damage event the tick produced
 * (docs/04_entity_component_model.md#D04-S4.4, docs/07_damage_destruction_model.md#D07-S5.2).
 *
 * <p>Authority only. This is where health actually falls, where a part crosses into
 * {@code DAMAGED}, {@code CRITICAL} or {@code DESTROYED}, and where the neighbours of a hit take
 * their share (D07-S5.4). The arithmetic itself lives in {@link DamageApplication}, because damage
 * arrives from three systems and a client replays the same state machine on replicated health
 * (D07-R26); this system is the schedule slot, the ordering, and the burn timer.
 *
 * <p><b>Ordering is the part that has to be deliberate.</b> Events arrive from
 * {@code ProjectileSystem} (9) and {@code CollisionEventSystem} (11) in whatever order those
 * produced them, and two damage events that both destroy the same part have to resolve the same way
 * on every peer: the first sets {@code DESTROYED}, the second is discarded (D07-E9). So the drained
 * events are sorted by target, then attacker, then type before any of them is applied (G3).
 *
 * <p><b>Where it sits.</b> Between {@code CollisionEventSystem} (11), which feeds it, and
 * {@code FractureSystem} (13) and {@code DetachSystem} (14), which react to the destroyed parts it
 * produces — all inside one tick, so a part destroyed by a hit has broken apart and changed the
 * vehicle's mass before the next physics step (G10, AC-D07-14).
 *
 * <p><b>Burn stacks tick here too.</b> {@code INCENDIARY} is the one damage type that keeps working
 * after the hit (D07-R8), and its per-second damage is delivered as ordinary damage events so that
 * armour, states, scoring and the ledger all see it the same way they see everything else.
 */
public final class DamageSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 12;

    private final AssetIndex assets;
    private final DamageApplication damage;

    private Family burning;

    private final CoverageMap coverage = new CoverageMap();
    private final Vector3 noNormal = new Vector3();

    public DamageSystem(AssetIndex assets, DamageApplication damage) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.damage = Objects.requireNonNull(damage, "damage");
    }

    @Override
    public Phase phase() {
        return Phase.POST_SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        burning = world.family(ComponentQuery.all(BurnStackComponent.class, HealthComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        boolean friendlyFire = friendlyFire(world);
        DamageLedger ledger = ledger(world);
        applyBurnStacks(world, dtSeconds, tick, friendlyFire, ledger);
        applyQueuedEvents(world, tick, friendlyFire, ledger);
    }

    // ---- The event queue -------------------------------------------------------------

    /**
     * Applies this tick's damage events in a deterministic order.
     *
     * <p>Drained rather than subscribed: a subscriber would run at whatever point in the tick the
     * emitting system chose, which for slot 9's projectiles is three slots before this one — and
     * damage applied during the SIM phase would change a vehicle's mass in the middle of the physics
     * step that is reading it.
     */
    private void applyQueuedEvents(World world, long tick, boolean friendlyFire, DamageLedger ledger) {
        // Copied because drainSameTick hands back an immutable view and this is about to be sorted.
        List<DamageEvent> events = new ArrayList<>(world.events().drainSameTick(DamageEvent.class));
        if (events.isEmpty()) {
            return;
        }
        events.sort(Comparator.comparingInt(DamageEvent::targetPart)
                .thenComparingInt(DamageEvent::attackerPlayer)
                .thenComparing(DamageEvent::type)
                .thenComparing(DamageEvent::baseAmount));
        for (DamageEvent event : events) {
            // Rebuilt per event rather than per vehicle: an earlier event in this same list can
            // destroy the plate that was covering the next event's target, and a map cached across
            // that would still be intercepting hits with armour that no longer exists.
            coverage.rebuild(world, assets, vehicleOf(world, event.targetPart()));
            damage.apply(world, event, coverage, ledger, friendlyFire);
        }
    }

    // ---- Burn stacks (D07-R8) --------------------------------------------------------

    /**
     * Advances every burning part's stacks and applies their damage.
     *
     * <p>Each stack expires on its own timer, so a part that took five stacks in one flamer burst
     * stops burning five stacks' worth all at once rather than tapering, which is what makes
     * sustained contact worth more than a touch (see {@code BurnStackComponent}).
     *
     * <p>The damage is delivered as a <em>propagated</em> event: it carries no geometry, so it earns
     * no positional modifiers, and it must not spread further or add a stack of its own — three
     * properties {@code isPropagated} already means (D07-S5.4).
     */
    private void applyBurnStacks(World world, float dtSeconds, long tick, boolean friendlyFire, DamageLedger ledger) {
        int count = burning.size();
        int[] entityIds = burning.snapshot();
        for (int i = 0; i < count; i++) {
            int partEntity = entityIds[i];
            if (!world.isAlive(partEntity)) {
                continue;
            }
            BurnStackComponent burn = world.getComponent(partEntity, BurnStackComponent.class);
            if (burn == null || burn.stackCount == 0) {
                continue;
            }
            if (isDeadOrGone(world, partEntity)) {
                // D07-E16: a detached part is debris and takes no damage, so the fire goes out.
                world.removeComponent(partEntity, BurnStackComponent.class);
                continue;
            }
            int live = 0;
            for (int s = 0; s < burn.stackCount; s++) {
                float remaining = burn.remainingS[s] - dtSeconds;
                if (remaining > 0f) {
                    burn.remainingS[live++] = remaining;
                }
            }
            for (int s = live; s < burn.stackCount; s++) {
                burn.remainingS[s] = 0f;
            }
            burn.stackCount = live;
            if (live == 0) {
                world.removeComponent(partEntity, BurnStackComponent.class);
                continue;
            }
            float burnDamage = live * BurnStackComponent.BURN_DAMAGE_PER_SECOND * dtSeconds;
            int vehicleEntity = vehicleOf(world, partEntity);
            coverage.rebuild(world, assets, vehicleEntity);
            damage.apply(
                    world,
                    new DamageEvent(
                            partEntity,
                            EntityId.NULL,
                            burn.lastAttacker,
                            DamageType.INCENDIARY,
                            burnDamage,
                            noNormal,
                            noNormal,
                            tick,
                            DamageEvent.NO_WEAPON_GROUP,
                            true,
                            0),
                    coverage,
                    ledger,
                    friendlyFire);
        }
    }

    // ---- Match context ---------------------------------------------------------------

    /**
     * Whether teammates can hurt each other (D01-E9).
     *
     * <p>Defaults to true when there is no match singleton: a test world with two vehicles and no
     * match is not a team game, and suppressing damage in it would silently make every damage test
     * pass by dealing none.
     */
    private static boolean friendlyFire(World world) {
        MatchRulesComponent rules = world.getComponent(EntityId.MATCH, MatchRulesComponent.class);
        return rules == null || rules.friendlyFire;
    }

    /** The match damage ledger, or null when nothing is keeping score. */
    private static DamageLedger ledger(World world) {
        DamageLedgerComponent component = world.getComponent(EntityId.MATCH, DamageLedgerComponent.class);
        return component == null ? null : component.ledger;
    }

    private static int vehicleOf(World world, int partEntity) {
        PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
        return partRef == null ? EntityId.NULL : partRef.vehicleEntity;
    }

    private static boolean isDeadOrGone(World world, int partEntity) {
        DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
        return damageState == null
                || damageState.state == DamageState.DESTROYED
                || damageState.state == DamageState.DETACHED;
    }
}
