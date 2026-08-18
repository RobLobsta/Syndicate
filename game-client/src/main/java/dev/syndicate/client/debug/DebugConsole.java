/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.debug;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.client.ClientLoop;
import dev.syndicate.client.ClientRuntime;
import dev.syndicate.client.LocalPlayer;
import dev.syndicate.core.arena.TerrainField;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.StructureDef;
import dev.syndicate.core.component.BotControllerComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.StructureComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.damage.DamageEvent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.structure.StructureFactory;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.DamageType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live testing console: what a tuning pass needs that a rebuild cannot give it.
 *
 * <p>Every number this project has is compiled in, and a handling pass that needs a rebuild per
 * value is a handling pass nobody finishes. This is the tool that makes the tuning step of the
 * roadmap possible: stop time and look at a frame, spawn the thing you want to test next to you,
 * turn the bots off so the arena holds still, hit a building and watch what comes off it.
 *
 * <p><b>It acts through the simulation's own entry points, never around them</b> — that is the rule
 * that keeps it honest and the reason it can be trusted as a diagnostic. Spawning goes through
 * {@code SpawnQueue} and {@code StructureFactory}, damage is a {@code DamageEvent} on the bus
 * exactly as a weapon's is, and time control scales the real seconds the loop admits rather than
 * {@code TICK_DT} (G2). Nothing here writes a component another system owns, so what you watch
 * through the console is the game and not a special case of it.
 *
 * <p><b>G6 and this class.</b> The cosmetic/authoritative split says cosmetic state must never feed
 * back into gameplay, and a console plainly does affect gameplay — that is what it is for. It is
 * not a violation but an exception with a boundary: the console is a <em>development affordance</em>
 * that issues the same requests a player or a weapon issues, and it is off unless somebody opens
 * it. See DEC-101.
 */
public final class DebugConsole {

    private static final Logger LOG = LoggerFactory.getLogger(DebugConsole.class);

    /** The time scales the console cycles through. Zero is a hard pause. */
    public static final float[] TIME_SCALES = {1f, 0.5f, 0.25f, 0.1f, 0f, 2f};

    /** Metres ahead of the camera focus that a spawned thing is placed. */
    public static final float SPAWN_AHEAD_M = 14f;

    /**
     * Damage one press of the "hit it" row deals, in the same units a weapon deals.
     *
     * <p>Structure-scale rather than car-scale. A masonry floor is two to seven thousand hit
     * points behind the full 60 armour, so the 900 this started at needed a dozen presses to
     * show anything — which made the row read as broken rather than as weak.
     */
    public static final float TEST_HIT_DAMAGE = 2500f;

    /** One titled group of rows. */
    public record Section(String title, List<DebugCommand> rows) {}

    private final ClientRuntime runtime;
    private final ClientLoop loop;
    private final LocalPlayer localPlayer;
    private final List<Section> sections = new ArrayList<>();

    private boolean open;
    private int cursorSection;
    private int cursorRow;
    private int timeScaleIndex;
    private boolean botsFrozen;
    private String lastMessage = "";

    private final List<AssetId> vehicleIds = new ArrayList<>();
    private final List<AssetId> structureIds = new ArrayList<>();
    private int vehicleChoice;
    private int structureChoice;

    private Family structureParts;
    private Family bots;

    private final Vector3 scratch = new Vector3();
    private final Matrix4 spawnTransform = new Matrix4();

    private final boolean[] wasDown = new boolean[Input.Keys.MAX_KEYCODE + 1];

    /**
     * The console actions a launch asked for, or null.
     *
     * <p>A static launch option for the reason {@code ScriptedSource.setLaunchScript} is one: a
     * capture has no keyboard, so without this the console is the one part of the client that can
     * never be photographed or asserted on — and the console is precisely the tool for producing
     * evidence about everything else. Threading it through four constructors would put a
     * capture-only flag in every signature between here and {@code ClientMain}.
     */
    private static String launchScript;

    /** Records the {@code --console} a launch asked for. Null clears it. */
    public static void setLaunchScript(String script) {
        launchScript = script;
    }

