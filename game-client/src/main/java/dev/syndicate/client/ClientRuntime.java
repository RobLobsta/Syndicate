/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import dev.syndicate.client.audio.SoundBank;
import dev.syndicate.client.input.InputBindings;
import dev.syndicate.client.render.RenderContext;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.AssetLoader;
import dev.syndicate.core.asset.GltfCollisionMeshSource;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.ValidationIssue;
import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.ecs.WorldFactory;
import dev.syndicate.core.physics.ArenaFactory;
import dev.syndicate.core.physics.DebrisFactory;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.system.CoreSystemProvider;
import dev.syndicate.core.system.SystemSetFactory;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.SpawnQueue;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.ExitCode;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.model.config.LaunchConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything a running client owns, brought up in D03-S5.1's order and torn down in D03-S5.6's
 * (docs/03_runtime_modes.md#D03-S5.1, #D03-S5.6).
 *
 * <p>The counterpart of {@code ServerRuntime}, and it deliberately builds the same world from the
 * same factories — {@link WorldFactory}, {@link SystemSetFactory}, {@link CoreSystemProvider},
 * {@link ArenaFactory}. The only differences are the six client slots the provider adds and the GL
 * resources of {@link RenderContext}. A client that assembled its own reduced simulation would be a
 * game nobody ships, in exactly the way D11-S5.8 says of the offline runner.
 *
 * <p>{@link AutoCloseable} because the teardown order is the point: GL resources before the world
 * that referenced them, then the world, then constraints, bodies, shapes and the physics world
 * (D02-S5.7, G19).
 */
public final class ClientRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ClientRuntime.class);

    /** Thrown when a startup step fails in a way D03-S4.4 assigns an exit code to. */
    public static final class StartupException extends RuntimeException {

        private final ExitCode exitCode;

        StartupException(ExitCode exitCode, String message, Throwable cause) {
            super(message, cause);
            this.exitCode = exitCode;
        }

        public ExitCode exitCode() {
            return exitCode;
        }
    }

    private final LaunchConfig config;
    private final World world;
    private final PhysicsWorld physics;
    private final ShapeCache shapes;
    private final RenderContext render;
    private final SoundBank sounds;
    private final ClientSystemProvider provider;
    private final LocalPlayer localPlayer;
    private final InMemoryAssetIndex assets;
    private final SpawnQueue spawnQueue;

    private ClientRuntime(
            LaunchConfig config,
            World world,
            PhysicsWorld physics,
            ShapeCache shapes,
            RenderContext render,
            SoundBank sounds,
            ClientSystemProvider provider,
            LocalPlayer localPlayer,
            InMemoryAssetIndex assets,
            SpawnQueue spawnQueue) {
        this.config = config;
        this.world = world;
        this.physics = physics;
        this.shapes = shapes;
        this.render = render;
        this.sounds = sounds;
        this.provider = provider;
        this.localPlayer = localPlayer;
        this.assets = assets;
        this.spawnQueue = spawnQueue;
    }

    /**
     * Steps 3 through 8 of D03-S5.1.
     *
     * <p>Must be called with a live GL context: step 8's {@code RenderContext.initialize} builds
     * shaders and textures, and {@link SoundBank} opens an audio device. Both are why this runs
     * inside the application listener's {@code create} rather than in {@code main}.
     *
     * @throws StartupException with the D03-S4.4 exit code for the step that failed
     */
    public static ClientRuntime start(LaunchConfig config) {
        return start(config, loadAssets(config), null);
    }

    /**
     * Steps 3 through 8, against content that is already loaded and a vehicle the player picked.
     *
     * <p>Split out so the shell can load the catalogue once at startup and build a match from it
     * repeatedly: the garage has to list the vehicles before any world exists, and a player who
     * finishes a match and starts another should not wait for every mesh to be re-read.
     *
     * @param assets the content index, loaded once and outliving this runtime
     * @param selectedAssembly the vehicle the local player drives, or null to take the first in the
     *     catalogue — which is what a launch with {@code --auto-start} and no menu does
     */
    public static ClientRuntime start(LaunchConfig config, InMemoryAssetIndex assets, AssetId selectedAssembly) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(assets, "assets");
        initialiseNatives(config);

        World world = WorldFactory.create(config);
        PhysicsWorld physics = PhysicsWorld.create();
        ShapeCache shapes = new ShapeCache();
        DebrisFactory debris = new DebrisFactory(physics);
        SpawnQueue spawnQueue = new SpawnQueue();

        ArenaDef arena = assets.arena(config.arenaId());
        if (arena == null) {
            LOG.warn(
                    "arena {} is not in the asset index; the world has no ground (D08-S4.7)",
                    config.arenaId().value());
        }

        RenderContext render = new RenderContext(config.assetRoot(), arena);
        SoundBank sounds = SoundBank.load(config.assetRoot());
        LocalPlayer localPlayer = new LocalPlayer();
        ClientSystemProvider provider =
                new ClientSystemProvider(InputBindings.load(config.assetRoot()), render, sounds, assets, localPlayer);

        List<EntitySystem> systems = SystemSetFactory.forMode(
                config.mode(), provider, new CoreSystemProvider(physics, shapes, assets, spawnQueue, debris));
        world.registerSystems(systems);

        if (arena != null) {
            ArenaFactory.LoadedArena loaded = ArenaFactory.load(world, physics, shapes, arena, assets);
            // The ground has to be handed to the renderer here rather than built with it: generating
            // it needs the physics world, and drawing it needs a GL context. Without this the
            // scrapyard's heaps collide and nothing draws them, which reads as broken physics.
            render.useTerrain(loaded.terrain());
            Vector3 centre =
                    new Vector3(arena.boundsMin()).add(arena.boundsMax()).scl(0.5f);
            centre.y = arena.groundY();
            provider.renderSystem().setArenaCentre(centre);
        }

        // Step 7. Named rather than passed over: "this build has no networking" and "this client
        // failed to connect" must not look alike in a log.
        LOG.warn("no transport is implemented; this client hosts its own match only (D03-S5.1 step 7)");

        bootstrapMatch(world, assets, config, localPlayer, provider, selectedAssembly);

        return new ClientRuntime(
                config, world, physics, shapes, render, sounds, provider, localPlayer, assets, spawnQueue);
    }

    /**
     * Step 8: rules, a human, and bots to fight.
     *
     * <p>The human joins <em>before</em> {@code MatchFlowSystem} fills the lobby, so the bots that
     * top it up count the player and the arena's spawn points are shared out between all of them.
     * Joining after would produce one more car than the launch asked for and a starting grid that
     * had already been dealt.
     */
    private static void bootstrapMatch(
            World world,
            InMemoryAssetIndex assets,
            LaunchConfig config,
            LocalPlayer localPlayer,
            ClientSystemProvider provider,
            AssetId selectedAssembly) {

        MatchRulesComponent rules = world.getComponent(EntityId.MATCH, MatchRulesComponent.class);
        MatchClockComponent clock = world.getComponent(EntityId.MATCH, MatchClockComponent.class);
        if (clock != null && clock.timeLimitTicks <= 0) {
            clock.timeLimitTicks = DEFAULT_TIME_LIMIT_TICKS;
        }
        if (rules != null) {
            // Pressing DEPLOY in the garage *is* the readiness signal a lobby waits for (D11-S5.7).
            // Reaching this constructor at all means the player has given it, so the lobby has
            // nothing left to wait for and starting the countdown immediately is correct.
            rules.autoStart = true;
        }

        int playerEntity = LocalPlayerFactory.join(
                world, assets, config.gameMode(), LocalPlayerFactory.DEFAULT_NAME, selectedAssembly);
        localPlayer.setPlayerEntity(playerEntity);
        provider.inputCollection().setLocalPlayer(playerEntity);
    }

    /** The time limit a client assumes when the configuration declares none. */
    public static final int DEFAULT_TIME_LIMIT_TICKS = 180 * SimulationConstants.TICK_RATE_HZ;

    public World world() {
        return world;
    }

    public RenderContext render() {
        return render;
    }

    public LocalPlayer localPlayer() {
        return localPlayer;
    }

    public LaunchConfig config() {
        return config;
    }

    /** The physics world, for a tool that has to place a body into it (the debug console). */
    public PhysicsWorld physics() {
        return physics;
    }

    /** The shape cache that owns every hull (G19). */
    public ShapeCache shapes() {
        return shapes;
    }

    /** Everything that was loaded, for a tool that offers the roster as a choice. */
    public InMemoryAssetIndex assets() {
        return assets;
    }

    /** The queue slot 5 drains, which is the only sanctioned way to ask for a spawn. */
    public SpawnQueue spawnQueue() {
        return spawnQueue;
    }

    public ClientSystemProvider provider() {
        return provider;
    }

    /** Step 3: Bullet natives, exactly once per process (D02-R3). */
    private static void initialiseNatives(LaunchConfig config) {
        try {
            Bullet.init(false);
        } catch (UnsatisfiedLinkError e) {
            throw new StartupException(ExitCode.NATIVES_MISSING, "Bullet natives are unavailable for this platform", e);
        }
        if (config.profile()) {
            NativeResourceTracker.install();
        }
    }

    /**
     * Step 5: content, with the strict/degrade split of D03-S5.1 and G18.
     *
     * <p>Public because the shell calls it before the first screen, not before the first match:
     * the garage lists what this returns.
     */
    public static InMemoryAssetIndex loadAssets(LaunchConfig config) {
        Path assetRoot = config.assetRoot();
        if (assetRoot == null || !Files.isDirectory(assetRoot)) {
            throw new StartupException(
                    ExitCode.ASSETS_NOT_FOUND, "asset root " + assetRoot + " is missing or not a directory", null);
        }
        AssetLoader loader = new AssetLoader(new GltfCollisionMeshSource());
        InMemoryAssetIndex index = loader.loadFrom(assetRoot);

        List<ValidationIssue> blocking = loader.blockingIssues();
        if (!blocking.isEmpty()) {
            if (config.strictAssets()) {
                blocking.forEach(issue -> LOG.error("  {}", issue));
                throw new StartupException(
                        ExitCode.ASSETS_INVALID, blocking.size() + " blocking asset validation errors", null);
            }
            LOG.warn("{} blocking asset errors; affected content is unavailable this run", blocking.size());
            blocking.forEach(issue -> LOG.warn("  {}", issue));
        }
        LOG.info("assets loaded from {}", assetRoot);
        return index;
    }

    /** The teardown of D03-S5.6, in the native disposal order of D02-S5.7. */
    @Override
    public void close() {
        // GL and audio first: they hold references into the models and sounds the world's components
        // point at, and disposing the world first would leave the batch drawing freed meshes.
        render.dispose();
        // Before the sound bank and before the world: the engine bus runs a thread that is still
        // pulling on the mixer, and it reads vehicle state the world is about to tear down (G19).
        if (provider.audioSystem() != null) {
            provider.audioSystem().dispose();
        }
        sounds.dispose();
        world.dispose();
        physics.close();
        shapes.close();

        if (NativeResourceTracker.isEnabled() && NativeResourceTracker.outstanding() != 0) {
            LOG.error("native resources outstanding at shutdown: {}", NativeResourceTracker.describeOutstanding());
        }
    }
}
