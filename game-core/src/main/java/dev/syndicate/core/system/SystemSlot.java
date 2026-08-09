/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.ecs.Phase;
import dev.syndicate.model.RuntimeMode;
import java.util.List;
import java.util.function.Predicate;

/**
 * The 27 systems of docs/04_entity_component_model.md#D04-S4.4, as data.
 *
 * <p>D04-R6 makes the execution order a compile-time constant list rather than something derived
 * from registration order or from dependency inference, and this enum is that list. Declaration
 * order is catalogue order, so {@code values()} <em>is</em> {@code FULL_CATALOGUE_ORDER} and the
 * subsequence assertion D03-S5.2 ends on becomes a property of how the schedule is built rather than
 * a check bolted on afterwards.
 *
 * <p>{@link #availability} is the mode filter of D03-S5.2, one branch of that pseudocode per slot.
 * A system that does not apply to a mode is <b>absent</b> from the schedule rather than present and
 * disabled (D04-R8), so a mode cannot accidentally run one — a dedicated server has no
 * {@code RenderSystem} to fail on a missing GL context, and a pure client has no {@code DamageSystem}
 * to author damage the authority never agreed to (G15).
 *
 * <p>{@link #module} records which module owns the implementation. {@code game-core} cannot
 * construct a {@code game-client} system — the dependency runs the other way (D02-S5.6) — so the
 * client slots are filled by a {@link SystemProvider} the client contributes.
 */
public enum SystemSlot {

    /** 1 — reads device state, writes {@code PlayerInputComponent}. */
    INPUT_COLLECTION(1, Phase.INPUT, Module.CLIENT, Availability.HAS_INPUT),

    /** 2 — applies input commands arriving over the transport. */
    INPUT_RECEIVE(2, Phase.INPUT, Module.CORE, Availability.AUTHORITY),

    /** 3 — bots write the same {@code PlayerInputComponent} a human's client does (G17). */
    BOT_DECISION(3, Phase.INPUT, Module.CORE, Availability.AUTHORITY),

    /** 4 — the match state machine (D11-S5.7). */
    MATCH_FLOW(4, Phase.PRE_SIM, Module.CORE, Availability.AUTHORITY),

    /** 5 — drains the spawn queue into vehicles (D05-S5.2). */
    SPAWN(5, Phase.PRE_SIM, Module.CORE, Availability.AUTHORITY),

    /** 6 — aggregates parts into vehicle stats (D05-S5.6). */
    VEHICLE_STATS(6, Phase.PRE_SIM, Module.CORE, Availability.ALL),

    /** 7 — turns input into engine force, brake and steering (D06-S5.5). */
    VEHICLE_CONTROL(7, Phase.SIM, Module.CORE, Availability.ALL),

    /** 8 — authoritative on the authority, predicted on a pure client (D03-S5.2). */
    WEAPON(8, Phase.SIM, Module.CORE, Availability.AUTHORITY_OR_PREDICTED),

    /** 9 — as {@link #WEAPON}: the client's variant spawns visuals and authors no damage (G15). */
    PROJECTILE(9, Phase.SIM, Module.CORE, Availability.AUTHORITY_OR_PREDICTED),

    /** 10 — the Bullet step (D06-S5.4). */
    PHYSICS(10, Phase.SIM, Module.CORE, Availability.ALL),

    /** 11 — turns Bullet manifolds into damage events (D07-S5.1). */
    COLLISION_EVENT(11, Phase.POST_SIM, Module.CORE, Availability.AUTHORITY),

    /** 12 — applies damage and drives the damage state machine (D07-S5.3). */
    DAMAGE(12, Phase.POST_SIM, Module.CORE, Availability.AUTHORITY),

    /** 13 — breaks a destroyed part into its shards (D07-S5.6). */
    FRACTURE(13, Phase.POST_SIM, Module.CORE, Availability.AUTHORITY),

    /** 14 — removes parts from a vehicle and lands them as debris (D07-S5.7). */
    DETACH(14, Phase.POST_SIM, Module.CORE, Availability.AUTHORITY),

    /** 15 — mass, centre of mass and inertia, in the same tick as the change (G10). */
    MASS_PROPERTY(15, Phase.POST_SIM, Module.CORE, Availability.ALL),

    /** 16 — expiry and sleep-despawn for everything transient (D07-S5.8). */
    LIFETIME(16, Phase.POST_SIM, Module.CORE, Availability.ALL),

