/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * How many cylinders an engine has and how they are arranged
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8).
 *
 * <p><b>This is the axis on which cars sound different from each other.</b> D15-R37 says sounds are
 * per class and per material, never per vehicle, and the reason is cost: a per-vehicle bank makes
 * every new car wait for an audio pass. Vehicle <em>class</em> turns out to be the wrong axis for an
 * engine, though — "medium" and "heavy" describe how much a car weighs, and what an engine sounds
 * like is decided by how many times a second it fires and how evenly.
 *
 * <p>Configuration is the right axis and it is still a small closed set. Six of these cover every
 * road and race car worth modelling, a new vehicle picks one rather than commissioning anything, and
 * two cars sharing a configuration still sound different because the rest of the character —
 * firing frequency, gain, low-end weight — comes from their own rpm and power (see
 * {@code EngineVoice}).
 *
 * <p>{@link #cylinders} is what sets the firing frequency: a four-stroke fires
 * {@code cylinders × rpm / 120} times a second, so a V8 at 3,000 rpm fires at 200 Hz and a V6 at
 * 150. That is most of a musical fourth apart and is immediately audible.
 *
 * <p>{@link #roughness} is the other half. An engine with fewer, larger cylinders has bigger
 * pressure pulses and longer gaps between them, which the ear hears as a hard-edged bark; a V12's
 * overlapping pulses read as a smooth tearing note. A cross-plane V8's uneven bank firing is the
 * famous case — it is rougher than its cylinder count alone would suggest, and that is why the value
 * is a property of the arrangement rather than a formula over {@link #cylinders}.
 */
public enum EngineConfiguration {

    /** Inline four. Small, buzzy, coarse. */
    I4(4, 0.30f, 12),

    /** Inline six. Perfectly balanced, smooth, and characteristically hard-edged at the top. */
    I6(6, 0.16f, 16),

    /** V6. Compact and slightly uneven; the modern turbocharged supercar's engine. */
    V6(6, 0.22f, 15),

    /** Cross-plane V8. Uneven bank firing is what gives it its burble. */
    V8(8, 0.26f, 18),

    /** V10. Flat-plane-ish, high-revving, and unmistakably shrill. */
    V10(10, 0.14f, 20),

    /** V12. The smoothest thing here, and the one that sounds like tearing silk. */
    V12(12, 0.10f, 24);

    private final int cylinders;
    private final float roughness;
    private final int harmonics;

    EngineConfiguration(int cylinders, float roughness, int harmonics) {
        this.cylinders = cylinders;
        this.roughness = roughness;
        this.harmonics = harmonics;
    }

    /** How many cylinders fire per two crank revolutions. */
    public int cylinders() {
        return cylinders;
    }

    /** How much cycle-to-cycle noise the loop carries, {@code [0,1]}. */
    public float roughness() {
        return roughness;
    }

    /** How many harmonics the loop stacks. More is a richer, rortier note. */
    public int harmonics() {
        return harmonics;
    }

    /**
     * The firing frequency at an engine speed, in Hz.
     *
     * <p>Four-stroke: each cylinder fires once every two crank revolutions, so the rate is
     * {@code cylinders × rpm / 120}. This is the number the whole engine sound hangs off — pitch it
     * and the engine revs, and no sample-rate trick or filter can substitute for getting it right.
     */
    public float firingHzAt(float rpm) {
        return cylinders * rpm / 120f;
    }

    /** The asset-id token, e.g. {@code v8}. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
