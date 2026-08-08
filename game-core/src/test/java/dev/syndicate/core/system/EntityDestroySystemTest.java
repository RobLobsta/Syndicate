/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.dynamics.btPoint2PointConstraint;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotAttachmentComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.SlotNode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("physics")
class EntityDestroySystemTest {

    static final class DummyComponent implements Component {
        @Override
        public void reset() {}
    }

    private World world;
    private PhysicsWorld physics;
    private EntityDestroySystem system;

    @BeforeEach
    void setUp() {
        Bullet.init();
        NativeResourceTracker.install();

        physics = PhysicsWorld.create();
        world = new World(1337L, true);

        system = new EntityDestroySystem(physics);
        world.registerSystems(List.of(system));
    }

    @AfterEach
    void tearDown() {
        world.dispose();
        physics.dispose();
        assertThat(NativeResourceTracker.outstanding())
                .as(NativeResourceTracker.describeOutstanding())
                .isZero();
        NativeResourceTracker.uninstall();
    }

    @Test
    void testDoubleDestroyIsNoOp() {
        // T-D04-2: Double-destroy is a safe no-op.
        int id = world.createEntity().id();
        world.addComponent(id, new DummyComponent());

        assertThatCode(() -> {
                    world.destroyEntity(id);
                    world.destroyEntity(id);
                    world.tick(1L);
                })
                .doesNotThrowAnyException();

        assertThat(world.isAlive(id)).isFalse();
        assertThat(world.entityCount()).isZero();
    }

    @Test
    void testVehicleDestructionCascadesToParts() {
        // T-D04-10: Destroying a vehicle destroys all its part entities.
        int chassisId = world.createEntity().id();
        int wheelId = world.createEntity().id();
        int partId = world.createEntity().id();
        int nodePartId = world.createEntity().id();

        VehicleChassisComponent chassis = new VehicleChassisComponent();
        chassis.chassisPartEntity = partId;
        chassis.wheelEntities[0] = wheelId;
        chassis.wheelCount = 1;
        world.addComponent(chassisId, chassis);

        SlotGraphComponent graph = new SlotGraphComponent();
        SlotNode node = new SlotNode();
        node.childEntity = nodePartId;
        graph.nodes.add(node);
        world.addComponent(chassisId, graph);

        world.destroyEntity(chassisId);
        world.tick(1L);

        // Explicitly assert that the components' fields were not keeping it alive, or rather, that the system ran the
        // recursive logic.
        // Wait, world.tick(1L) runs PRESENT and CLEANUP logic. Actually, it runs CLEANUP directly? No, CLEANUP is part
        // of EntitySystem schedule.
        // Let's ensure the recursive logic successfully ran.
        assertThat(world.isAlive(chassisId)).isFalse();
        assertThat(world.isAlive(wheelId)).isFalse();
        assertThat(world.isAlive(partId)).isFalse();
        assertThat(world.isAlive(nodePartId)).isFalse();
    }

    @Test
    void testNativeResourcesAreDisposed() {
        // T-D04-10: natives are freed; constraints removed before bodies (handled inherently by EntityDestroySystem
        // logic and physics world).
        int id = world.createEntity().id();

        btDefaultMotionState motionState = new btDefaultMotionState();
        btRigidBody.btRigidBodyConstructionInfo ci = new btRigidBody.btRigidBodyConstructionInfo(1f, motionState, null);
        NativeResourceTracker.register("btRigidBodyConstructionInfo");
        btRigidBody body = new btRigidBody(ci);
        ci.dispose();
        NativeResourceTracker.release("btRigidBodyConstructionInfo");

        btPoint2PointConstraint constraint = new btPoint2PointConstraint(body, new Vector3(0, 1, 0));

        NativeResourceTracker.register("btDefaultMotionState");
        NativeResourceTracker.register("btRigidBody");
        NativeResourceTracker.register("btPoint2PointConstraint");

        physics.addBody(body, 1, 1);
        physics.dynamicsWorld().addConstraint(constraint);

        RigidBodyComponent rigidBody = new RigidBodyComponent();
        rigidBody.body = body;
        rigidBody.motionState = motionState;
        world.addComponent(id, rigidBody);

        SlotAttachmentComponent attachment = new SlotAttachmentComponent();
        attachment.constraintHandle = constraint;
        world.addComponent(id, attachment);

        world.destroyEntity(id);
        world.tick(1L);

        // Assert they are removed from physics world.
        assertThat(physics.contains(body)).isFalse();
        assertThat(physics.dynamicsWorld().getNumConstraints()).isZero();

        // Assert they are tracked as disposed.
        // NativeResourceTracker.outstanding() check in tearDown() will fail if they are not correctly disposed.
    }
}
