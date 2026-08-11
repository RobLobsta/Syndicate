/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.present;

import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.utils.Array;
import dev.syndicate.client.component.RenderModelComponent;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.DamageVisualComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import net.mgsx.gltf.scene3d.model.NodePartPlus;
import net.mgsx.gltf.scene3d.model.NodePlus;
import net.mgsx.gltf.scene3d.model.WeightVector;

/**
 * Schedule slot 23: how damaged a part looks
 * (docs/04_entity_component_model.md#D04-S4.4 row 23, docs/07_damage_destruction_model.md#D07-S5.5).
 *
 * <p>Reads {@code HealthComponent.healthFraction} — an authoritative, replicated number — and writes
 * shape key weights, which are neither. G6 runs one way only: this system is the sole writer of
 * {@link DamageVisualComponent} and nothing anywhere reads it back. That is what lets a dedicated
 * server skip both this system and the morph geometry it drives (D03-R13) and still simulate an
 * identical match.
 *
 * <p>The arithmetic is {@link MorphWeights}'; this system owns the schedule slot, the per-part state,
 * and pushing the result at the mesh. The push is the only part that touches gdx-gltf: a glTF morph
 * target's weight lives on the {@link NodePart} that references it, so a part whose mesh was split
 * across several primitives gets the same weight vector written to each.
 */
public final class DamageVisualSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 23;

    private final float[] target = new float[MorphWeights.COUNT];

    private Family unvisualised;
    private Family parts;

    @Override
    public Phase phase() {
        return Phase.PRESENT;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        unvisualised = world.family(ComponentQuery.all(HealthComponent.class, DamageStateComponent.class)
                .exclude(DamageVisualComponent.class));
        parts = world.family(
                ComponentQuery.all(HealthComponent.class, DamageStateComponent.class, DamageVisualComponent.class));
    }

    /**
     * @param dtSeconds real frame time, which is what the morph ease is defined against (D07-S5.5).
     *     It is deliberately not {@code TICK_DT}: this system runs once per frame, so easing by a
     *     tick's worth per frame would make the crumple speed a function of frame rate.
     */
    @Override
    public void update(World world, float dtSeconds, long tick) {
        attachToNewParts(world);

        int[] entityIds = parts.snapshot();
        int count = parts.size();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            HealthComponent health = world.getComponent(entityId, HealthComponent.class);
            DamageVisualComponent visual = world.getComponent(entityId, DamageVisualComponent.class);
            if (health == null || visual == null) {
                continue;
            }
            MorphWeights.forHealth(health.healthFraction, target);
            System.arraycopy(target, 0, visual.targetMorphWeights, 0, MorphWeights.COUNT);
            MorphWeights.moveToward(visual.morphWeights, visual.targetMorphWeights, dtSeconds);

            RenderModelComponent model = world.getComponent(entityId, RenderModelComponent.class);
            if (model != null && model.modelInstance != null) {
                apply(model.modelInstance, visual.morphWeights);
            }
        }
    }

    /**
     * Gives a {@link DamageVisualComponent} to every part that can take damage.
     *
     * <p>D04-S4.2's {@code PART} archetype lists it, but {@code VehicleFactory} builds parts on a
     * dedicated server too and must not attach cosmetic state there. The client adds it to the
     * archetype from this side, which is the same arrangement slot 22 uses for its render transform.
     */
    private void attachToNewParts(World world) {
        int[] entityIds = unvisualised.snapshot();
        int count = unvisualised.size();
        for (int i = 0; i < count; i++) {
            world.addComponent(entityIds[i], new DamageVisualComponent());
        }
    }

    /**
     * Writes the weights onto every morph-target-bearing primitive of a model.
     *
     * <p>A mesh with no shape keys is left alone, which is D07-R17's "simply never deforms": there is
     * no {@link WeightVector} to write to, and the part still takes damage, still changes state and
     * still fractures.
     */
    private void apply(ModelInstance instance, float[] weights) {
        for (int n = 0; n < instance.nodes.size; n++) {
            applyToNode(instance.nodes.get(n), weights);
        }
    }

    private void applyToNode(Node node, float[] weights) {
        Array<String> names = node instanceof NodePlus plus ? plus.morphTargetNames : null;
        if (node instanceof NodePlus plus && plus.weights != null) {
            write(plus.weights, weights, names);
        }
        for (int p = 0; p < node.parts.size; p++) {
            NodePart part = node.parts.get(p);
            if (part instanceof NodePartPlus plus && plus.morphTargets != null) {
                write(plus.morphTargets, weights, names);
            }
        }
        for (Node child : node.getChildren()) {
            applyToNode(child, weights);
        }
    }

    /**
     * Writes four damage weights into a mesh's morph target vector.
     *
     * <p>By name when the file carries target names, which is what D07-S5.5's {@code MORPH_NAMES}
     * table is for and the only way to be right about a mesh whose keys are authored in another
     * order or interleaved with keys this system does not own. A file with no names falls back to
     * treating the vector as the four levels in order, which is what the tool emits (D09-S5.3).
     */
    private void write(WeightVector vector, float[] weights, Array<String> names) {
        if (names == null) {
            MorphWeights.renormalise(weights, vector.count, vector.values);
            return;
        }
        java.util.Arrays.fill(vector.values, 0f);
        int deepest = -1;
        float unplaced = 0f;
        for (int level = 0; level < MorphWeights.COUNT; level++) {
            int index = names.indexOf(MorphWeights.NAMES[level], false);
            if (index >= 0 && index < vector.count) {
                vector.values[index] = weights[level];
                deepest = index;
            } else {
                // D07-R17: a level the mesh does not author is skipped, and its deformation is shown
                // by the deepest level that does exist rather than being silently dropped.
                unplaced += weights[level];
            }
        }
        if (deepest >= 0) {
            vector.values[deepest] += unplaced;
        }
    }
}
