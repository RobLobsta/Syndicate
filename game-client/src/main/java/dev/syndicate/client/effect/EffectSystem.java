/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.effect;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.client.component.ParticleRefComponent;
import dev.syndicate.core.component.LifetimeComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.damage.DamageEvent;
import dev.syndicate.core.damage.PartDetachedEvent;
import dev.syndicate.core.damage.PartFracturedEvent;
import dev.syndicate.core.damage.VehicleDestroyedEvent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Schedule slot 24: turns simulation events into things a player can see happen
 * (docs/04_entity_component_model.md#D04-S4.4 row 24).
 *
 * <p>Every event this system listens to already changes the simulation — a hit removes health, a
 * fracture removes a part. What it does not do is <em>announce itself</em>. Without this system the
 * only sign a car has been hit is that a number nobody is shown got smaller; with it, the hit throws
 * sparks off the panel it landed on.
 *
 * <p><b>One entity per burst, not per particle</b> (D04-S4.2's {@code EFFECT} archetype). A burst
 * carries up to {@link ParticleRefComponent#MAX_PARTICLES} particles in flat arrays and expires
 * through the ordinary {@link LifetimeComponent}, so slot 16 cleans up after presentation exactly as
 * it does after debris and nothing here needs its own reaper.
 *
 * <p><b>Its randomness is its own</b> (G4). Scatter directions come from an unseeded {@link Random},
 * never from {@code world.random()}: cosmetic draws taken from a gameplay stream would advance it by
 * an amount that depends on how many frames were rendered, so two clients — or a client and a server
 * — watching the same match would diverge. This is the one place in the codebase where an unseeded
 * generator is correct, and it is correct precisely because nothing reads its output back.
 */
public final class EffectSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 24;

    /** The most bursts alive at once. Beyond this, new events are dropped rather than queued. */
    public static final int MAX_LIVE_BURSTS = 96;

    /** Newtons-free gravity for particles, metres per second squared. */
    public static final float PARTICLE_GRAVITY_MPS2 = -12f;

    /** Damage below which a hit is not worth a spark. Stops attrition ticks strobing the screen. */
    public static final float MIN_SPARK_DAMAGE = 6f;

    private final Random cosmetic = new Random();
    private final List<Burst> pending = new ArrayList<>();
    private final Vector3 scratch = new Vector3();

    private Family bursts;
    private World world;

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
        this.world = world;
        bursts = world.family(ComponentQuery.all(ParticleRefComponent.class, TransformComponent.class));

        world.events().subscribe(DamageEvent.class, this::onDamage);
        world.events().subscribe(PartFracturedEvent.class, this::onFracture);
        world.events().subscribe(PartDetachedEvent.class, this::onDetach);
        world.events().subscribe(VehicleDestroyedEvent.class, this::onVehicleDestroyed);
    }

    /**
     * @param dtSeconds real frame time; particles move in frame time because they are not simulated
     */
    @Override
    public void update(World world, float dtSeconds, long tick) {
        spawnPending(world);
        advance(world, dtSeconds);
    }

    /** Live bursts, for the renderer and for a test that wants to know something happened. */
    public Family bursts() {
        return bursts;
    }

    // ---- Event handling -------------------------------------------------------------
    //
    // Listeners run at the end of a tick, which is after slot 27 has torn down whatever the tick
    // destroyed. A fractured part is therefore already gone by the time its event arrives, so the
    // position is resolved here — at the latest moment the entity might still exist — and falls back
    // to the vehicle it was part of. A burst two metres from the true break is a burst; no burst at
    // all is a part that vanishes silently.

    private void onDamage(DamageEvent event) {
        if (event.baseAmount() < MIN_SPARK_DAMAGE || event.isPropagated()) {
            return;
        }
        Vector3 at = event.hitPointWorld();
        queue(ParticleRefComponent.Kind.SPARKS, at.x, at.y, at.z);
    }

    private void onFracture(PartFracturedEvent event) {
        positionOf(event.partEntity(), event.vehicleEntity());
        queue(ParticleRefComponent.Kind.SHARDS, scratch.x, scratch.y, scratch.z);
    }

    private void onDetach(PartDetachedEvent event) {
        positionOf(event.partEntity(), event.vehicleEntity());
        queue(ParticleRefComponent.Kind.DEBRIS_PUFF, scratch.x, scratch.y, scratch.z);
    }

    private void onVehicleDestroyed(VehicleDestroyedEvent event) {
        positionOf(event.vehicleEntity(), event.vehicleEntity());
        queue(ParticleRefComponent.Kind.SMOKE, scratch.x, scratch.y, scratch.z);
    }

    private void positionOf(int preferred, int fallback) {
        scratch.set(0f, 0f, 0f);
        TransformComponent transform = world.getComponent(preferred, TransformComponent.class);
        if (transform == null) {
            transform = world.getComponent(fallback, TransformComponent.class);
        }
        if (transform != null) {
            transform.worldMatrix.getTranslation(scratch);
        }
    }

    private void queue(ParticleRefComponent.Kind kind, float x, float y, float z) {
        if (pending.size() + bursts.size() >= MAX_LIVE_BURSTS) {
            return;
        }
        pending.add(new Burst(kind, x, y, z));
    }

    // ---- Burst lifecycle ------------------------------------------------------------

    private void spawnPending(World world) {
        for (int i = 0; i < pending.size(); i++) {
            create(world, pending.get(i));
        }
        pending.clear();
    }

    private void create(World world, Burst burst) {
        Entity entity = world.createEntity();
        int entityId = entity.id();

        TransformComponent transform = new TransformComponent();
        transform.position.set(burst.x, burst.y, burst.z);
        transform.worldMatrix.setToTranslation(burst.x, burst.y, burst.z);
        transform.dirty = false;
        world.addComponent(entityId, transform);

        ParticleRefComponent particles = new ParticleRefComponent();
        fill(particles, burst.kind);
        world.addComponent(entityId, particles);

        LifetimeComponent lifetime = new LifetimeComponent();
        lifetime.remainingS = particles.lifespanSeconds;
        lifetime.despawnPolicy = LifetimeComponent.DespawnPolicy.DESTROY;
        world.addComponent(entityId, lifetime);
    }

    /** Gives a burst the count, spread, speed, size, colour and lifespan its kind calls for. */
    private void fill(ParticleRefComponent particles, ParticleRefComponent.Kind kind) {
        particles.kind = kind;
        float speed;
        float size;
        switch (kind) {
            case SPARKS -> {
                particles.count = 14;
                particles.lifespanSeconds = 0.45f;
                particles.colour.set(1f, 0.78f, 0.32f, 1f);
                speed = 7f;
                size = 0.045f;
            }
            case SHARDS -> {
                particles.count = ParticleRefComponent.MAX_PARTICLES;
                particles.lifespanSeconds = 0.9f;
                particles.colour.set(0.85f, 0.88f, 0.95f, 1f);
                speed = 5.5f;
                size = 0.07f;
            }
            case DEBRIS_PUFF -> {
                particles.count = 18;
                particles.lifespanSeconds = 0.8f;
                particles.colour.set(0.55f, 0.52f, 0.48f, 1f);
                speed = 3.2f;
                size = 0.13f;
            }
            case SMOKE -> {
                particles.count = 24;
                particles.lifespanSeconds = 2.6f;
                particles.colour.set(0.22f, 0.22f, 0.24f, 1f);
                speed = 1.6f;
                size = 0.55f;
            }
            default -> throw new IllegalStateException("unhandled effect kind " + kind);
        }
        particles.ageSeconds = 0f;
        for (int i = 0; i < particles.count; i++) {
            // A cone about +Y rather than a sphere: a spark that starts by going into the panel it
            // came off is a spark nobody sees.
            float theta = (float) (cosmetic.nextDouble() * Math.PI * 2.0);
            float lift = 0.25f + (float) cosmetic.nextDouble() * 0.9f;
            float radial = (float) Math.sqrt(Math.max(0f, 1f - lift * lift));
            float magnitude = speed * (0.4f + (float) cosmetic.nextDouble() * 0.8f);
            particles.velocityX[i] = (float) Math.cos(theta) * radial * magnitude;
            particles.velocityY[i] = lift * magnitude;
            particles.velocityZ[i] = (float) Math.sin(theta) * radial * magnitude;
            particles.offsetX[i] = 0f;
            particles.offsetY[i] = 0f;
            particles.offsetZ[i] = 0f;
            particles.sizeM[i] = size * (0.6f + (float) cosmetic.nextDouble() * 0.8f);
        }
    }

    /** Integrates every live burst by the frame delta. */
    private void advance(World world, float dtSeconds) {
        int[] entityIds = bursts.snapshot();
        int count = bursts.size();
        boolean rises;
        for (int i = 0; i < count; i++) {
            ParticleRefComponent particles = world.getComponent(entityIds[i], ParticleRefComponent.class);
            if (particles == null) {
                continue;
            }
            particles.ageSeconds += dtSeconds;
            // Smoke is the one kind that goes up: it is buoyant, and a plume that falls reads as
            // dirt rather than as a car on fire.
            rises = particles.kind == ParticleRefComponent.Kind.SMOKE;
            float gravity = rises ? -PARTICLE_GRAVITY_MPS2 * 0.12f : PARTICLE_GRAVITY_MPS2;
            float drag = rises ? 0.9f : 0.98f;
            for (int p = 0; p < particles.count; p++) {
                particles.velocityY[p] += gravity * dtSeconds;
                particles.velocityX[p] *= drag;
                particles.velocityY[p] *= drag;
                particles.velocityZ[p] *= drag;
                particles.offsetX[p] += particles.velocityX[p] * dtSeconds;
                particles.offsetY[p] += particles.velocityY[p] * dtSeconds;
                particles.offsetZ[p] += particles.velocityZ[p] * dtSeconds;
            }
        }
    }

    @Override
    public void dispose() {
        pending.clear();
    }

    /** An event that has happened and not yet been given an entity. */
    private record Burst(ParticleRefComponent.Kind kind, float x, float y, float z) {}

    /** The colour a burst fades through as it ages, for the renderer. */
    public static float fade(ParticleRefComponent particles) {
        if (particles.lifespanSeconds <= 0f) {
            return 0f;
        }
        float t = particles.ageSeconds / particles.lifespanSeconds;
        return Math.max(0f, 1f - t * t);
    }
}