    /** What a launch script still has to do, soonest first. */
    private final List<ScriptedAction> scripted = new ArrayList<>();

    private float scriptElapsedSeconds;

    /**
     * One console action a launch script asked for, at a time or at a frame.
     *
     * <p>Frames as well as seconds, and frames are what a capture should use. A headless run under
     * {@code xvfb} renders at two to five frames a second, so the first frame's delta swallows the
     * whole asset load and every "t=" action inside the first several seconds fires at once on the
     * same frame. {@code --capture-frame} already counts frames; a script that counts them too
     * lands its actions in a fixed relationship to the photograph.
     */
    private record ScriptedAction(float atSeconds, int atFrame, String action) {

        boolean isDue(float elapsedSeconds, int frame) {
            return atFrame >= 0 ? frame >= atFrame : elapsedSeconds >= atSeconds;
        }

        /** Frame-keyed actions sort before time-keyed ones only within their own kind. */
        float sortKey() {
            return atFrame >= 0 ? atFrame : atSeconds;
        }
    }

    private int scriptFrame;

    public DebugConsole(ClientRuntime runtime, ClientLoop loop, LocalPlayer localPlayer) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.localPlayer = Objects.requireNonNull(localPlayer, "localPlayer");
        buildRosters();
        buildSections();
        parseLaunchScript(launchScript);
    }

    /**
     * Parses {@code open | t=2 hit | t=5 flatten} into a timeline.
     *
     * <p>Deliberately the same shape as {@code ScriptedSource}'s: segments separated by {@code |},
     * a time and then what happens. Anything unrecognised is logged and skipped rather than
     * refused, because a capture that dies on a typo in a debug flag has cost more than it saved.
     */
    void parseLaunchScript(String script) {
        scripted.clear();
        scriptElapsedSeconds = 0f;
        scriptFrame = 0;
        if (script == null || script.isBlank()) {
            return;
        }
        for (String segment : script.split("\\|")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equals("open")) {
                open = true;
                continue;
            }
            float at = 0f;
            int atFrame = -1;
            String action = trimmed;
            if (trimmed.startsWith("t=") || trimmed.startsWith("f=")) {
                boolean byFrame = trimmed.charAt(0) == 'f';
                String[] words = trimmed.split("\\s+", 2);
                try {
                    if (byFrame) {
                        atFrame = Integer.parseInt(words[0].substring(2));
                    } else {
                        at = Float.parseFloat(words[0].substring(2));
                    }
                } catch (NumberFormatException e) {
                    LOG.warn("console script segment \"{}\" has no readable time; skipped", trimmed);
                    continue;
                }
                action = words.length > 1 ? words[1].trim() : "";
            }
            if (!action.isEmpty()) {
                scripted.add(new ScriptedAction(at, atFrame, action));
            }
        }
        scripted.sort((a, b) -> Float.compare(a.sortKey(), b.sortKey()));
        if (!scripted.isEmpty()) {
            LOG.info("console script has {} action(s)", scripted.size());
        }
    }

    /** Runs whatever the launch script owes by now. Called once per frame, before the keyboard. */
    private void advanceScript(float frameDeltaSeconds) {
        if (scripted.isEmpty()) {
            return;
        }
        scriptElapsedSeconds += frameDeltaSeconds;
        scriptFrame++;
        // One action per frame at most, so three hits scripted on consecutive frames land on three
        // frames rather than all on the first one that is late.
        for (int i = 0; i < scripted.size(); i++) {
            if (scripted.get(i).isDue(scriptElapsedSeconds, scriptFrame)) {
                String action = scripted.remove(i).action();
                LOG.info("console script at frame {}: {}", scriptFrame, action);
                run(action);
                break;
            }
        }
    }

    /**
     * Runs one named action, which is the whole vocabulary a script and a key press share.
     *
     * @return true when the name was recognised
     */
    public boolean run(String action) {
        switch (action) {
            case "open" -> open = true;
            case "close" -> open = false;
            case "pause" -> {
                timeScaleIndex = indexOfScale(0f);
                loop.setTimeScale(0f);
                lastMessage = "paused";
            }
            case "resume" -> {
                timeScaleIndex = 0;
                loop.setTimeScale(1f);
                lastMessage = "resumed";
            }
            case "slow" -> {
                timeScaleIndex = indexOfScale(0.25f);
                loop.setTimeScale(0.25f);
                lastMessage = "time scale 0.25x";
            }
            case "step" -> {
                loop.stepOnce();
                lastMessage = "stepped one tick";
            }
            case "hit" -> hitNearestStructure();
            case "flatten" -> flattenNearestStructure();
            case "freeze-ai" -> {
                botsFrozen = true;
                lastMessage = "enemy AI frozen";
            }
            case "thaw-ai" -> {
                botsFrozen = false;
                lastMessage = "enemy AI active";
            }
            case "spawn-vehicle" -> spawnVehicle();
            case "spawn-structure" -> spawnStructure();
            case "night" -> cycleNight();
            default -> {
                LOG.warn("console action \"{}\" is not recognised", action);
                return false;
            }
        }
        return true;
    }

    /** Whether the console is showing. */
    public boolean isOpen() {
        return open;
    }

    /** The sections, for the overlay to draw. */
    public List<Section> sections() {
        return List.copyOf(sections);
    }

    public int cursorSection() {
        return cursorSection;
    }

    public int cursorRow() {
        return cursorRow;
    }

    /** The last thing the console did, shown as a status line so a press has visible feedback. */
    public String lastMessage() {
        return lastMessage;
    }

    // ---- Input ----------------------------------------------------------------------

    /**
     * Reads the keyboard once per frame.
     *
     * <p>Edge-triggered on every key: a console whose rows fired while a key was held would spawn
     * sixty cars in a second. Deliberately polled here rather than routed through
     * {@code InputRouter}, because the console must keep working when the simulation is paused and
     * the input collection system is therefore not running.
     */
    public void handleInput(float frameDeltaSeconds) {
        advanceScript(frameDeltaSeconds);
        if (pressed(Input.Keys.GRAVE) || pressed(Input.Keys.F1)) {
            open = !open;
            lastMessage = open ? "console open — arrows to move, enter to activate" : "";
        }
        if (!open) {
            return;
        }
        if (pressed(Input.Keys.DOWN)) {
            moveCursor(1);
        }
        if (pressed(Input.Keys.UP)) {
            moveCursor(-1);
        }
        if (pressed(Input.Keys.RIGHT)) {
            cursorSection = (cursorSection + 1) % sections.size();
            cursorRow = firstActionable(cursorSection, 0, 1);
        }
        if (pressed(Input.Keys.LEFT)) {
            cursorSection = (cursorSection + sections.size() - 1) % sections.size();
            cursorRow = firstActionable(cursorSection, 0, 1);
        }
        if (pressed(Input.Keys.ENTER) || pressed(Input.Keys.SPACE)) {
            activateSelected();
        }
        // The two controls worth a dedicated key, because they are the ones used while watching
        // something rather than while reading the menu.
        if (pressed(Input.Keys.P)) {
            cycleTimeScale();
        }
        if (pressed(Input.Keys.PERIOD)) {
            loop.stepOnce();
            lastMessage = "stepped one tick";
        }
    }

    private boolean pressed(int key) {
        boolean down = Gdx.input.isKeyPressed(key);
        boolean edge = down && !wasDown[key];
        wasDown[key] = down;
        return edge;
    }

    private void moveCursor(int direction) {
        int rows = sections.get(cursorSection).rows().size();
        cursorRow = firstActionable(cursorSection, Math.floorMod(cursorRow + direction, rows), direction);
    }

    /** The next selectable row from {@code start}, so the cursor never rests on a readout. */
    private int firstActionable(int section, int start, int direction) {
        List<DebugCommand> rows = sections.get(section).rows();
        for (int i = 0; i < rows.size(); i++) {
            int index = Math.floorMod(start + i * direction, rows.size());
            if (rows.get(index).actionable()) {
                return index;
            }
        }
        return start;
    }

    private void activateSelected() {
        List<DebugCommand> rows = sections.get(cursorSection).rows();
        if (cursorRow < rows.size()) {
            rows.get(cursorRow).activate();
        }
    }

    // ---- The rows -------------------------------------------------------------------

    private void buildRosters() {
        InMemoryAssetIndex assets = runtime.assets();
        assets.assemblies().keySet().stream().sorted().forEach(vehicleIds::add);
        assets.structures().keySet().stream().sorted().forEach(structureIds::add);
    }

    private void buildSections() {
        sections.add(new Section(
                "TIME",
                List.of(
                        DebugCommand.of("time scale", () -> formatScale(loop.timeScale()), this::cycleTimeScale),
                        DebugCommand.of(
                                "pause / resume",
                                () -> loop.timeScale() == 0f ? "PAUSED" : "running",
                                this::togglePause),
                        DebugCommand.of("step one tick  [.]", () -> "", () -> {
                            loop.stepOnce();
                            lastMessage = "stepped one tick";
                        }),
                        DebugCommand.readout("tick", () -> Long.toString(loop.tick())))));

        sections.add(new Section(
                "SPAWN",
                List.of(
                        DebugCommand.of(
                                "vehicle",
                                () -> choiceOf(vehicleIds, vehicleChoice),
                                () -> vehicleChoice = next(vehicleIds, vehicleChoice)),
                        DebugCommand.of("> spawn it", () -> "", this::spawnVehicle),
                        DebugCommand.of(
                                "structure",
                                () -> choiceOf(structureIds, structureChoice),
                                () -> structureChoice = next(structureIds, structureChoice)),
                        DebugCommand.of("> place it", () -> "", this::spawnStructure))));

        sections.add(new Section(
                "AI",
                List.of(
                        DebugCommand.of("enemy AI", () -> botsFrozen ? "FROZEN" : "active", this::toggleBots),
                        DebugCommand.readout("bots", () -> Integer.toString(botCount())))));

        sections.add(new Section(
                "DAMAGE",
                List.of(
                        DebugCommand.of("hit nearest structure", () -> "", this::hitNearestStructure),
                        DebugCommand.of("flatten nearest structure", () -> "", this::flattenNearestStructure),
                        DebugCommand.readout("structures", () -> Integer.toString(structureCount())),
                        DebugCommand.readout("live structure parts", () -> Integer.toString(structurePartCount())))));

        sections.add(new Section(
                "VIEW",
                List.of(
                        DebugCommand.of("night", () -> String.format("%.2f", nightFraction()), this::cycleNight),
                        DebugCommand.readout(
                                "models drawn",
                                () -> Integer.toString(
                                        runtime.provider().renderSystem().drawnThisFrame())),
                        DebugCommand.readout("fps", () -> Integer.toString(Gdx.graphics.getFramesPerSecond())),
                        DebugCommand.readout(
                                "entities",
                                () -> Integer.toString(runtime.world().entityCount())))));
    }

    // ---- Actions --------------------------------------------------------------------

    private void cycleTimeScale() {
        timeScaleIndex = (timeScaleIndex + 1) % TIME_SCALES.length;
        loop.setTimeScale(TIME_SCALES[timeScaleIndex]);
        lastMessage = "time scale " + formatScale(TIME_SCALES[timeScaleIndex]);
    }

    private void togglePause() {
        if (loop.timeScale() == 0f) {
            timeScaleIndex = 0;
            loop.setTimeScale(1f);
            lastMessage = "resumed";
        } else {
            timeScaleIndex = indexOfScale(0f);
            loop.setTimeScale(0f);
            lastMessage = "paused";
        }
    }

    private static int indexOfScale(float scale) {
        for (int i = 0; i < TIME_SCALES.length; i++) {
            if (TIME_SCALES[i] == scale) {
                return i;
            }
        }
        return 0;
    }

    private static String formatScale(float scale) {
        return scale == 0f ? "PAUSED" : String.format("%.2gx", scale);
    }

    /**
     * Queues a vehicle in front of the player, through {@code SpawnQueue}.
     *
     * <p>The queue rather than {@code VehicleFactory} directly, because slot 5 is where a spawn is
     * allowed to happen: building a vehicle mid-frame would add bodies to a Bullet world that is
     * part-way through its step, and the crash would land somewhere else entirely.
     */
    private void spawnVehicle() {
        if (vehicleIds.isEmpty()) {
            lastMessage = "no assemblies are loaded";
            return;
        }
        AssetId id = vehicleIds.get(vehicleChoice);
        placeAhead(spawnTransform);
        runtime.spawnQueue().request(id, spawnTransform, EntityId.NULL, 0);
        lastMessage = "queued " + id.value();
        LOG.info("debug console queued a spawn of {}", id.value());
    }

    /** Places a structure in front of the player, through the same factory the arena pass uses. */
    private void spawnStructure() {
        if (structureIds.isEmpty()) {
            lastMessage = "no structures are loaded";
            return;
        }
        AssetId id = structureIds.get(structureChoice);
        StructureDef definition = runtime.assets().structure(id);
        if (definition == null) {
            lastMessage = id.value() + " is not loaded";
            return;
        }
        placeAhead(spawnTransform);
        // Sit it on the landform rather than at the player's own height, or a structure spawned on
        // a slope is buried at one corner and floating at the other.
        TerrainField terrain = runtime.physics().terrain();
        if (terrain != null) {
            spawnTransform.getTranslation(scratch);
            spawnTransform.setTranslation(scratch.x, terrain.heightAt(scratch.x, scratch.z), scratch.z);
        }
        int entity = StructureFactory.spawnStructure(
                runtime.world(), runtime.physics(), runtime.shapes(), runtime.assets(), definition, spawnTransform);
        lastMessage = entity == EntityId.NULL ? id.value() + " could not be placed" : "placed " + id.value();
        LOG.info("debug console placed {} as entity {}", id.value(), entity);
    }

    /**
     * Freezes or thaws every bot by taking its behaviour tree to {@code IDLE} and holding it there.
     *
     * <p>Implemented as a per-frame write rather than by unregistering {@code BotDecisionSystem},
     * because the schedule is fixed (D04-S4.4) and a console that could remove a system from it
     * would be able to produce a world no build of the game can otherwise reach.
     */
    private void toggleBots() {
        botsFrozen = !botsFrozen;
        lastMessage = botsFrozen ? "enemy AI frozen" : "enemy AI active";
    }

    /**
     * Holds every frozen bot's input at neutral. Called each frame after the simulation has run.
     *
     * <p>After rather than before, so what the bot decided this tick is what gets zeroed — running
     * first would let slot 3 overwrite the neutral input in the same tick and freeze nothing.
     */
    public void applyHolds(World world) {
        if (!botsFrozen) {
            return;
        }
        if (bots == null) {
            bots = world.family(ComponentQuery.all(BotControllerComponent.class, PlayerInputComponent.class));
        }
        int[] ids = bots.snapshot();
        for (int i = 0; i < bots.size(); i++) {
            PlayerInputComponent input = world.getComponent(ids[i], PlayerInputComponent.class);
            if (input != null) {
                input.throttle = 0f;
                input.brake = 1f;
                input.steer = 0f;
                input.collective = 0f;
                input.fireMask = 0;
            }
        }
    }

    /** Emits one direct hit on the nearest living structure part, as a weapon would. */
    private void hitNearestStructure() {
        int part = nearestStructurePart();
        if (part == EntityId.NULL) {
            lastMessage = "no structure part in range";
            return;
        }
        damage(part, TEST_HIT_DAMAGE);
        lastMessage = "hit a structure part for " + (int) TEST_HIT_DAMAGE;
    }

    /** Kills the nearest structure's root outright, which drops everything standing on it. */
    private void flattenNearestStructure() {
        int part = nearestStructureRoot();
        if (part == EntityId.NULL) {
            lastMessage = "no structure in range";
            return;
        }
        damage(part, Float.MAX_VALUE / 4f);
        lastMessage = "destroyed a structure's root";
    }

    private void damage(int partEntity, float amount) {
        World world = runtime.world();
        TransformComponent transform = world.getComponent(partEntity, TransformComponent.class);
        Vector3 at = transform == null ? Vector3.Zero : transform.position;
        // emitSameTick, not emit: DamageSystem (slot 12) drains the same-tick queue, and the
        // deferred queue goes to listeners instead — so an emit() here is a hit that fires, logs
        // and never touches the target. That is D04-R14's damage-pipeline exception being used
        // for the damage pipeline, which is what it is for. The console runs before the loop
        // advances, so the event is waiting when slot 12 runs in the very next tick.
        world.events()
                .emitSameTick(DamageEvent.direct(
                        partEntity,
                        EntityId.NULL,
                        localPlayer.playerEntity(),
                        DamageType.EXPLOSIVE,
                        amount,
                        at,
                        Vector3.Y,
                        loop.tick(),
                        DamageEvent.NO_WEAPON_GROUP));
    }

    // ---- Queries --------------------------------------------------------------------

    private void placeAhead(Matrix4 out) {
        Vector3 eye = runtime.render().camera().camera().position;
        Vector3 look = runtime.render().camera().camera().direction;
        scratch.set(look.x, 0f, look.z).nor().scl(SPAWN_AHEAD_M).add(eye);
        out.idt().setTranslation(scratch);
    }

    private Family structureParts(World world) {
        if (structureParts == null) {
            structureParts = world.family(ComponentQuery.all(PartRefComponent.class, TransformComponent.class));
        }
        return structureParts;
    }

    /** The nearest living part belonging to a structure, measured from the camera. */
    private int nearestStructurePart() {
        World world = runtime.world();
        Vector3 eye = runtime.render().camera().camera().position;
        int best = EntityId.NULL;
        float bestDistance = Float.MAX_VALUE;
        Family family = structureParts(world);
        int[] ids = family.snapshot();
        for (int i = 0; i < family.size(); i++) {
            int id = ids[i];
            PartRefComponent ref = world.getComponent(id, PartRefComponent.class);
            if (ref == null || world.getComponent(ref.vehicleEntity, StructureComponent.class) == null) {
                continue;
            }
            HealthComponent health = world.getComponent(id, HealthComponent.class);
            if (health != null && health.healthFraction <= 0f) {
                continue;
            }
            TransformComponent transform = world.getComponent(id, TransformComponent.class);
            if (transform == null) {
                continue;
            }
            float distance = transform.position.dst2(eye);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = id;
            }
        }
        return best;
    }

    /** The root part of the structure nearest the camera. */
    private int nearestStructureRoot() {
        int part = nearestStructurePart();
        if (part == EntityId.NULL) {
            return EntityId.NULL;
        }
        World world = runtime.world();
        PartRefComponent ref = world.getComponent(part, PartRefComponent.class);
        StructureComponent structure =
                ref == null ? null : world.getComponent(ref.vehicleEntity, StructureComponent.class);
        return structure == null ? part : structure.rootPartEntity;
    }

    private int structureCount() {
        World world = runtime.world();
        Family family = world.family(ComponentQuery.all(StructureComponent.class));
        return family.size();
    }

    private int structurePartCount() {
        World world = runtime.world();
        Family family = structureParts(world);
        int[] ids = family.snapshot();
        int count = 0;
        for (int i = 0; i < family.size(); i++) {
            PartRefComponent ref = world.getComponent(ids[i], PartRefComponent.class);
            if (ref != null && world.getComponent(ref.vehicleEntity, StructureComponent.class) != null) {
                count++;
            }
        }
        return count;
    }

    private int botCount() {
        World world = runtime.world();
        return world.family(ComponentQuery.all(BotControllerComponent.class, VehicleChassisComponent.class))
                .size();
    }

    private float nightFraction() {
        return runtime.render().environment().nightFraction();
    }

    private void cycleNight() {
        float[] steps = {0f, 0.55f, 1f};
        float current = nightFraction();
        int index = 0;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == current) {
                index = (i + 1) % steps.length;
            }
        }
        runtime.render().environment().setNightFraction(steps[index]);
        lastMessage = "night " + steps[index];
    }

    private static String choiceOf(List<AssetId> ids, int index) {
        return ids.isEmpty() ? "(none)" : ids.get(index).value();
    }

    private static int next(List<AssetId> ids, int index) {
        return ids.isEmpty() ? 0 : (index + 1) % ids.size();
    }
}
