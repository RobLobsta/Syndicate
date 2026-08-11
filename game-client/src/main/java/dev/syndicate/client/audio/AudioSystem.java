/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.audio;

import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.client.LocalPlayer;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.MaterialDef;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.damage.DamageEvent;
import dev.syndicate.core.damage.PartDestroyedEvent;
import dev.syndicate.core.damage.PartDetachedEvent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.EngineVoice;
import dev.syndicate.core.vehicle.VehicleProfile;
import dev.syndicate.core.vehicle.VehicleProfiles;
import dev.syndicate.model.AudioEvent;
import dev.syndicate.model.AudioMaterial;
import dev.syndicate.model.DestructionClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 25: what the match sounds like
 * (docs/04_entity_component_model.md#D04-S4.4 row 25, docs/15_vehicle_preparation_pipeline.md#D15-S8).
 *
 * <p>The bank has existed since the session before this one and had nothing to play it. This is the
 * thing that plays it, and the whole of its design is the two rules D15-S8 already fixed:
 *
 * <ul>
 *   <li><b>Engines are keyed on configuration, not on the car</b> (D15-R37a, DEC-047). Six loops
 *       cover every vehicle; which one a car plays, at what pitch and what gain, comes from its
 *       {@link EngineVoice} — so two cars sharing a V8 still differ, and a new car adds no asset.
 *   <li><b>One-shots are keyed on material and class, not on the part</b> (D15-R37). A hit on steel
 *       sounds like steel wherever it lands.
 * </ul>
 *
 * <p><b>Attenuated by distance from the listener</b>, which is the local player's car. Without it,
 * eight bots idling at the far end of an arena are eight engines at full volume, and the one a
 * player is driving is inaudible inside them.
 *
 * <p>Two of D15-R36's families are not played here, and are named rather than quietly skipped:
 * {@code TYRE_ROLL}/{@code TYRE_SKID} need per-wheel slip and surface, which the ray-cast wheel
 * computes but no component yet exposes; {@code WEAPON_FIRE}/{@code WEAPON_IMPACT} need events slots
 * 8 and 9 do not emit. Both sets of files exist in the bank and are silent until those arrive.
 */
public final class AudioSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(AudioSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 25;

    /** Metres past which a sound is inaudible. */
    public static final float MAX_AUDIBLE_M = 90f;

    /** Metres within which a sound is at full volume. */
    public static final float FULL_VOLUME_M = 8f;

    /** Master gain, so the whole mix can be pulled down without touching every call site. */
    public static final float MASTER_GAIN = 0.7f;

    /** Damage below which a hit gets no impact sound, matching the effect system's threshold. */
    public static final float MIN_IMPACT_DAMAGE = 6f;

    /** Damage at which an impact is played as {@code heavy}. */
    public static final float HEAVY_IMPACT_DAMAGE = 120f;

    /** Damage at which an impact is played as {@code medium}. */
    public static final float MEDIUM_IMPACT_DAMAGE = 35f;

    /** One-shots started in a single frame, past which the rest are dropped. */
    public static final int MAX_ONE_SHOTS_PER_FRAME = 8;

    private final SoundBank bank;
    private final AssetIndex assets;
    private final LocalPlayer localPlayer;

    private final Map<Integer, EngineLoop> engines = new TreeMap<>();
    private final List<OneShot> pending = new ArrayList<>();
    private final Vector3 listener = new Vector3();
    private final Vector3 scratch = new Vector3();

    private Family vehicles;
    private World world;
    private boolean warnedSilent;

    public AudioSystem(SoundBank bank, AssetIndex assets, LocalPlayer localPlayer) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.localPlayer = Objects.requireNonNull(localPlayer, "localPlayer");
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
        this.world = world;
        vehicles = world.family(ComponentQuery.all(VehicleChassisComponent.class, TransformComponent.class));
        world.events().subscribe(DamageEvent.class, this::onDamage);
        world.events().subscribe(PartDetachedEvent.class, this::onDetach);
        world.events().subscribe(PartDestroyedEvent.class, this::onPartDestroyed);
        if (!bank.isAvailable()) {
            LOG.warn("audio is unavailable; slot 25 runs and plays nothing (D15-S8)");
        }
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        if (!bank.isAvailable()) {
            pending.clear();
            return;
        }
        updateListener(world);
        playPending();
        updateEngines(world);
    }

    // ---- Listener -------------------------------------------------------------------

    private void updateListener(World world) {
        int vehicle = localPlayer.vehicleEntity(world);
        TransformComponent transform =
                vehicle == EntityId.NULL ? null : world.getComponent(vehicle, TransformComponent.class);
        if (transform != null) {
            transform.worldMatrix.getTranslation(listener);
        }
    }

    /**
     * Volume at a world position, {@code [0,1]}.
     *
     * <p>Inverse-square would be physically right and is wrong here: a car twenty metres away would
     * be a fortieth of the volume of one at three, and an arena brawl would be one loud engine and
     * seven silences. A linear roll-off past a full-volume radius keeps the fight audible, which is
     * what the sound is for.
     */
    private float attenuation(float x, float y, float z) {
        float distance = scratch.set(x, y, z).dst(listener);
        if (distance <= FULL_VOLUME_M) {
            return 1f;
        }
        if (distance >= MAX_AUDIBLE_M) {
            return 0f;
        }
        return 1f - (distance - FULL_VOLUME_M) / (MAX_AUDIBLE_M - FULL_VOLUME_M);
    }

    // ---- One-shots ------------------------------------------------------------------

    private void onDamage(DamageEvent event) {
        if (event.baseAmount() < MIN_IMPACT_DAMAGE || event.isPropagated()) {
            return;
        }
        AudioMaterial material = materialOf(event.targetPart());
        String severity = event.baseAmount() >= HEAVY_IMPACT_DAMAGE
                ? "heavy"
                : event.baseAmount() >= MEDIUM_IMPACT_DAMAGE ? "medium" : "light";
        Vector3 at = event.hitPointWorld();
        queue(AudioEvent.IMPACT, material.token() + "_" + severity, at.x, at.y, at.z, 1f);
    }

    private void onDetach(PartDetachedEvent event) {
        DestructionClass destruction = destructionOf(event.partEntity());
        positionOf(event.partEntity(), event.vehicleEntity());
        queue(AudioEvent.PART_DETACH, destruction.name().toLowerCase(Locale.ROOT), scratch.x, scratch.y, scratch.z, 1f);
    }

    /**
     * Glass gets its own event because D15-S8 says it is the one sound a player will notice missing.
     *
     * <p>Keyed on size by the part's own mass: a windscreen and a lamp lens both shatter, and the
     * difference between them is the only thing the two authored variants encode.
     */
    private void onPartDestroyed(PartDestroyedEvent event) {
        if (materialOf(event.partEntity()) != AudioMaterial.GLASS) {
            return;
        }
        PartType type = partTypeOf(event.partEntity());
        String size = type != null && type.massKg() >= 8f ? "large" : "small";
        positionOf(event.partEntity(), event.vehicleEntity());
        queue(AudioEvent.GLASS_SHATTER, size, scratch.x, scratch.y, scratch.z, 1f);
    }

    private void queue(AudioEvent event, String key, float x, float y, float z, float gain) {
        if (pending.size() >= MAX_ONE_SHOTS_PER_FRAME) {
            return;
        }
        pending.add(new OneShot(event, key, x, y, z, gain));
    }

    private void playPending() {
        for (int i = 0; i < pending.size(); i++) {
            OneShot shot = pending.get(i);
            Sound sound = bank.forKey(shot.event(), shot.key());
            if (sound == null) {
                if (!warnedSilent) {
                    LOG.warn("no sound for {}/{}; further misses are not logged", shot.event(), shot.key());
                    warnedSilent = true;
                }
                continue;
            }
            float volume = MASTER_GAIN * shot.gain() * attenuation(shot.x(), shot.y(), shot.z());
            if (volume > 0.01f) {
                sound.play(volume);
            }
        }
        pending.clear();
    }

    // ---- Engines --------------------------------------------------------------------

    /**
     * Starts, pitches and stops one looping voice per live vehicle.
     *
     * <p>The loop is started once and then only adjusted. Restarting it per frame — or per gear
     * change, of which there are none — is the single most obvious way to make a synthesised engine
     * sound synthesised, because every restart is an audible discontinuity at the loop point.
     */
    private void updateEngines(World world) {
        int[] entityIds = vehicles.snapshot();
        int count = vehicles.size();
        for (int i = 0; i < count; i++) {
            int vehicle = entityIds[i];
            VehicleChassisComponent chassis = world.getComponent(vehicle, VehicleChassisComponent.class);
            if (chassis == null) {
                continue;
            }
            EngineLoop loop = engines.get(vehicle);
            if (loop == null) {
                loop = start(vehicle, chassis);
                if (loop == null) {
                    continue;
                }
                engines.put(vehicle, loop);
            }
            drive(world, vehicle, loop);
        }
        stopDeadVehicles(world);
    }

    private EngineLoop start(int vehicle, VehicleChassisComponent chassis) {
        VehicleProfile profile = VehicleProfiles.byId(chassis.assemblyId);
        if (profile == null) {
            return null;
        }
        EngineVoice voice = profile.engineVoice();
        Sound sound = bank.get(voice.soundId());
        if (sound == null) {
            return null;
        }
        // Started silent: the first frame's pitch and gain are applied immediately below, and
        // starting at full volume would pop every engine in at the moment a match spawns.
        long handle = sound.loop(0f);
        return new EngineLoop(sound, handle, voice);
    }

    private void drive(World world, int vehicle, EngineLoop loop) {
        VelocityComponent velocity = world.getComponent(vehicle, VelocityComponent.class);
        VehicleStatsComponent stats = world.getComponent(vehicle, VehicleStatsComponent.class);
        PlayerInputComponent input = world.getComponent(vehicle, PlayerInputComponent.class);
        TransformComponent transform = world.getComponent(vehicle, TransformComponent.class);

        float speed = velocity == null ? 0f : velocity.linear.len();
        float topSpeed = stats == null ? 0f : stats.maxSpeedMps;
        float throttle = input == null ? 0f : Math.abs(input.throttle);
        float rpm = loop.voice().rpmFor(speed, topSpeed, throttle);

        float attenuation = 1f;
        if (transform != null) {
            transform.worldMatrix.getTranslation(scratch);
            attenuation = attenuation(scratch.x, scratch.y, scratch.z);
        }
        loop.sound().setPitch(loop.handle(), loop.voice().pitchAt(rpm));
        loop.sound().setVolume(loop.handle(), MASTER_GAIN * loop.voice().gainAt(rpm) * attenuation);
    }

    private void stopDeadVehicles(World world) {
        engines.entrySet().removeIf(entry -> {
            if (world.isAlive(entry.getKey())) {
                return false;
            }
            entry.getValue().sound().stop(entry.getValue().handle());
            return true;
        });
    }

    // ---- Lookups --------------------------------------------------------------------

    private AudioMaterial materialOf(int partEntity) {
        PartType type = partTypeOf(partEntity);
        if (type == null) {
            return MaterialDef.DEFAULT_AUDIO_MATERIAL;
        }
        MaterialDef material = assets.material(type.materialId());
        return material == null ? MaterialDef.DEFAULT_AUDIO_MATERIAL : material.audioMaterial();
    }

    private DestructionClass destructionOf(int partEntity) {
        PartType type = partTypeOf(partEntity);
        return type == null ? DestructionClass.RIGID : type.destructionClass();
    }

    private PartType partTypeOf(int partEntity) {
        PartRefComponent part = world.getComponent(partEntity, PartRefComponent.class);
        return part == null ? null : assets.partType(part.partTypeId);
    }

    /** Fills {@link #scratch} with a part's position, falling back to its vehicle's (see slot 24). */
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

    @Override
    public void dispose() {
        engines.values().forEach(loop -> loop.sound().stop(loop.handle()));
        engines.clear();
        pending.clear();
    }

    /** One vehicle's running engine: the sound, the voice instance playing it, and its parameters. */
    private record EngineLoop(Sound sound, long handle, EngineVoice voice) {}

    /** A one-shot waiting for the frame to play it. */
    private record OneShot(AudioEvent event, String key, float x, float y, float z, float gain) {}
}
