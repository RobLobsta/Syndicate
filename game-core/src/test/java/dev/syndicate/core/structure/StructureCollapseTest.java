/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.structure;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.StructureDef;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.StructureComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.physics.DestructionTestScene;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.PartCategory;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A structure collapses when its supporting part dies (AC-D16-11, D16-S7.2).
 *
 * <p>The claim under test is D16-R80's, and it is a claim about what does <em>not</em> exist: the
 * span falls because it is "a part whose parent is gone", which {@code DetachSystem} (14) already
 * handles for a wheel. If this test ever needs a system that is not in the schedule already, the
 * design has drifted and D16-R81 is the sentence to re-read.
 */
final class StructureCollapseTest {

    /** A three-part stack: a base on the ground, a mid tier, and a crown on top of that. */
    private static final AssetId STRUCTURE_ID = AssetId.of("str_test_stack_01");

    @Test
    @Tag("integration")
    void aDestroyedBaseDropsEverythingAboveIt() {
        try (DestructionTestScene scene = new DestructionTestScene(4242L)) {
            scene.addGround();
            int structure = spawnStack(scene, new Vector3(0f, 0f, 0f));
            assertThat(structure).isNotEqualTo(EntityId.NULL);

            StructureComponent component = scene.world().getComponent(structure, StructureComponent.class);
            assertThat(component).isNotNull();
            assertThat(component.partCount).isEqualTo(3);

            // Every part starts as a zero-mass STATIC body carrying its authored mass (D16-R78).
            List<Integer> parts = partsOf(scene, structure);
            assertThat(parts).hasSize(2);
            for (int part : parts) {
                RigidBodyComponent body = scene.world().getComponent(part, RigidBodyComponent.class);
                assertThat(body.body).isNotNull();
                assertThat(body.layer).isEqualTo(CollisionLayer.STATIC);
                assertThat(body.massKg).isGreaterThan(0f);
            }

            // Kill the base. Nothing else is touched.
            int base = component.rootPartEntity;
            HealthComponent health = scene.world().getComponent(base, HealthComponent.class);
            health.setCurrentHp(0f);
            DamageStateComponent state = scene.world().getComponent(base, DamageStateComponent.class);
            state.state = DamageState.DESTROYED;
            state.stateEnteredTick = scene.tick();

            scene.step(4);

            // The structure is gone and what stood on it is debris. Every part above the root left
            // through `DetachSystem` alone: no schedule slot was added for this (AC-D16-11).
            assertThat(scene.world().isAlive(structure)).isFalse();
            assertThat(scene.debrisFactory().debrisCount()).isGreaterThan(0);
            for (int part : parts) {
                assertThat(scene.world().isAlive(part))
                        .as("part %s retired once its debris body existed", EntityId.toString(part))
                        .isFalse();
            }
        }
    }

    @Test
    @Tag("integration")
    void aStructurePartLeavesWeighingWhatItWasAuthoredAt() {
        try (DestructionTestScene scene = new DestructionTestScene(99L)) {
            scene.addGround();
            int structure = spawnStack(scene, new Vector3(0f, 0f, 0f));
            StructureComponent component = scene.world().getComponent(structure, StructureComponent.class);

            // The crown, two links up, is what falls furthest and is the one whose mass matters.
            List<Integer> parts = partsOf(scene, structure);
            int crown = parts.get(parts.size() - 1);
            float authored = scene.world().getComponent(crown, RigidBodyComponent.class).massKg;
            assertThat(authored).isEqualTo(400f);

            HealthComponent health = scene.world().getComponent(component.rootPartEntity, HealthComponent.class);
            health.setCurrentHp(0f);
            DamageStateComponent state =
                    scene.world().getComponent(component.rootPartEntity, DamageStateComponent.class);
            state.state = DamageState.DESTROYED;
            state.stateEnteredTick = scene.tick();
            scene.step(4);

            // D16-R79: the debris body's mass comes from the part definition, not from a share of a
            // vehicle's. One of the debris bodies now in the world weighs exactly what the crown did.
            Family debris =
                    scene.world().family(ComponentQuery.all(DebrisTagComponent.class, RigidBodyComponent.class));
            int[] ids = debris.snapshot();
            boolean found = false;
            for (int i = 0; i < debris.size(); i++) {
                RigidBodyComponent body = scene.world().getComponent(ids[i], RigidBodyComponent.class);
                if (body != null && Math.abs(body.massKg - authored) < 0.01f) {
                    found = true;
                }
            }
            assertThat(found)
                    .as("a debris body weighing the crown's authored mass")
                    .isTrue();
        }
    }

    /**
     * Registers a three-part stack and spawns it as a structure.
     *
     * <p>Built out of {@code DestructionTestScene}'s vehicle-assembly helper on purpose: if a
     * structure needed part types a vehicle cannot express, D16-R19 would already be broken.
     */
    private static int spawnStack(DestructionTestScene scene, Vector3 at) {
        AssemblyDef assembly = scene.registerAssembly(
                STRUCTURE_ID,
                List.of(
                        DestructionTestScene.PartSpec.of("root", PartCategory.CHASSIS, 1000f, new Vector3()),
                        DestructionTestScene.PartSpec.of(
                                "root/tier1", PartCategory.PANEL, 600f, new Vector3(0f, 0.6f, 0f)),
                        DestructionTestScene.PartSpec.of(
                                "root/tier1/tier2", PartCategory.PANEL, 400f, new Vector3(0f, 0.6f, 0f))));

        StructureDef definition = new StructureDef(STRUCTURE_ID, assembly, true, 2.0f, 1.8f);
        return StructureFactory.spawnStructure(
                scene.world(),
                scene.physics(),
                scene.shapes(),
                scene.assets(),
                definition,
                new Matrix4().setToTranslation(at));
    }

    /** The structure's non-root parts, in slot-path order (G3). */
    private static List<Integer> partsOf(DestructionTestScene scene, int structure) {
        SlotGraphComponent graph = scene.world().getComponent(structure, SlotGraphComponent.class);
        return graph.nodes.stream()
                .sorted((a, b) -> a.slotPath.compareTo(b.slotPath))
                .map((SlotNode node) -> node.childEntity)
                .toList();
    }
}
