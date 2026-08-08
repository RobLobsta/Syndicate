/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.ComponentTypeRegistry;
import java.util.List;

/**
 * The append-only registration order for every component type in
 * docs/04_entity_component_model.md#D04-S4.3.
 *
 * <p>This is the {@code component_types.txt} of D04-R22, expressed as code so a typo is a compile
 * error rather than a runtime mismatch. The index a type receives here is intended to become its
 * wire type id, which is why the list carries three rules:
 *
 * <ol>
 *   <li><b>Append only.</b> A new type goes at the end. Inserting one renumbers every type after
 *       it, and two builds that both believe they agree would then disagree about the protocol.
 *   <li><b>Never reorder.</b> Same reason.
 *   <li><b>Never remove.</b> A retired type keeps its slot forever (D04-R26); deleting the line
 *       silently hands its id to whatever followed.
 * </ol>
 *
 * <p>Order within the list follows the D04-S4.3 tables so the two can be diffed by eye.
 * {@code RenderModelComponent} is deliberately absent: D04-S4.3.5 marks it client-only and it lives
 * in {@code game-client}, where no wire id is meaningful.
 */
public final class ComponentCatalogue {

    /**
     * Every component type, in permanent registration order.
     *
     * <p>Currently {@value #EXPECTED_SIZE} of the 64 mask bits (D04-R26), leaving headroom for the
     * types the unimplemented subsystems will add.
     */
    public static final List<Class<? extends Component>> TYPES = List.of(
            // --- D04-S4.3.1 Spatial and physics ---
            TransformComponent.class,
            RigidBodyComponent.class,
            VelocityComponent.class,
            StaticCollisionComponent.class,
            BallisticMotionComponent.class,
            // --- D04-S4.3.2 Vehicle and parts ---
            VehicleChassisComponent.class,
            SlotGraphComponent.class,
            PartRefComponent.class,
            PartStatsComponent.class,
            SlotAttachmentComponent.class,
            VehicleStatsComponent.class,
            // --- D04-S4.3.3 Health and damage ---
            HealthComponent.class,
            DamageStateComponent.class,
            DamageVisualComponent.class,
            FractureDataComponent.class,
            DebrisTagComponent.class,
            // --- D04-S4.3.4 Control, weapons, AI ---
            PlayerInputComponent.class,
            WeaponControllerComponent.class,
            WheelControllerComponent.class,
            BotControllerComponent.class,
            TeamComponent.class,
            OwnerComponent.class,
            LifetimeComponent.class,
            // --- D04-S4.3.5 Networking, match, infrastructure ---
            NetworkReplicatedComponent.class,
            InterpolationComponent.class,
            PredictionComponent.class,
            MatchStateComponent.class,
            MatchClockComponent.class,
            MatchRulesComponent.class,
            RandomSourceComponent.class,
            ScoreComponent.class);

    /**
     * How many types the catalogue holds. Asserted by a test, so appending a type without updating
     * this constant fails loudly rather than drifting.
     */
    public static final int EXPECTED_SIZE = 31;

    private ComponentCatalogue() {}

    /**
     * Registers every type in catalogue order.
     *
     * <p>Call this on a fresh registry before anything attaches a component. {@code register} is
     * idempotent, so a later call cannot renumber — but a *first* attach that happens before this
     * call would take index 0 for whatever type it happened to be.
     */
    public static void registerAll(ComponentTypeRegistry registry) {
        registry.registerAll(TYPES);
    }
}
