/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import dev.syndicate.core.util.RandomSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * The container of all entities, components, systems, and the simulation clock for one match
 * (docs/04_entity_component_model.md#D04-S4.5, #D04-S5.4).
 *
 * <p>This class owns the three mechanisms the invariants depend on:
 *
 * <ul>
 *   <li><b>Deterministic iteration</b> — families expose ids in ascending order, never hash order
 *       (G3, D04-R9).
 *   <li><b>Deferred destruction</b> — {@link #destroyEntity(int)} deactivates immediately but tears
 *       down in CLEANUP, so no system can read a half-destroyed entity, and native disposal never
 *       races Bullet's step (D04-R15, D04-E5).
 *   <li><b>Seeded randomness</b> — every gameplay draw comes from {@link #random()} (G4).
 * </ul>
 *
 * <p>The physics world, asset registry, and match singleton are attached by {@code WorldFactory}
 * (D04-S5.4); this class deliberately knows nothing about them, so it stays testable without Bullet.
 */
public final class World {

    private final ComponentTypeRegistry componentTypes = new ComponentTypeRegistry();
    private final RandomSource random;
    private final boolean authority;

    private final Entity[] entities = new Entity[EntityId.MAX_ENTITIES];
    private final int[] generations = new int[EntityId.MAX_ENTITIES];
    private final Deque<Integer> freeIndices = new ArrayDeque<>();
    private final Deque<Entity> entityPool = new ArrayDeque<>();

    private final List<Family> families = new ArrayList<>();
    private final List<EntitySystem> schedule = new ArrayList<>();
    private final EventBus events = new EventBus();

    private final int indexMin;
    private final int indexMax;
    private int nextIndex;

    private final int[] destroyQueue = new int[EntityId.MAX_ENTITIES];
    private int destroyQueueSize;

    private long currentTick;
    private float renderAlpha;
    private boolean initialized;

    /**
     * @param matchSeed the per-match seed every gameplay stream derives from (G4)
     * @param authority true when this process owns authoritative state; it selects the entity index
     *     range so authority-allocated and client-local ids can never collide (D04-R24)
     */
    public World(long matchSeed, boolean authority) {
        this.random = new RandomSource(matchSeed);
        this.authority = authority;
        this.indexMin = authority ? EntityId.AUTHORITY_INDEX_MIN : EntityId.CLIENT_LOCAL_INDEX_MIN;
        this.indexMax = authority ? EntityId.AUTHORITY_INDEX_MAX : EntityId.CLIENT_LOCAL_INDEX_MAX;
        this.nextIndex = indexMin;
    }

    // ---- Accessors -----------------------------------------------------------------

    public ComponentTypeRegistry componentTypes() {
        return componentTypes;
    }

    /** The seeded PRNG for all gameplay randomness (G4). Cosmetic randomness never uses this. */
    public RandomSource random() {
        return random;
    }

    public EventBus events() {
        return events;
    }

    public boolean isAuthority() {
        return authority;
    }

    /** The tick currently executing. The only clock a simulation system may read (G5). */
    public long currentTick() {
        return currentTick;
    }

    /**
     * How far the render frame sits between the last completed tick and the next, in {@code [0,1)}
     * (docs/03_runtime_modes.md#D03-S5.3).
     *
     * <p><b>Only a PRESENT system may read this</b> (D03-R11). A gameplay system that did would let
     * frame rate decide a simulation result, which is the whole of what G2 forbids. It is a field on
     * the world rather than the {@code dtSeconds} argument because a PRESENT system needs both
     * numbers and they are not the same one: slot 23 eases morph weights at a rate per *second* of
     * real time (D07-S5.5), while slot 22 places a body a *fraction* of a tick along its last step.
     * Passing alpha as the dt — which the single-argument {@code present} used to do — makes the
     * morph ease run at a speed decided by where in the tick the frame landed.
     */
    public float renderAlpha() {
        return renderAlpha;
    }

    /** Live entity count, excluding entities awaiting teardown. */
    public int entityCount() {
        int count = 0;
        for (Entity entity : entities) {
            if (entity != null && entity.isActive()) {
                count++;
            }
        }
        return count;
    }

    // ---- Entity lifecycle (D04-S5.1, D04-S5.5) --------------------------------------

    /**
     * Allocates an entity from this world's index range.
     *
     * <p>Free indices are recycled FIFO rather than LIFO, deliberately: it maximises the time before
     * an index is reused, which is what makes the 8-bit generation counter's wrap unreachable in
     * practice (D04-R11).
     *
     * @throws IllegalStateException when the range is exhausted — always a leak, never a reason to
     *     grow (D04-R10, D04-E3)
     */
    public Entity createEntity() {
        int index;
        if (!freeIndices.isEmpty()) {
            index = freeIndices.removeFirst();
        } else {
            if (nextIndex > indexMax) {
                throw new IllegalStateException("entity capacity exhausted in range [" + indexMin + ", " + indexMax
                        + "]; this always means a leak (D04-E3), typically debris that is not despawning");
            }
            index = nextIndex++;
        }
        return activate(index, generations[index]);
    }

    /**
     * Creates the entity at a specific index, used for the match singleton (D04-R5) and for
     * replicated spawns, where the authority dictates the index so every peer agrees (D04-R24).
     */
    public Entity createEntityWithReservedIndex(int index) {
        if (entities[index] != null) {
            throw new IllegalStateException("index " + index + " is already occupied");
        }
        freeIndices.remove(index);
        nextIndex = Math.max(nextIndex, index + 1);
        return activate(index, generations[index]);
    }

    private Entity activate(int index, int generation) {
        Entity entity = entityPool.isEmpty() ? new Entity() : entityPool.removeFirst();
        entity.initialize(EntityId.pack(index, generation));
        entities[index] = entity;
        return entity;
    }

    /**
     * True when the id addresses a live entity. A stale id — one whose index was recycled — fails
     * the generation check and reports false rather than resolving to the wrong entity (D04-E2).
     */
    public boolean isAlive(int entityId) {
        if (entityId == EntityId.NULL) {
            return false;
        }
        int index = EntityId.index(entityId);
        Entity entity = entities[index];
        return entity != null && entity.isActive() && generations[index] == EntityId.generation(entityId);
    }

    /** The entity, or null if the id is stale or destroyed. Never throws (D04-E1). */
    public Entity get(int entityId) {
        return isAlive(entityId) ? entities[EntityId.index(entityId)] : null;
    }

    /**
     * The component of the given type, or null if absent, unregistered, or the entity is gone.
     *
     * <p>Never throws (D04-E1). A type that was never registered cannot be on any entity, so it is
     * absent rather than an error — otherwise every read of an optional component would need a
     * registration guard at the call site.
     */
    public <T extends Component> T getComponent(int entityId, Class<T> type) {
        Entity entity = get(entityId);
        int typeIndex = componentTypes.indexOfOrAbsent(type);
        if (entity == null || typeIndex < 0) {
            return null;
        }
        return type.cast(entity.componentAt(typeIndex));
    }

    /** True when the entity carries a component of this type. Never throws (D04-E1). */
    public boolean hasComponent(int entityId, Class<? extends Component> type) {
        Entity entity = get(entityId);
        int typeIndex = componentTypes.indexOfOrAbsent(type);
        return entity != null && typeIndex >= 0 && (entity.componentMask() & (1L << typeIndex)) != 0L;
    }

    /**
     * Attaches a component, registering its type on first use.
     *
     * @throws IllegalStateException on a duplicate add — D04-E4 forbids an entity silently carrying
     *     two components of one type, because the loser would be invisible and unfreed
     */
    public void addComponent(int entityId, Component component) {
        Entity entity = get(entityId);
        if (entity == null) {
            throw new IllegalStateException("addComponent on dead entity " + EntityId.toString(entityId));
        }
        int typeIndex = componentTypes.register(component.getClass());
        if (entity.componentAt(typeIndex) != null) {
            throw new IllegalStateException("duplicate component "
                    + component.getClass().getSimpleName() + " on " + EntityId.toString(entityId) + " (D04-E4)");
        }
        entity.put(typeIndex, component);
        updateFamilies(entity);
    }

    /** Detaches and resets a component. A missing component is a no-op, not an error. */
    public void removeComponent(int entityId, Class<? extends Component> type) {
        Entity entity = get(entityId);
        int typeIndex = componentTypes.indexOfOrAbsent(type);
        if (entity == null || typeIndex < 0) {
            return;
        }
        Component removed = entity.take(typeIndex);
        if (removed == null) {
            return;
        }
        updateFamilies(entity);
        removed.reset();
    }

    /**
     * Marks an entity for destruction. It leaves every family immediately, but its components and
     * native resources are released in CLEANUP (D04-R15). Double-destroy is a no-op (D04-E2).
     */
    public void destroyEntity(int entityId) {
        if (!isAlive(entityId)) {
            return;
        }
        Entity entity = entities[EntityId.index(entityId)];
        entity.deactivate();
        updateFamilies(entity);
        destroyQueue[destroyQueueSize++] = entityId;
    }

    // ---- Families (D04-S5.7) --------------------------------------------------------

    /**
     * Creates a cached family. Call this once at system initialisation, never per tick: the family
     * is maintained incrementally, so building one per tick would defeat the caching entirely.
     */
    public Family family(ComponentQuery.Builder builder) {
        Family family = new Family(builder.build(componentTypes));
        for (Entity entity : entities) {
            if (entity != null) {
                family.onEntityChanged(entity);
            }
        }
        families.add(family);
        return family;
    }

    private void updateFamilies(Entity entity) {
        for (int i = 0; i < families.size(); i++) {
            families.get(i).onEntityChanged(entity);
        }
    }

    // ---- Schedule (D04-S4.4, D04-S5.3) ----------------------------------------------

    /**
     * Registers the tick schedule.
     *
     * <p>The list is sorted by {@link EntitySystem#order()}, which is the compile-time constant from
     * the D04-S4.4 catalogue — never registration order and never dependency inference (D04-R6).
     * Duplicate order numbers are rejected, because two systems claiming one slot makes the schedule
     * ambiguous and the simulation non-reproducible.
     */
    public void registerSystems(List<EntitySystem> systems) {
        if (initialized) {
            throw new IllegalStateException("systems are registered once, before the first tick");
        }
        List<EntitySystem> sorted = new ArrayList<>(systems);
        sorted.sort(Comparator.comparingInt(EntitySystem::order));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).order() == sorted.get(i - 1).order()) {
                throw new IllegalStateException("two systems claim schedule slot "
                        + sorted.get(i).order() + ": " + sorted.get(i - 1).systemName() + " and "
                        + sorted.get(i).systemName());
            }
        }
        schedule.addAll(sorted);
        for (EntitySystem system : schedule) {
            system.initialize(this);
        }
        initialized = true;
    }

    /** The registered systems, in execution order. */
    public List<EntitySystem> schedule() {
        return List.copyOf(schedule);
    }

    /**
     * Advances the simulation by exactly one fixed step (D04-S5.3).
     *
     * <p>PRESENT systems are skipped: they run once per rendered frame from the client loop
     * (D04-R7). Events queued during this tick are dispatched at its end and consumed in the next,
     * with the single documented exception of same-tick damage events (D04-R14).
     */
    public void tick(long tickNumber) {
        currentTick = tickNumber;
        for (int i = 0; i < schedule.size(); i++) {
            EntitySystem system = schedule.get(i);
            if (system.isPerFrame()) {
                continue;
            }
            system.update(this, dev.syndicate.model.SimulationConstants.TICK_DT, tickNumber);
        }
        events.dispatchQueued();
    }

    /**
     * Runs the PRESENT systems once per rendered frame.
     *
     * @param alpha the render interpolation factor in {@code [0,1)}, readable for the duration of
     *     this call through {@link #renderAlpha()}. Only presentation may read it; no gameplay
     *     system may (D03-R11), or frame rate would leak into simulation results.
     * @param frameDtSeconds the wall-clock time this frame covers, which is what a PRESENT system
     *     receives as its {@code dtSeconds}. It is real elapsed time rather than {@code TICK_DT}
     *     precisely because presentation is the one place frame rate is allowed to matter.
     */
    public void present(float alpha, float frameDtSeconds) {
        renderAlpha = alpha;
        for (int i = 0; i < schedule.size(); i++) {
            EntitySystem system = schedule.get(i);
            if (system.isPerFrame()) {
                system.update(this, frameDtSeconds, currentTick);
            }
        }
    }

    public int[] destroyQueue() {
        return destroyQueue;
    }

    public int destroyQueueSize() {
        return destroyQueueSize;
    }

    public void clearDestroyQueue() {
        destroyQueueSize = 0;
    }

    public Entity getEntityForTeardown(int entityId) {
        if (entityId == EntityId.NULL) {
            return null;
        }
        int index = EntityId.index(entityId);
        Entity entity = entities[index];
        // Must match generation, but intentionally does NOT check isActive() because the entity is in teardown
        if (entity != null && generations[index] == EntityId.generation(entityId)) {
            return entity;
        }
        return null;
    }

    public void recycleEntity(int entityId) {
        int index = EntityId.index(entityId);
        Entity entity = entities[index];
        if (entity == null || entity.id() != entityId) {
            return; // already torn down this pass; double-destroy is a no-op
        }

        for (int typeIndex = 0; typeIndex < ComponentTypeRegistry.MAX_COMPONENT_TYPES; typeIndex++) {
            Component component = entity.componentAt(typeIndex);
            if (component != null) {
                component.reset();
            }
        }

        for (int f = 0; f < families.size(); f++) {
            families.get(f).onEntityRemoved(entityId);
        }

        entity.clear();
        entities[index] = null;
        // Incrementing the generation is what invalidates every outstanding id for this index.
        generations[index] = EntityId.nextGeneration(generations[index]);
        freeIndices.addLast(index);
        entityPool.addLast(entity);
    }

    /**
     * Tears the world down in the order D03-S5.6 fixes.
     *
     * <p><b>Entities first, through the CLEANUP phase, then the systems.</b> The order matters more
     * than it looks. Native release — Bullet rigid bodies, ray-cast vehicle controllers, constraints
     * — belongs to {@code EntityDestroySystem} in slot 27 (D02-S5.7, G19), and that system is part of
     * the schedule. Disposing the schedule first and then recycling the entity records by hand, which
     * is what this method used to do, freed every entity's <em>Java</em> object and leaked every one
     * of its native ones: a match teardown left its vehicles' bodies and controllers in the physics
     * world, and the census at shutdown said so every run.
     *
     * <p>So: queue every entity for destruction, run the CLEANUP systems once to release what they
     * own, and only then dispose the schedule. Anything still queued after that had no system to
     * release it and is recycled directly, which is the same fallback as before and now the
     * exception rather than the rule.
     */
    public void dispose() {
        for (Entity entity : entities) {
            if (entity != null) {
                destroyEntity(entity.id());
            }
        }
        runCleanupPhase();

        for (int i = schedule.size() - 1; i >= 0; i--) {
            schedule.get(i).dispose();
        }
        schedule.clear();

        for (int i = 0; i < destroyQueueSize; i++) {
            recycleEntity(destroyQueue[i]);
        }
        clearDestroyQueue();
        families.forEach(Family::clear);
        families.clear();
        initialized = false;
    }

    /**
     * Runs the CLEANUP-phase systems once, outside a tick.
     *
     * <p>Repeated until the queue stops shrinking: tearing down a vehicle destroys its parts, and
     * those parts land in the same queue. Bounded by a pass count rather than by "until empty",
     * because an entity whose teardown re-creates something would otherwise spin here forever — and
     * a shutdown that hangs is worse than one that leaks.
     */
    private void runCleanupPhase() {
        for (int pass = 0; pass < MAX_CLEANUP_PASSES && destroyQueueSize > 0; pass++) {
            for (int i = 0; i < schedule.size(); i++) {
                EntitySystem system = schedule.get(i);
                if (system.phase() == Phase.CLEANUP) {
                    system.update(this, dev.syndicate.model.SimulationConstants.TICK_DT, currentTick);
                }
            }
        }
    }

    /** How many times {@link #runCleanupPhase()} will drain a queue that keeps refilling. */
    private static final int MAX_CLEANUP_PASSES = 8;

    /** Convenience for tests and factories that build a component lazily. */
    public <T extends Component> T addComponent(int entityId, Supplier<T> factory) {
        T component = factory.get();
        addComponent(entityId, component);
        return component;
    }
}
