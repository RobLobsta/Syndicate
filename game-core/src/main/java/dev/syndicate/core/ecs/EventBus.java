/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Deferred, in-order event delivery between systems (docs/04_entity_component_model.md#D04-S5.3).
 *
 * <p>Systems communicate only through components and this bus (D04-R13). Events emitted during tick
 * <i>N</i> are dispatched at the end of <i>N</i> and consumed in <i>N+1</i>, so a system can never
 * observe the output of a system that runs later in the same tick — which would make the schedule
 * stop describing causality.
 *
 * <p>The one documented exception is damage: {@code CollisionEventSystem} (11) emits and
 * {@code DamageSystem} (12) consumes within the same tick, via {@link #emitSameTick} (D04-R14).
 * That exception is explicit and is the only one.
 *
 * <p>Listener registration order is preserved, so dispatch order is deterministic (G3).
 */
public final class EventBus {

    private final Map<Class<?>, List<Consumer<?>>> listeners = new LinkedHashMap<>();
    private final List<Object> queued = new ArrayList<>();
    private final List<Object> dispatching = new ArrayList<>();
    private final Map<Class<?>, List<Object>> sameTick = new LinkedHashMap<>();

    /** Registers a listener. Registration order fixes dispatch order for that type. */
    public <T> void subscribe(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, key -> new ArrayList<>()).add(listener);
    }

    /** Queues an event for delivery at the end of the current tick. */
    public void emit(Object event) {
        queued.add(event);
    }

    /**
     * Publishes an event consumable within the same tick by a later system.
     *
     * <p>Reserved for the damage pipeline (D04-R14). Using it elsewhere reintroduces exactly the
     * ordering coupling the deferred bus exists to prevent.
     */
    public void emitSameTick(Object event) {
        sameTick.computeIfAbsent(event.getClass(), key -> new ArrayList<>()).add(event);
    }

    /** Drains the same-tick queue for a type. The caller consumes them; they are not redelivered. */
    @SuppressWarnings("unchecked")
    public <T> List<T> drainSameTick(Class<T> eventType) {
        List<Object> events = sameTick.remove(eventType);
        return events == null ? List.of() : (List<T>) List.copyOf(events);
    }

    /**
     * Delivers everything queued this tick. Events emitted by a listener are queued for the next
     * dispatch rather than appended to this one, so a feedback loop cannot stall a tick.
     */
    @SuppressWarnings("unchecked")
    public void dispatchQueued() {
        if (queued.isEmpty()) {
            return;
        }
        dispatching.addAll(queued);
        queued.clear();
        for (int i = 0; i < dispatching.size(); i++) {
            Object event = dispatching.get(i);
            List<Consumer<?>> forType = listeners.get(event.getClass());
            if (forType == null) {
                continue;
            }
            for (int j = 0; j < forType.size(); j++) {
                ((Consumer<Object>) forType.get(j)).accept(event);
            }
        }
        dispatching.clear();
    }

    /** True when nothing is waiting. Used by the tick loop's post-conditions. */
    public boolean isEmpty() {
        return queued.isEmpty() && sameTick.isEmpty();
    }

    /** Drops every queued event and listener. Called on world teardown. */
    public void clear() {
        queued.clear();
        dispatching.clear();
        sameTick.clear();
        listeners.clear();
    }
}
