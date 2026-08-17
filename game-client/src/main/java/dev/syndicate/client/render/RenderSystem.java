/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import dev.syndicate.client.LocalPlayer;
import dev.syndicate.client.component.ParticleRefComponent;
import dev.syndicate.client.component.RenderModelComponent;
import dev.syndicate.client.component.RenderTransformComponent;
import dev.syndicate.client.effect.EffectSystem;
import dev.syndicate.client.input.InputDeviceKind;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.FractureManifest;
import dev.syndicate.core.asset.ShardDefinition;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PlayerIdentityComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.ScoreComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.ShapeCacheKey;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.model.MatchOutcome;
import dev.syndicate.model.MatchPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Schedule slot 26: the draw call (docs/04_entity_component_model.md#D04-S4.4 row 26).
 *
 * <p>The last system in the frame, and the only one in the project that produces something a person
 * can look at. It draws the arena, then every part that has a mesh, then the effect bursts, then the
 * HUD — in that order because each pass depends on the depth buffer the one before it left.
 *
 * <p><b>It reads render transforms, never simulation transforms.</b> Slot 22 has already placed
 * every entity between the last two ticks; using {@code TransformComponent.worldMatrix} here would
 * discard that and reintroduce the judder it exists to remove. The one exception is an entity that
 * appeared this frame and has no render transform yet, which is drawn where the simulation says it
 * is because that is the only place it has ever been.
 *
 * <p>Cosmetic in the strict sense of G6: this system writes nothing any other system reads.
 */
public final class RenderSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 26;

    /** Metres. Radius the overview camera frames when there is no vehicle to follow. */
    public static final float OVERVIEW_RADIUS_M = 60f;

    private final RenderContext context;
    private final AssetIndex assets;
    private final LocalPlayer localPlayer;
    private final Supplier<InputDeviceKind> activeDevice;
    private final EffectSystem effects;

    private final Vector3 focus = new Vector3();
    private final Vector3 arenaCentre = new Vector3();
    private final List<Hud.ScoreRow> scoreboard = new ArrayList<>();

    private Family drawable;
    private Family undrawn;
    private Family undrawnDebris;
    private Family players;
    private Family bursts;
    private Family lamps;

    private int drawnThisFrame;

    private final Vector3 lampPosition = new Vector3();
    private final Vector3 lampDirection = new Vector3();

    /** What drives every articulated part's pose this frame (D17-S5.9). Cosmetic throughout (G6). */
    private final ArticulationState articulation = new ArticulationState();

    private final Matrix4 articulationPose = new Matrix4();

    /**
     * @param effects slot 24, read for the bursts it owns. This is a constructor dependency rather
     *     than a system-to-system call at update time (D04-R13): the renderer is told where the
     *     particles are once, and never asks slot 24 to do anything.
     */
    public RenderSystem(
            RenderContext context,
            AssetIndex assets,
            LocalPlayer localPlayer,
            EffectSystem effects,
            Supplier<InputDeviceKind> activeDevice) {
        this.context = Objects.requireNonNull(context, "context");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.localPlayer = Objects.requireNonNull(localPlayer, "localPlayer");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.activeDevice = Objects.requireNonNull(activeDevice, "activeDevice");
    }

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
        undrawn = world.family(ComponentQuery.all(PartRefComponent.class).exclude(RenderModelComponent.class));
        // Debris is the other half of what has a mesh, and it is not a part: a shard has no
        // PartRefComponent, because the part it broke off was destroyed in the tick that made it
        // (DEC-018, D04-E1). What it does carry is the shape key its hull came from, which names
        // the manifest and the shard within it.
        undrawnDebris = world.family(ComponentQuery.all(DebrisTagComponent.class, RigidBodyComponent.class)
                .exclude(RenderModelComponent.class));
        drawable = world.family(ComponentQuery.all(RenderModelComponent.class, RenderTransformComponent.class));
        players = world.family(ComponentQuery.all(PlayerIdentityComponent.class, ScoreComponent.class));
        // A lamp is a part with a render transform. Whether it is *lit* is decided per frame from
        // the part's health, which is authoritative state this system only ever reads (G6).
        lamps = world.family(ComponentQuery.all(PartRefComponent.class, RenderTransformComponent.class));
        bursts = effects.bursts();
        articulation.initialize(world);
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        // Real frame time, not tick time: a PRESENT system is handed the frame delta (DEC-049), and
        // a recoil that advanced on ticks would stutter on any machine not running at exactly 60 fps.
        articulation.advance(dtSeconds);
        articulation.resolvePending(world);
        attachModels(world);
        aimCamera(world, dtSeconds);
        placeLamps(world);

        Color sky = context.environment().sky();
        ScreenUtils.clear(sky.r, sky.g, sky.b, 1f, true);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        drawScene(world);
        drawParticles(world);
        context.hud().render(buildHudFrame(world));
    }

    /** How many model instances the last frame drew, for a capture's log line. */
    public int drawnThisFrame() {
        return drawnThisFrame;
    }

    // ---- Model attachment -----------------------------------------------------------

    /**
     * Gives every part a drawable instance.
     *
     * <p>Done here rather than in {@code VehicleFactory} for the reason D03-R14 gives: the factory
     * runs on a dedicated server, and a server that built {@code ModelInstance}s would need a GL
     * context to do it. A part whose mesh will not load gets a component with a null instance, so
     * the failed load is attempted once rather than every frame.
     */
    private void attachModels(World world) {
        int[] entityIds = undrawn.snapshot();
        int count = undrawn.size();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            PartRefComponent part = world.getComponent(entityId, PartRefComponent.class);
            RenderModelComponent model = new RenderModelComponent();
            model.modelInstance = part == null ? null : context.partModels().instanceOf(part.partTypeId);
            world.addComponent(entityId, model);
        }
        attachShardModels(world);
    }

    /**
     * Gives every shard the piece of glass it is.
     *
     * <p>Without this the authored destruction is invisible: {@code FractureSystem} spawns one
     * debris body per shard and they collide, slide and settle, but nothing draws them, so a
     * windscreen taking a burst still just disappears. The shard's own hull is already the right
     * shape — this asks {@code shards.glb} for the same node the loader took that hull from
     * (D08-S5.3 step 2), which is why {@link ShardDefinition#meshNodeName()} exists rather than
     * each side inventing a naming convention.
     *
     * <p>A shard whose manifest is not in the index still simulates and still cannot be drawn, and
     * that is a null instance rather than a skipped entity: the component is attached either way,
     * so a failed lookup happens once per shard rather than once per frame for its whole life.
     */
    private void attachShardModels(World world) {
        int[] entityIds = undrawnDebris.snapshot();
        int count = undrawnDebris.size();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            RenderModelComponent model = new RenderModelComponent();
            model.modelInstance = shardInstance(world.getComponent(entityId, RigidBodyComponent.class));
            world.addComponent(entityId, model);
        }
    }

    private ModelInstance shardInstance(RigidBodyComponent body) {
        if (body == null || body.shapeKey == null || body.shapeKey.variant() != ShapeCacheKey.Variant.SHARD_HULL) {
            return null;
        }
        FractureManifest manifest = assets.fractureManifest(body.shapeKey.assetId());
        if (manifest == null) {
            return null;
        }
        for (ShardDefinition shard : manifest.shards()) {
            if (shard.index() == body.shapeKey.index()) {
                return context.partModels().shardInstanceOf(manifest.partTypeId(), shard);
            }
        }
        return null;
    }

    // ---- Lamps ----------------------------------------------------------------------

    /**
     * Lights every lamp on every living part, then hands the nearest ones to the environment.
     *
     * <p>The whole of the on/off rule is here, and all of it is a <em>read</em> of authoritative
     * state: a lamp lights while its part is alive and attached, and stops the moment the part is
     * destroyed or comes off. That is the right way round for G6 — the cosmetic layer observes the
     * simulation and never the reverse — and it means shooting a headlight out is already
     * implemented by the damage model rather than by anything here.
     *
     * <p>A lamp's origin and direction are authored in the part's own space, so they are
     * transformed by the part's render matrix. Using the <em>render</em> matrix rather than the
     * simulation transform is the same rule the meshes follow: a beam that lagged its own lamp by a
     * tick would visibly swing behind the car.
     */
    private void placeLamps(World world) {
        VehicleLights lights = context.vehicleLights();
        lights.begin();
        float night = context.environment().nightFraction();
        if (night <= 0f) {
            lights.commit(context.environment(), night);
            return;
        }

        Vector3 eye = context.camera().camera().position;
        int[] entityIds = lamps.snapshot();
        int count = lamps.size();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            PartRefComponent part = world.getComponent(entityId, PartRefComponent.class);
            if (part == null) {
                continue;
            }
            PartLamps.Lamp lamp = context.partLamps().lampFor(part.partTypeId.value());
            if (lamp == null || !isLit(world, entityId)) {
                continue;
            }
            RenderTransformComponent render = world.getComponent(entityId, RenderTransformComponent.class);
            if (render == null) {
                continue;
            }
            lampPosition.set(lamp.origin()).mul(render.renderMatrix);
            // The direction is rotated by the part's orientation and not translated, so it is
            // transformed as a direction rather than as a point.
            lampDirection.set(lamp.direction()).rot(render.renderMatrix).nor();
            lights.add(lampPosition, lampDirection, lamp, eye);
        }
        lights.commit(context.environment(), night);
    }

    /** A lamp lights while its part is alive and has health left; a destroyed lamp is dark. */
    private boolean isLit(World world, int entityId) {
        if (!world.isAlive(entityId)) {
            return false;
        }
        HealthComponent health = world.getComponent(entityId, HealthComponent.class);
        return health == null || health.healthFraction > 0f;
    }

    // ---- Camera ---------------------------------------------------------------------

    private void aimCamera(World world, float dtSeconds) {
        int vehicle = localPlayer.vehicleEntity(world);
        RenderTransformComponent render =
                vehicle == EntityId.NULL ? null : world.getComponent(vehicle, RenderTransformComponent.class);
        if (render == null) {
            context.camera().overview(arenaCentre, OVERVIEW_RADIUS_M, dtSeconds);
            return;
        }
        render.renderMatrix.getTranslation(focus);

        // Yaw straight off the render rotation rather than off the body's forward axis, so the
        // camera is behind where the car is *drawn* and not where it was at the last tick.
        float yawRad = (float) Math.toRadians(render.currentRotation.getYaw());
        context.camera().follow(focus, yawRad, speedFraction(world, vehicle), dtSeconds);
    }

    private float speedFraction(World world, int vehicle) {
        VelocityComponent velocity = world.getComponent(vehicle, VelocityComponent.class);
        VehicleStatsComponent stats = world.getComponent(vehicle, VehicleStatsComponent.class);
        if (velocity == null || stats == null || stats.maxSpeedMps <= 0f) {
            return 0f;
        }
        return Math.min(1f, velocity.linear.len() / stats.maxSpeedMps);
    }

    /** Where the overview camera looks when nothing is being driven. */
    public void setArenaCentre(Vector3 centre) {
        arenaCentre.set(centre);
    }

    // ---- Passes ---------------------------------------------------------------------

    private void drawScene(World world) {
        drawnThisFrame = 0;
        context.batch().begin(context.camera().camera());
        // Ground first: either the generated terrain or the flat box, never both.
        TerrainModel terrain = context.terrainModel();
        if (terrain != null) {
            context.batch().render(terrain.instance(), context.environment().environment());
            drawnThisFrame++;
        }
        ArenaModel arena = context.arenaModel();
        if (arena != null) {
            context.batch().render(arena.instance(), context.environment().environment());
            drawnThisFrame++;
        }
        int[] entityIds = drawable.snapshot();
        int count = drawable.size();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            RenderModelComponent model = world.getComponent(entityId, RenderModelComponent.class);
            RenderTransformComponent render = world.getComponent(entityId, RenderTransformComponent.class);
            if (model == null || !model.visible || model.modelInstance == null || render == null) {
                continue;
            }
            ModelInstance instance = model.modelInstance;
            instance.transform.set(render.renderMatrix);
            // Articulation composes *after* the render matrix, in the part's own local space, so a
            // recoiling barrel slides along its own bore rather than along a world axis (D17-R48).
            // The collision hull is untouched by this — that is the G6 line, and crossing it would
            // make hit registration depend on frame rate.
            PartRefComponent articulated = world.getComponent(entityId, PartRefComponent.class);
            if (articulated != null) {
                PartArticulation.Articulation block =
                        context.partArticulation().forPart(articulated.partTypeId.value());
                if (block != null) {
                    float phase = articulation.phaseFor(world, entityId, block);
                    instance.transform.mul(PartArticulation.pose(block, phase, articulationPose));
                }
            }
            context.batch().render(instance, context.environment().environment());
            drawnThisFrame++;
        }
        // Inside the same begin/end so the batch's sorter puts every blended cone after every
        // opaque surface, which is the only order in which an additive beam reads as light.
        context.vehicleLights()
                .renderBeams(
                        context.batch(),
                        context.environment(),
                        context.environment().nightFraction());
        context.batch().end();
    }

    private void drawParticles(World world) {
        context.particles().begin(context.camera().camera());
        int[] entityIds = bursts.snapshot();
        int count = bursts.size();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            ParticleRefComponent particles = world.getComponent(entityId, ParticleRefComponent.class);
            RenderTransformComponent render = world.getComponent(entityId, RenderTransformComponent.class);
            if (particles == null) {
                continue;
            }
            if (render != null) {
                render.renderMatrix.getTranslation(focus);
            } else {
                focus.set(0f, 0f, 0f);
            }
            context.particles().add(particles, focus.x, focus.y, focus.z, EffectSystem.fade(particles));
        }
        context.particles().flush(context.camera().camera());
    }

    // ---- HUD ------------------------------------------------------------------------

    private Hud.Frame buildHudFrame(World world) {
        int vehicle = localPlayer.vehicleEntity(world);
        boolean hasVehicle = vehicle != EntityId.NULL;

        float speed = 0f;
        float topSpeed = 0f;
        float health = 0f;
        int live = 0;
        int total = 0;
        if (hasVehicle) {
            VelocityComponent velocity = world.getComponent(vehicle, VelocityComponent.class);
            VehicleStatsComponent stats = world.getComponent(vehicle, VehicleStatsComponent.class);
            VehicleChassisComponent chassis = world.getComponent(vehicle, VehicleChassisComponent.class);
            speed = velocity == null ? 0f : velocity.linear.len();
            topSpeed = stats == null ? 0f : stats.maxSpeedMps;
            HealthComponent chassisHealth =
                    chassis == null ? null : world.getComponent(chassis.chassisPartEntity, HealthComponent.class);
            health = chassisHealth == null ? 0f : chassisHealth.healthFraction;

            SlotGraphComponent graph = world.getComponent(vehicle, SlotGraphComponent.class);
            if (graph != null) {
                total = graph.nodes.size();
                for (SlotNode node : graph.nodes) {
                    if (world.isAlive(node.childEntity)) {
                        live++;
                    }
                }
            }
        }

        MatchStateComponent state = world.getComponent(EntityId.MATCH, MatchStateComponent.class);
        MatchClockComponent clock = world.getComponent(EntityId.MATCH, MatchClockComponent.class);
        int remaining =
                clock == null || clock.timeLimitTicks <= 0 ? -1 : Math.max(0, clock.timeLimitTicks - (int) clock.tick);

        return new Hud.Frame(
                hasVehicle,
                speed,
                topSpeed,
                health,
                live,
                total,
                state == null ? MatchPhase.LOBBY : state.phase,
                state == null ? MatchOutcome.UNDECIDED : state.outcome,
                remaining,
                buildScoreboard(world),
                activeDevice.get(),
                Gdx.graphics.getFramesPerSecond());
    }

    /**
     * The scoreboard, highest score first then most kills.
     *
     * <p>Rows are collected in ascending entity order and {@code List.sort} is stable, so two players
     * who are level stay in the same order from frame to frame rather than swapping places every
     * time the list is rebuilt (G3's reason, applied to something a player is reading).
     */
    private List<Hud.ScoreRow> buildScoreboard(World world) {
        scoreboard.clear();
        int[] entityIds = players.snapshot();
        int count = players.size();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            PlayerIdentityComponent identity = world.getComponent(entityId, PlayerIdentityComponent.class);
            ScoreComponent score = world.getComponent(entityId, ScoreComponent.class);
            if (identity == null || score == null) {
                continue;
            }
            scoreboard.add(new Hud.ScoreRow(
                    identity.displayName,
                    identity.isBot,
                    entityId == localPlayer.playerEntity(),
                    score.kills,
                    score.deaths,
                    score.objectiveScore));
        }
        scoreboard.sort((a, b) ->
                a.score() != b.score() ? Integer.compare(b.score(), a.score()) : Integer.compare(b.kills(), a.kills()));
        return scoreboard;
    }
}