    /** 17 — kills, damage credit and the scoreboard. */
    SCORE(17, Phase.POST_SIM, Module.CORE, Availability.AUTHORITY),

    /** 18 — snapshot send (D10-S5.3). */
    NETWORK_SEND(18, Phase.NET, Module.CORE, Availability.AUTHORITY),

    /** 19 — snapshot receive (D10-S5.4). */
    NETWORK_RECEIVE(19, Phase.NET, Module.CORE, Availability.CLIENT),

    /** 20 — rewind and replay (D10-S5.5). A no-op for a loopback peer (D03-R9). */
    RECONCILIATION(20, Phase.NET, Module.CORE, Availability.CLIENT),

    /** 21 — world matrices for the transform tree. Per tick, not per frame (D04-R7). */
    TRANSFORM(21, Phase.PRESENT, Module.CORE, Availability.ALL),

    /** 22 — render-transform interpolation between ticks (D03-S5.3). */
    INTERPOLATION(22, Phase.PRESENT, Module.CLIENT, Availability.RENDERS),

    /** 23 — morph target weights from health (D07-S5.5). Cosmetic (G6). */
    DAMAGE_VISUAL(23, Phase.PRESENT, Module.CLIENT, Availability.RENDERS),

    /** 24 — particles and decals. */
    EFFECT(24, Phase.PRESENT, Module.CLIENT, Availability.RENDERS),

    /** 25 — audio. */
    AUDIO(25, Phase.PRESENT, Module.CLIENT, Availability.RENDERS),

    /** 26 — draw calls. */
    RENDER(26, Phase.PRESENT, Module.CLIENT, Availability.RENDERS),

    /** 27 — deferred teardown, always last so nothing reads a half-destroyed entity (D04-R15). */
    ENTITY_DESTROY(27, Phase.CLEANUP, Module.CORE, Availability.ALL);

    /** Which module owns a slot's implementation (D02-S4.5). */
    public enum Module {
        /** Implemented in {@code game-core}, available in every process. */
        CORE,
        /** Implemented in {@code game-client}; {@code game-core} cannot construct it. */
        CLIENT
    }

    /** The mode filter of D03-S5.2, one constant per branch of that pseudocode. */
    public enum Availability {
        /** Present in every mode. */
        ALL(mode -> true),
        /** Present where input devices are polled. */
        HAS_INPUT(RuntimeMode::hasInput),
        /** Present where this process owns authoritative state (G1). */
        AUTHORITY(RuntimeMode::isAuthority),
        /** Present where the client half of replication runs. */
        CLIENT(RuntimeMode::isClient),
        /** Present where PRESENT-phase systems 22-26 run. */
        RENDERS(RuntimeMode::renders),
        /**
         * Present in every mode, but as two different systems: the authoritative one where
         * {@code isAuthority}, the client-side predicted variant otherwise (D03-S5.2). Which one is
         * built is a {@link SystemProvider}'s decision, not this enum's.
         */
        AUTHORITY_OR_PREDICTED(mode -> true);

        private final Predicate<RuntimeMode> presentIn;

        Availability(Predicate<RuntimeMode> presentIn) {
            this.presentIn = presentIn;
        }

        boolean test(RuntimeMode mode) {
            return presentIn.test(mode);
        }
    }

    private final int order;
    private final Phase phase;
    private final Module module;
    private final Availability availability;

    SystemSlot(int order, Phase phase, Module module, Availability availability) {
        this.order = order;
        this.phase = phase;
        this.module = module;
        this.availability = availability;
    }

    /** The slot's fixed number in D04-S4.4. Equal to {@code ordinal() + 1} by construction. */
    public int order() {
        return order;
    }

    /** The phase this slot runs in. */
    public Phase phase() {
        return phase;
    }

    /** Which module implements it. */
    public Module module() {
        return module;
    }

    /** Its mode filter. */
    public Availability availability() {
        return availability;
    }

    /** True when D03-S5.2 puts this slot in {@code mode}'s schedule. */
    public boolean isPresentIn(RuntimeMode mode) {
        return availability.test(mode);
    }

    /** The catalogue in execution order, which is declaration order (D04-R6). */
    public static List<SystemSlot> catalogue() {
        return List.of(values());
    }

    /** The slot with a given D04-S4.4 number, or null. */
    public static SystemSlot byOrder(int order) {
        SystemSlot[] values = values();
        return order >= 1 && order <= values.length ? values[order - 1] : null;
    }
}
