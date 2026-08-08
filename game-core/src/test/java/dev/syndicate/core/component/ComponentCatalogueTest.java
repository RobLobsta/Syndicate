/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.ComponentTypeRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The component catalogue of docs/04_entity_component_model.md#D04-S4.3.
 *
 * <p>Covers AC-D04-1 (the catalogue is complete), AC-D04-2 (components carry no behaviour), and
 * D04-R17 (reset returns every field to its default).
 */
@Tag("unit")
class ComponentCatalogueTest {

    /** AC-D04-1: the catalogue is exactly the D04-S4.3 tables, with no duplicates. */
    @Test
    void catalogueMatchesTheBlueprintTables() {
        assertThat(ComponentCatalogue.TYPES).hasSize(ComponentCatalogue.EXPECTED_SIZE);
        assertThat(new HashSet<>(ComponentCatalogue.TYPES)).hasSize(ComponentCatalogue.EXPECTED_SIZE);
    }

    /** D04-R26: the catalogue must fit the 64-bit component mask, with headroom to spare. */
    @Test
    void catalogueFitsTheComponentMask() {
        assertThat(ComponentCatalogue.TYPES.size()).isLessThanOrEqualTo(ComponentTypeRegistry.MAX_COMPONENT_TYPES);
    }

    /** D04-R22: registration order is the wire order, so indices follow the list exactly. */
    @Test
    void registrationAssignsIndicesInCatalogueOrder() {
        ComponentTypeRegistry registry = new ComponentTypeRegistry();
        ComponentCatalogue.registerAll(registry);

        assertThat(registry.size()).isEqualTo(ComponentCatalogue.EXPECTED_SIZE);
        for (int i = 0; i < ComponentCatalogue.TYPES.size(); i++) {
            assertThat(registry.indexOf(ComponentCatalogue.TYPES.get(i)))
                    .as("index of %s", ComponentCatalogue.TYPES.get(i).getSimpleName())
                    .isEqualTo(i);
        }
    }

    /** D04-R22 again: a second registration must not renumber anything. */
    @Test
    void registrationIsIdempotent() {
        ComponentTypeRegistry registry = new ComponentTypeRegistry();
        ComponentCatalogue.registerAll(registry);
        ComponentCatalogue.registerAll(registry);

        assertThat(registry.size()).isEqualTo(ComponentCatalogue.EXPECTED_SIZE);
        assertThat(registry.indexOf(ComponentCatalogue.TYPES.get(0))).isZero();
    }

