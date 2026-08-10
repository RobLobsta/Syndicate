/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.server;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessFiles;
import com.badlogic.gdx.physics.bullet.Bullet;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.AssetLoader;
import dev.syndicate.core.asset.GltfCollisionMeshSource;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.ValidationIssue;
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
import dev.syndicate.model.ExitCode;
import dev.syndicate.model.config.LaunchConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything a running server owns, brought up in the order D03-S5.1 fixes and torn down in the
 * order D03-S5.6 fixes (docs/03_runtime_modes.md#D03-S5.1, #D03-S5.6).
 *
 * <p>It is {@link AutoCloseable} because that ordering is the point: constraints before bodies,
 * bodies before shapes, shapes before the world (D02-S5.7). A {@code try}-with-resources in
 * {@link ServerMain} then guarantees the teardown runs whether the loop ended normally, threw, or
 * was stopped by a signal — which is what makes the {@code NativeResourceTracker} census at the end
 * a real assertion rather than a hope.
 *
 * <p><b>Assets.</b> The loader reads everything textual and asks a {@link AssetLoader
 * .CollisionMeshSource} for collision geometry (DEV-010). That source is now
 * {@link GltfCollisionMeshSource}, which reads the part's {@code mesh.glb} with {@code game-core}'s
 * own headless glTF reader — so a part directory holding a model loads completely, and one that does
 * not still reports A503 for that part alone rather than failing the server.
 */
final class ServerRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ServerRuntime.class);

    /** Thrown when a startup step fails in a way D03-S4.4 assigns an exit code to. */
    static final class StartupException extends RuntimeException {

        private final ExitCode exitCode;

        StartupException(ExitCode exitCode, String message, Throwable cause) {
            super(message, cause);
            this.exitCode = exitCode;
        }

        ExitCode exitCode() {
            return exitCode;
        }
    }

    private final World world;
    private final PhysicsWorld physics;
    private final ShapeCache shapes;
    private final List<EntitySystem> systems;

    private ServerRuntime(World world, PhysicsWorld physics, ShapeCache shapes, List<EntitySystem> systems) {
        this.world = world;
        this.physics = physics;
        this.shapes = shapes;
        this.systems = systems;
    }

    /**
     * Steps 3 through 8 of D03-S5.1, as far as the implemented subsystems reach.
     *
     * @throws StartupException with the D03-S4.4 exit code for the step that failed
     */
    static ServerRuntime start(LaunchConfig config) {
        Objects.requireNonNull(config, "config");
        initialiseNatives(config);
        initialiseFiles();

        InMemoryAssetIndex assets = loadAssets(config);

        World world = WorldFactory.create(config);
        PhysicsWorld physics = PhysicsWorld.create();
        ShapeCache shapes = new ShapeCache();
        DebrisFactory debris = new DebrisFactory(physics);
        SpawnQueue spawnQueue = new SpawnQueue();

        List<EntitySystem> systems = SystemSetFactory.forMode(
                config.mode(), new CoreSystemProvider(physics, shapes, assets, spawnQueue, debris));
        world.registerSystems(systems);

        // Step 6 of D03-S5.1, and D04-S5.4's last line: the world gets a floor before anything is
        // asked to stand on it.
        loadArena(world, physics, shapes, assets, config);

        // Steps 7 and 8. Named rather than passed over: an operator reading the log should be able
        // to tell "this build has no networking" from "this server failed to bind".
        LOG.warn("no transport is implemented; this server accepts no connections (D03-S5.1 step 7)");
        LOG.warn("no match bootstrap is implemented; no vehicle is spawned into the arena " + "(D03-S5.1 step 8)");

        return new ServerRuntime(world, physics, shapes, systems);
    }

    /**
     * Puts the configured arena's static geometry into the world (D04-S5.4).
     *
     * <p>A missing arena is a warning rather than a startup failure. A server with no floor still
     * ticks, still replicates, and still reports what it is missing — refusing to boot would make an
     * asset problem look like a crash, and the mode that most needs to start regardless is the one
     * an operator is using to debug the assets.
     */
    private static void loadArena(
            World world, PhysicsWorld physics, ShapeCache shapes, InMemoryAssetIndex assets, LaunchConfig config) {

        ArenaDef arena = assets.arena(config.arenaId());
        if (arena == null) {
            LOG.warn(
                    "arena {} is not in the asset index; the world has no ground (D08-S4.7)",
                    config.arenaId().value());
            return;
        }
        if (!arena.supports(config.gameMode())) {
            LOG.warn(
                    "arena {} does not declare mode {}; loading it anyway",
                    arena.arenaId().value(),
                    config.gameMode());
        }
        ArenaFactory.load(world, physics, shapes, arena);
    }

    World world() {
        return world;
    }

    List<EntitySystem> systems() {
        return systems;
    }

    /** Step 3: Bullet natives, exactly once per process (D02-R3). */
    private static void initialiseNatives(LaunchConfig config) {
        try {
            // useRefCounting = false: ownership in this project is manual and documented, one owner
            // per native object (G19), and reference counting would mask a missing dispose rather
            // than let the tracker's census find it.
            Bullet.init(false);
        } catch (UnsatisfiedLinkError e) {
            throw new StartupException(ExitCode.NATIVES_MISSING, "Bullet natives are unavailable for this platform", e);
        }
        if (config.profile()) {
            NativeResourceTracker.install();
        }
    }

    /**
     * The half of D03-S5.1 step 4 a headless server actually needs.
     *
     * <p>{@code Gdx.files} without an {@code Application} (DEV-011): the backend is here for its
     * file system, not for its loop. It has no GL context and creates none, so G17 holds exactly as
     * it would with the full {@code HeadlessApplication}.
     */
    private static void initialiseFiles() {
        if (Gdx.files == null) {
            Gdx.files = new HeadlessFiles();
        }
    }

    /** Step 5: content, with the strict/degrade split of D03-S5.1 and G18. */
    private static InMemoryAssetIndex loadAssets(LaunchConfig config) {
        Path assetRoot = config.assetRoot();
        if (assetRoot == null || !Files.isDirectory(assetRoot)) {
            throw new StartupException(
                    ExitCode.ASSETS_NOT_FOUND, "asset root " + assetRoot + " is missing or not a directory", null);
        }

        // A part with no mesh on disk still reports A503 and is skipped (G18) — but a part that has
        // one is now read here rather than stubbed out, which is what DEV-010's seam existed for.
        AssetLoader loader = new AssetLoader(new GltfCollisionMeshSource());
        InMemoryAssetIndex index = loader.loadFrom(assetRoot);

        List<ValidationIssue> blocking = loader.blockingIssues();
        if (!blocking.isEmpty()) {
            if (config.strictAssets()) {
                blocking.forEach(issue -> LOG.error("  {}", issue));
                throw new StartupException(
                        ExitCode.ASSETS_INVALID, blocking.size() + " blocking asset validation errors", null);
            }
            // G18: a content error degrades the load rather than refusing it, so one bad part cannot
            // stop a server the other ninety-nine are fine for.
            LOG.warn("{} blocking asset errors; affected content is unavailable this run", blocking.size());
            blocking.forEach(issue -> LOG.warn("  {}", issue));
        }
        LOG.info("assets loaded from {}", assetRoot);
        return index;
    }

    /** The teardown of D03-S5.6, in the native disposal order of D02-S5.7. */
    @Override
    public void close() {
        // Systems first, in reverse registration order, then the entities they were operating on —
        // so nothing releases a body a system is still about to read.
        world.dispose();
        physics.close();
        shapes.close();

        if (NativeResourceTracker.isEnabled() && NativeResourceTracker.outstanding() != 0) {
            LOG.error("native resources outstanding at shutdown: {}", NativeResourceTracker.describeOutstanding());
        }
    }
}
