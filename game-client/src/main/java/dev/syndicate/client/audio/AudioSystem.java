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
import dev.syndicate.core.component.BurnStackComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.damage.DamageEvent;
import dev.syndicate.core.damage.DebrisSettledEvent;
import dev.syndicate.core.damage.PartDestroyedEvent;
import dev.syndicate.core.damage.PartDetachedEvent;
import dev.syndicate.core.damage.WeaponFiredEvent;
import dev.syndicate.core.damage.WeaponImpactEvent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.EngineVoice;
import dev.syndicate.core.vehicle.VehicleProfile;
import dev.syndicate.core.vehicle.VehicleProfiles;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.AudioEvent;
import dev.syndicate.model.AudioMaterial;
import dev.syndicate.model.DestructionClass;
import dev.syndicate.model.WeaponFamily;
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
 * <p><b>Two halves that fail independently.</b> Engines are synthesised live and mixed by
 * {@link EngineMixer} onto their own stereo bus (DEC-056); everything else is a file from the
 * {@link SoundBank}. On a machine with no audio device both are silent and this still runs (G18),
 * and a missing sound bank does not take the engines with it.
 *
 * <ul>
 *   <li><b>An engine is not an asset</b> (D15-R37a3). This acquires a mixer slot per vehicle and
 *       publishes what the car is doing into it every frame — rpm, throttle, load, and how badly
 *       hurt it is. No file, no pitch ratio, and a new car adds nothing to the bank.
 *   <li><b>One-shots are keyed on material and class, not on the part</b> (D15-R37). A hit on steel
 *       sounds like steel wherever it lands.
 * </ul>
 *
 * <p><b>Attenuated by distance from the listener</b>, which is the local player's car. Without it,
 * eight bots idling at the far end of an arena are eight engines at full volume, and the one a
 * player is driving is inaudible inside them.
 *
 * <p><b>Every family D15-R36 names now plays.</b> Three of them were silent for a session not for
 * want of sounds — the files were in the bank and correct — but for want of anything to trigger them:
 * tyre roll and skid needed per-wheel slip, which the ray-cast wheel computed inside Bullet where
 * nothing could read it; weapon fire and impact needed events slots 8 and 9 did not emit; debris
 * settle needed a came-to-rest signal the debris path did not produce. All three now exist, and this
 * system is what they arrive at.
 *
 * <p><b>The sampled loops a vehicle still holds</b> are tyre roll, tyre skid, and a fire loop while
 * it burns. They are started once and then only adjusted, never restarted: every restart is an
 * audible discontinuity at the loop point. The engine has no such constraint any more, because it
 * has no loop point.
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

    /** Road speed above which tyre roll is at full voice. */
    public static final float TYRE_ROLL_FULL_SPEED_MPS = 28f;

    /** Road speed below which a tyre makes no rolling noise at all. */
    public static final float TYRE_ROLL_MIN_SPEED_MPS = 1.5f;

    /** Slip below which a tyre is gripping and does not squeal. */
    public static final float SKID_THRESHOLD = 0.18f;

    /** The surface every arena is made of today. Arenas do not yet declare one (DEV-014). */
    public static final String DEFAULT_SURFACE = "tarmac";

    /** Throttle above which a lift can later count as a lift, for the overrun. */
    public static final float OVERRUN_ARM_THROTTLE = 0.55f;

    /** Seconds between overrun one-shots, so a driver feathering the throttle does not machine-gun. */
    public static final float OVERRUN_COOLDOWN_S = 1.4f;

    /** Idle burn at no throttle, which fades out as revs rise into a genuine closed-throttle overrun. */
    public static final float IDLE_LOAD = 0.22f;

    private final SoundBank bank;
    private final AssetIndex assets;
    private final LocalPlayer localPlayer;
    private final EngineMixer mixer;
    private final EngineAudioOutput engineOutput;

    private final Map<Integer, VehicleVoices> engines = new TreeMap<>();
    private final List<OneShot> pending = new ArrayList<>();
    private final Vector3 listener = new Vector3();
    private final Vector3 scratch = new Vector3();
    private final Vector3 forward = new Vector3();
    private final Vector3 right = new Vector3();

    private Family vehicles;
    private World world;
    private boolean warnedSilent;

    public AudioSystem(SoundBank bank, AssetIndex assets, LocalPlayer localPlayer) {
        this(bank, assets, localPlayer, new EngineMixer(), null);
    }

    /**
     * @param output the engine bus, or {@code null} to open one. Injectable so a test can drive the
     *     mixer without an audio device.
     */
    AudioSystem(
            SoundBank bank, AssetIndex assets, LocalPlayer localPlayer, EngineMixer mixer, EngineAudioOutput output) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.localPlayer = Objects.requireNonNull(localPlayer, "localPlayer");
        this.mixer = Objects.requireNonNull(mixer, "mixer");
        this.engineOutput = output == null ? EngineAudioOutput.open(mixer) : output;
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
        world.events().subscribe(WeaponFiredEvent.class, this::onWeaponFired);
        world.events().subscribe(WeaponImpactEvent.class, this::onWeaponImpact);
        world.events().subscribe(DebrisSettledEvent.class, this::onDebrisSettled);
        if (!bank.isAvailable()) {
            LOG.warn("audio is unavailable; slot 25 runs and plays nothing (D15-S8)");
        }
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        // The two halves fail independently: the sample bank can be missing while the engine bus is
        // open, and on a machine with no device at all both are silent and this still runs (G18).
        updateListener(world);
        if (bank.isAvailable()) {
            playPending();
        } else {
            pending.clear();
        }
        updateEngines(world, dtSeconds);
    }

    // ---- The three families that used to be silent -----------------------------------

    /**
     * A weapon fired. One of seven, chosen by family.
     *
     * <p>{@code RAM} is filtered rather than mapped: its "fire" is a collision, which the impact bank
     * already covers, and the generator deliberately writes no {@code weapon_fire_ram.wav} for it.
     * Letting it through would ask the bank for a sound that does not exist once per ram.
     */
    private void onWeaponFired(WeaponFiredEvent event) {
        if (event.family() == WeaponFamily.RAM) {
            return;
        }
        Vector3 at = event.muzzleWorld();
        queue(AudioEvent.WEAPON_FIRE, event.family().name().toLowerCase(Locale.ROOT), at.x, at.y, at.z, 1f);
    }

    /** A shot arrived — on a car, on the floor, or on nothing. All three make a noise. */
    private void onWeaponImpact(WeaponImpactEvent event) {
        if (event.family() == WeaponFamily.RAM || !event.hitSomething()) {
            return;
        }
        Vector3 at = event.pointWorld();
        queue(AudioEvent.WEAPON_IMPACT, event.family().name().toLowerCase(Locale.ROOT), at.x, at.y, at.z, 1f);
    }

    /**
     * A shard came to rest.
     *
     * <p>Quieter than the impact that produced it, because a settle is the end of an event rather
     * than the event: a fracture throws dozens of shards, and playing each landing at full volume
     * turns the tail of every explosion into a louder sound than the explosion.
     */
    private void onDebrisSettled(DebrisSettledEvent event) {
        // A shard whose part named no material carries an empty id, which AssetId rejects outright
        // rather than resolving to nothing — so the validity test comes before the lookup, and the
        // default audio material carries the fallback.
        MaterialDef material =
                AssetId.isValid(event.materialId()) ? assets.material(AssetId.of(event.materialId())) : null;
        AudioMaterial audio = material == null ? MaterialDef.DEFAULT_AUDIO_MATERIAL : material.audioMaterial();
        Vector3 at = event.pointWorld();
        queue(AudioEvent.DEBRIS_SETTLE, audio.token(), at.x, at.y, at.z, DEBRIS_SETTLE_GAIN);
    }

    /** How loud a shard's landing is relative to the hit that threw it. */
    private static final float DEBRIS_SETTLE_GAIN = 0.45f;

    // ---- Listener -------------------------------------------------------------------

    private void updateListener(World world) {
        int vehicle = localPlayer.vehicleEntity(world);
        TransformComponent transform =
                vehicle == EntityId.NULL ? null : world.getComponent(vehicle, TransformComponent.class);
        if (transform == null) {
            return;
        }
        transform.worldMatrix.getTranslation(listener);
        // The mixer pans against the car's own axes rather than the world's, so a rival coming up
        // the inside is on the inside and not merely somewhere along +X.
        forward.set(0f, 0f, -1f).rot(transform.worldMatrix).nor();
        right.set(1f, 0f, 0f).rot(transform.worldMatrix).nor();
        mixer.setListener(new EngineMixer.Listener(
                listener.x, listener.y, listener.z, forward.x, forward.y, forward.z, right.x, right.y, right.z));
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

    // ---- Per-vehicle looping voices --------------------------------------------------

    /**
     * Starts, pitches and stops every looping voice a live vehicle has.
     *
     * <p>A car can be running up to four at once — exhaust, induction, tyre roll and tyre skid — and
     * a burning one adds a fifth. Each is started once, silent, and then only adjusted. Restarting a
     * loop per frame, or per state change, is the single most obvious way to make a synthesised
     * engine sound synthesised, because every restart is an audible discontinuity at the loop point.
     */
    private void updateEngines(World world, float dtSeconds) {
        int[] entityIds = vehicles.snapshot();
        int count = vehicles.size();
        for (int i = 0; i < count; i++) {
            int vehicle = entityIds[i];
            VehicleChassisComponent chassis = world.getComponent(vehicle, VehicleChassisComponent.class);
            if (chassis == null) {
                continue;
            }
            VehicleVoices voices = engines.get(vehicle);
            if (voices == null) {
                voices = start(vehicle, chassis);
                if (voices == null) {
                    continue;
                }
                engines.put(vehicle, voices);
            }
            drive(world, vehicle, voices, dtSeconds);
        }
        stopDeadVehicles(world, dtSeconds);
    }

    /**
     * Brings a vehicle's voices up, and announces it with an ignition.
     *
     * <p>The start one-shot is the reason a match no longer opens with eight engines already idling.
     * It is played at the car's own idle speed relative to the 800 rpm the bank authored, which costs
     * no extra asset and is the same trick the loop uses for its rev range.
     */
    private VehicleVoices start(int vehicle, VehicleChassisComponent chassis) {
        VehicleProfile profile = VehicleProfiles.byId(chassis.assemblyId);
        if (profile == null) {
            return null;
        }
        EngineVoice voice = profile.engineVoice();
        VehicleVoices voices = new VehicleVoices(voice, vehicle);
        // A slot, not a file. The ignition that used to be a one-shot is the run state's first
        // phase, so the engine cranks and catches at this car's own idle rather than at 800 rpm.
        voices.engineSlot = mixer.acquire(
                voice.configuration(),
                voice.induction(),
                voice.idleRpm(),
                voice.redlineRpm(),
                voice.peakPowerW(),
                vehicle);

        voices.tyreRoll = bank.forKey(AudioEvent.TYRE_ROLL, DEFAULT_SURFACE);
        if (voices.tyreRoll != null) {
            voices.tyreRollHandle = voices.tyreRoll.loop(0f);
        }
        voices.tyreSkid = bank.forKey(AudioEvent.TYRE_SKID, DEFAULT_SURFACE);
        if (voices.tyreSkid != null) {
            voices.tyreSkidHandle = voices.tyreSkid.loop(0f);
        }
        return voices;
    }

    /** Adjusts every voice this vehicle is running to what it is doing this frame. */
    private void drive(World world, int vehicle, VehicleVoices voices, float dtSeconds) {
        VelocityComponent velocity = world.getComponent(vehicle, VelocityComponent.class);
        VehicleStatsComponent stats = world.getComponent(vehicle, VehicleStatsComponent.class);
        PlayerInputComponent input = world.getComponent(vehicle, PlayerInputComponent.class);
        TransformComponent transform = world.getComponent(vehicle, TransformComponent.class);
        VehicleChassisComponent chassis = world.getComponent(vehicle, VehicleChassisComponent.class);

        float speed = velocity == null ? 0f : velocity.linear.len();
        float topSpeed = stats == null ? 0f : stats.maxSpeedMps;
        float throttle = input == null ? 0f : Math.abs(input.throttle);
        EngineVoice voice = voices.voice;
        float demandRpm = voice.rpmFor(speed, topSpeed, throttle);

        scratch.set(0f, 0f, 0f);
        if (transform != null) {
            transform.worldMatrix.getTranslation(scratch);
        }
        float attenuation = attenuation(scratch.x, scratch.y, scratch.z);
        float base = MASTER_GAIN * attenuation;

        // Load, not throttle, is what decides whether a cylinder burns. They differ in exactly one
        // place and it is the important one: a closed throttle at high rpm is an engine pumping air
        // and not burning it, which is an overrun, while a closed throttle at idle is still a
        // running engine. The idle term fades out as revs rise, which is that distinction.
        float idleLoad = IDLE_LOAD * (1f - voice.revFraction(demandRpm));
        float load = Math.max(throttle, idleLoad);

        float health = healthOf(world, chassis);
        // Remembered so the shutdown can go on sounding from where the wreck is after the entity
        // has gone and its transform with it.
        voices.lastX = scratch.x;
        voices.lastY = scratch.y;
        voices.lastZ = scratch.z;
        voices.lastHealth = health;
        EngineSynth.State engineState = voices.run.advance(dtSeconds, demandRpm, throttle, load, health);
        if (voices.engineSlot >= 0) {
            mixer.publish(
                    voices.engineSlot,
                    new EngineMixer.VoiceUpdate(
                            engineState, scratch.x, scratch.y, scratch.z, voice.gainAt(engineState.rpm())));
        }

        updateTyres(world, chassis, voices, speed, base);
        updateLift(voice, voices, engineState.rpm(), throttle, dtSeconds);
        updateFire(world, vehicle, voices, base);

        voices.previousThrottle = throttle;
    }

    /**
     * How healthy a vehicle's chassis is, in {@code [0,1]}.
     *
     * <p>The chassis stands for the whole car here. There is no engine part in the slot graph
     * (D05-S4.2 has no such category), so the honest reading of "how badly hurt is this vehicle" is
     * the part everything else hangs off. A car with a missing door does not misfire; a car whose
     * chassis is at a third of its hit points has been hit enough that it should.
     */
    private float healthOf(World world, VehicleChassisComponent chassis) {
        if (chassis == null || chassis.chassisPartEntity == EntityId.NULL) {
            return 1f;
        }
        HealthComponent health = world.getComponent(chassis.chassisPartEntity, HealthComponent.class);
        return health == null ? 1f : clamp01(health.healthFraction);
    }

    /**
     * Blends the two tyre loops from what the wheels are actually doing.
     *
     * <p>Roll rises with road speed; skid rises with the slip {@code VehicleControlSystem} (7) now
     * mirrors off each {@code btWheelInfo}. Both are gated on a wheel being <em>in contact</em>,
     * because a car in mid-air makes no tyre noise however fast its wheels are turning — and D06's
     * ray-cast wheel will happily report grip on a wheel carrying no load at all (DISC-012), which is
     * why the gate is on suspension force rather than on the contact flag alone.
     */
    private void updateTyres(
            World world, VehicleChassisComponent chassis, VehicleVoices voices, float speed, float base) {

        if (chassis == null) {
            return;
        }
        int inContact = 0;
        float worstSkid = 0f;
        for (int i = 0; i < chassis.wheelCount; i++) {
            WheelControllerComponent wheel =
                    world.getComponent(chassis.wheelEntities[i], WheelControllerComponent.class);
            if (wheel == null || !wheel.isInContact) {
                continue;
            }
            inContact++;
            worstSkid = Math.max(worstSkid, wheel.skid);
        }
        float grounded = chassis.wheelCount == 0 ? 0f : (float) inContact / chassis.wheelCount;

        float rollFraction =
                clamp01((speed - TYRE_ROLL_MIN_SPEED_MPS) / (TYRE_ROLL_FULL_SPEED_MPS - TYRE_ROLL_MIN_SPEED_MPS));
        float skidFraction = clamp01((worstSkid - SKID_THRESHOLD) / (1f - SKID_THRESHOLD));

        if (voices.tyreRoll != null) {
            // A rolling tyre's pitch rises with speed as well as its volume, because the tread-block
            // rate is a function of road speed. Held to a narrow range: a tyre is not an engine.
            voices.tyreRoll.setPitch(voices.tyreRollHandle, 0.75f + 0.5f * rollFraction);
            voices.tyreRoll.setVolume(voices.tyreRollHandle, base * grounded * rollFraction * TYRE_ROLL_GAIN);
        }
        if (voices.tyreSkid != null) {
            voices.tyreSkid.setVolume(voices.tyreSkidHandle, base * grounded * skidFraction * TYRE_SKID_GAIN);
        }
    }

    /** How loud tyre roll gets at full speed, under the engine. */
    private static final float TYRE_ROLL_GAIN = 0.30f;

    /** How loud a full slide gets. Above roll, because a squeal is information. */
    private static final float TYRE_SKID_GAIN = 0.55f;

    /**
     * Fires the one-shots that belong to lifting off: the overrun crackle and the turbo's release.
     *
     * <p>Both need the previous frame's throttle, which is why {@link VehicleVoices} keeps it. A lift
     * is a transition and there is no other way to see one.
     */
    private void updateLift(EngineVoice voice, VehicleVoices voices, float rpm, float throttle, float dtSeconds) {
        // The real frame delta, which slot 25 is handed (DEC-049) — not Gdx.graphics, which a
        // capture run and a headless test both make a liar of.
        voices.overrunCooldownS = Math.max(0f, voices.overrunCooldownS - dtSeconds);
        boolean lifted = voices.previousThrottle > OVERRUN_ARM_THROTTLE && throttle <= EngineVoice.LIFT_THROTTLE;
        if (!lifted) {
            return;
        }
        // The overrun is no longer a one-shot laid over the engine: dropping the load *is* the
        // overrun, and the synthesiser is already doing it by the time this runs. What remains is
        // the blow-off, which is a genuine transient and has to be triggered.
        voices.overrunCooldownS = OVERRUN_COOLDOWN_S;
        if (voices.engineSlot >= 0 && voice.shouldRelease(rpm, throttle, voices.previousThrottle)) {
            mixer.triggerRelease(voices.engineSlot);
        }
    }

    /**
     * Starts and stops the fire loop as a vehicle burns.
     *
     * <p>{@code DamageSystem} (12) has run a burn timer since Phase 5 with nothing audible attached
     * to it, which meant an incendiary hit set a car alight and the car went on sounding exactly as
     * before. The stack count drives the gain, so a car hit twice by a flamer roars rather than
     * crackling.
     */
    private void updateFire(World world, int vehicle, VehicleVoices voices, float base) {
        BurnStackComponent burn = world.getComponent(vehicle, BurnStackComponent.class);
        int stacks = burn == null ? 0 : burn.stackCount;

        if (stacks <= 0) {
            if (voices.fire != null) {
                voices.fire.stop(voices.fireHandle);
                voices.fire = null;
            }
            return;
        }
        if (voices.fire == null) {
            Sound fire = bank.get(FIRE_LOOP_SOUND_ID);
            if (fire == null) {
                return;
            }
            voices.fire = fire;
            voices.fireHandle = fire.loop(0f);
        }
        float intensity = Math.min(1f, stacks / (float) FIRE_FULL_STACKS);
        voices.fire.setVolume(voices.fireHandle, base * FIRE_GAIN * (0.45f + 0.55f * intensity));
    }

    /**
     * The fire loop's asset id.
     *
     * <p>Spelled out rather than reconstructed through {@code forKey}, because this is the one sound
     * in the bank with no key: there is a single fire, not one per material or per class, so its id
     * is the bare event token and {@code forKey} with a key would ask for a file that does not exist.
     */
    private static final String FIRE_LOOP_SOUND_ID = "fire_loop";

    /** Burn stacks at which a fire is at full voice. */
    private static final int FIRE_FULL_STACKS = 3;

    /** How loud a burning car is. */
    private static final float FIRE_GAIN = 0.5f;

    /**
     * Silences and forgets every voice of a vehicle that has left the world.
     *
     * <p>A dead car gets its shutdown one-shot on the way out, which is the other half of the
     * ignition — and the only moment in a match when an engine stopping is a thing a player hears
     * rather than infers from a car that has stopped moving.
     */
    private void stopDeadVehicles(World world, float dtSeconds) {
        engines.entrySet().removeIf(entry -> {
            VehicleVoices voices = entry.getValue();
            if (world.isAlive(entry.getKey())) {
                return false;
            }
            // A dead car does not fall silent, it winds down — so the voice outlives the entity by
            // the length of a shutdown, still in the place the wreck is, and is only then released.
            voices.run.beginShutdown();
            voices.stopSampledLoops();
            if (voices.engineSlot >= 0) {
                EngineSynth.State state = voices.run.advance(dtSeconds, 0f, 0f, 0f, voices.lastHealth);
                mixer.publish(
                        voices.engineSlot,
                        new EngineMixer.VoiceUpdate(state, voices.lastX, voices.lastY, voices.lastZ, 1f));
            }
            if (!voices.run.isFinished()) {
                return false;
            }
            mixer.release(voices.engineSlot);
            return true;
        });
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : Math.min(value, 1f);
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
        // The bus goes first: its thread is still pulling on the mixer, and releasing slots out
        // from under a live render is the one ordering that can produce a torn block (G19).
        engineOutput.dispose();
        for (VehicleVoices voices : engines.values()) {
            voices.stopSampledLoops();
            mixer.release(voices.engineSlot);
        }
        engines.clear();
        pending.clear();
    }

    /**
     * Every looping voice one vehicle is running, and the two scraps of state a transition needs.
     *
     * <p>A class rather than a record because it is mutable by design: the handles are assigned as
     * loops start, and {@link #previousThrottle} and {@link #overrunCooldownS} exist precisely to be
     * written every frame. A lift is a transition, and a transition cannot be detected without
     * remembering the previous side of it.
     */
    private static final class VehicleVoices {

        private final EngineVoice voice;
        private final EngineRunState run;

        /** This vehicle's slot in the mixer, or {@code -1} when every slot was taken. */
        private int engineSlot = -1;

        private Sound tyreRoll;
        private long tyreRollHandle;
        private Sound tyreSkid;
        private long tyreSkidHandle;
        private Sound fire;
        private long fireHandle;

        private float previousThrottle;
        private float overrunCooldownS;
        private float lastX;
        private float lastY;
        private float lastZ;
        private float lastHealth = 1f;

        VehicleVoices(EngineVoice voice, int vehicle) {
            this.voice = voice;
            this.run = new EngineRunState(voice.configuration().cylinders(), voice.idleRpm(), vehicle);
        }

        /** Stops the loops that are still files. The engine is the mixer's and outlives this. */
        void stopSampledLoops() {
            if (tyreRoll != null) {
                tyreRoll.stop(tyreRollHandle);
                tyreRoll = null;
            }
            if (tyreSkid != null) {
                tyreSkid.stop(tyreSkidHandle);
                tyreSkid = null;
            }
            if (fire != null) {
                fire.stop(fireHandle);
                fire = null;
            }
        }
    }

    /** A one-shot waiting for the frame to play it. */
    private record OneShot(AudioEvent event, String key, float x, float y, float z, float gain) {}
}