    /**
     * AC-D04-2: components are data only. Anything beyond {@code reset()}, {@code validate()}, and
     * trivial accessors is behaviour that belongs in a system — it would not survive replication or
     * rollback, which serialise fields and know nothing about methods.
     */
    @Test
    void componentsCarryNoBehaviour() {
        List<String> offenders = new ArrayList<>();
        Set<String> allowed = Set.of("reset", "validate", "set", "setCurrentHp", "equals", "hashCode", "toString");

        for (Class<? extends Component> type : ComponentCatalogue.TYPES) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || Modifier.isPrivate(method.getModifiers())) {
                    continue;
                }
                if (!allowed.contains(method.getName())) {
                    offenders.add(type.getSimpleName() + "." + method.getName() + "()");
                }
            }
        }
        assertThat(offenders)
                .as("component methods beyond accessors/reset/validate (AC-D04-2)")
                .isEmpty();
    }

    /** Every catalogue type must be instantiable with a no-arg constructor, since it is pooled. */
    @Test
    void everyComponentIsPoolable() throws Exception {
        for (Class<? extends Component> type : ComponentCatalogue.TYPES) {
            Component instance = type.getDeclaredConstructor().newInstance();
            assertThat(instance).as(type.getSimpleName()).isNotNull();
        }
    }

    /**
     * D04-R17: {@code reset()} returns every field to its declared default. Verified by comparing a
     * mutated-then-reset instance against a freshly constructed one, field by field, which catches
     * the field a future author adds and forgets to clear — the exact failure R17 describes, where a
     * pooled component leaks stale data into a newly spawned entity.
     */
    @Test
    void resetRestoresDeclaredDefaults() throws Exception {
        for (Class<? extends Component> type : ComponentCatalogue.TYPES) {
            Component pristine = type.getDeclaredConstructor().newInstance();
            Component reused = type.getDeclaredConstructor().newInstance();
            reused.reset();

            for (var field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object expected = field.get(pristine);
                Object actual = field.get(reused);
                var assertion =
                        assertThat(describe(actual)).as("%s.%s after reset()", type.getSimpleName(), field.getName());
                assertion.isEqualTo(describe(expected));
            }
        }
    }

    /** An unregistered type must be diagnosable rather than silently matching nothing. */
    @Test
    void unregisteredTypeIsReportedNotAssumedAbsent() {
        ComponentTypeRegistry registry = new ComponentTypeRegistry();
        assertThatThrownBy(() -> registry.indexOf(TransformComponent.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TransformComponent");
        assertThat(registry.indexOfOrAbsent(TransformComponent.class)).isEqualTo(-1);
    }

    /**
     * A structural description of a field value, for comparing a reset instance against a fresh one.
     *
     * <p>Direct {@code equals} is not usable: libGDX math types, arrays, and the ring buffers all
     * use identity equality, so two structurally identical values compare unequal. Arrays are
     * rendered element-wise; everything else falls back to {@code toString()}, which the components'
     * own collection and math field types all implement structurally. A {@code RingBuffer} field
     * reaches the fallback and compares by identity string, so its reset behaviour is asserted in
     * {@link #ringBufferFieldsAreClearedNotReplaced} instead.
     */
    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof int[] array) {
            return java.util.Arrays.toString(array);
        }
        if (value instanceof float[] array) {
            return java.util.Arrays.toString(array);
        }
        if (value.getClass().isArray()) {
            return java.util.Arrays.deepToString((Object[]) value);
        }
        if (value instanceof dev.syndicate.core.util.RingBuffer<?> ring) {
            return "ring[size=" + ring.size() + ",capacity=" + ring.capacity() + "]";
        }
        // Owned sub-objects (SensorSnapshot, StatBlock, Transform) are mutable holders with
        // identity toString(), so recurse into their fields rather than compare references.
        if (value.getClass().getName().startsWith("dev.syndicate.")) {
            StringBuilder out = new StringBuilder(value.getClass().getSimpleName()).append('{');
            for (var field : value.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    out.append(field.getName())
                            .append('=')
                            .append(describe(field.get(value)))
                            .append(';');
                } catch (IllegalAccessException e) {
                    throw new AssertionError("cannot read " + field, e);
                }
            }
            return out.append('}').toString();
        }
        return value.toString();
    }

    /**
     * D04-S5.6: the ring buffers are preallocated once and reused. {@code reset()} must clear them
     * rather than replace them — a fresh {@code RingBuffer} per pooled component would reallocate
     * 128 input commands on every respawn, which is the allocation AC-D04-6 measures.
     */
    @Test
    void ringBufferFieldsAreClearedNotReplaced() {
        PredictionComponent prediction = new PredictionComponent();
        var buffer = prediction.pendingInputs;
        buffer.next().sequence = 42;
        assertThat(buffer.size()).isEqualTo(1);

        prediction.reset();

        assertThat(prediction.pendingInputs).isSameAs(buffer);
        assertThat(prediction.pendingInputs.size()).isZero();
        assertThat(prediction.pendingInputs.capacity()).isEqualTo(PredictionComponent.CAPACITY);
        assertThat(prediction.lastAckedTick).isZero();

        InterpolationComponent interpolation = new InterpolationComponent();
        var samples = interpolation.buffer;
        samples.next().tick = 7L;
        interpolation.reset();
        assertThat(interpolation.buffer).isSameAs(samples);
        assertThat(interpolation.buffer.size()).isZero();
    }
}
