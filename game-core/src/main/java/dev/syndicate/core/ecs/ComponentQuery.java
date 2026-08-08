/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A component-set predicate used to define a {@link Family}
 * (docs/04_entity_component_model.md#D04-R9).
 *
 * <p>Supports {@code all}, {@code any}, and {@code exclude}. A query with no {@code all} clause is
 * rejected at construction (D04-E7): an unbounded family would iterate every entity in the world
 * each tick, which defeats the point of the design and degrades silently as entity counts grow.
 */
public final class ComponentQuery {

    private final long allMask;
    private final long anyMask;
    private final long excludeMask;
    private final String description;

    private ComponentQuery(long allMask, long anyMask, long excludeMask, String description) {
        this.allMask = allMask;
        this.anyMask = anyMask;
        this.excludeMask = excludeMask;
        this.description = description;
    }

    /** Starts a query requiring every listed component. At least one is mandatory (D04-E7). */
    @SafeVarargs
    public static Builder all(Class<? extends Component>... types) {
        if (types.length == 0) {
            throw new IllegalArgumentException("a family needs at least one all() component (D04-E7)");
        }
        return new Builder(types);
    }

    long allMask() {
        return allMask;
    }

    long anyMask() {
        return anyMask;
    }

    long excludeMask() {
        return excludeMask;
    }

    /** True when an entity's mask satisfies this query (D04-S5.7). */
    public boolean matches(long componentMask) {
        return (componentMask & allMask) == allMask
                && (anyMask == 0L || (componentMask & anyMask) != 0L)
                && (componentMask & excludeMask) == 0L;
    }

    @Override
    public String toString() {
        return description;
    }

    /** Accumulates clauses, resolving component classes to mask bits on {@link #build}. */
    public static final class Builder {

        private final List<Class<? extends Component>> all = new ArrayList<>();
        private final List<Class<? extends Component>> any = new ArrayList<>();
        private final List<Class<? extends Component>> exclude = new ArrayList<>();

        @SafeVarargs
        @SuppressWarnings("varargs")
        private Builder(Class<? extends Component>... types) {
            all.addAll(Arrays.asList(types));
        }

        /** At least one of these must be present. */
        @SafeVarargs
        @SuppressWarnings("varargs")
        public final Builder any(Class<? extends Component>... types) {
            any.addAll(Arrays.asList(types));
            return this;
        }

        /** None of these may be present. */
        @SafeVarargs
        @SuppressWarnings("varargs")
        public final Builder exclude(Class<? extends Component>... types) {
            exclude.addAll(Arrays.asList(types));
            return this;
        }

        /**
         * Resolves the clauses against a registry, registering any type not yet seen.
         *
         * <p>Registering here rather than requiring prior registration matters: a system builds its
         * families in {@code initialize()}, before any entity carrying those components exists. If
         * this only looked types up, every family would have to be created lazily on first use, and
         * a system whose components never appeared would silently never run.
         */
        public ComponentQuery build(ComponentTypeRegistry registry) {
            return new ComponentQuery(
                    maskOf(registry, all), maskOf(registry, any), maskOf(registry, exclude), describe());
        }

        private static long maskOf(ComponentTypeRegistry registry, List<Class<? extends Component>> types) {
            long mask = 0L;
            for (Class<? extends Component> type : types) {
                mask |= 1L << registry.register(type);
            }
            return mask;
        }

        private String describe() {
            StringBuilder sb = new StringBuilder("all").append(names(all));
            if (!any.isEmpty()) {
                sb.append(" any").append(names(any));
            }
            if (!exclude.isEmpty()) {
                sb.append(" exclude").append(names(exclude));
            }
            return sb.toString();
        }

        private static String names(List<Class<? extends Component>> types) {
            List<String> simple = new ArrayList<>(types.size());
            for (Class<?> type : types) {
                simple.add(type.getSimpleName());
            }
            return simple.toString();
        }
    }
}
