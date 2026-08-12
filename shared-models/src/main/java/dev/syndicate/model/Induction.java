/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

import java.util.Locale;

/**
 * How an engine gets its air, and therefore the second voice it speaks with
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R37b).
 *
 * <p><b>This is the axis on which the two shipped cars are most obviously themselves, and the one
 * the bank was missing.</b> Both reference cars are forced-induction and neither sounded like it:
 * the Eclipse's reference is a twin-turbo V6 and the Stampede's a supercharged V8, and a supercharger
 * whine is arguably the single most identifiable thing about that car. Neither the synthesiser nor
 * the playback path had anything for it, so the two cars differed only in firing rate and gain.
 *
 * <p>It follows the same rule as {@link EngineConfiguration} (D15-R37): a small closed set, one asset
 * per member, and a vehicle picks one rather than commissioning anything. What varies per vehicle is
 * the drive ratio and how hard it is working, which the runtime derives from rpm and throttle.
 *
 * <p>The two forced members sound different for a structural reason rather than a tuning one. A
 * supercharger is <em>geared to the crank</em>, so its whine is a hard tone at a fixed multiple of
 * engine speed and it is present whenever the engine is running. A turbocharger is driven by exhaust
 * flow, so it is broadband rush that lags the throttle, builds with load, and lets go audibly when
 * the driver lifts — which is why only {@link #TURBO} has a release.
 */
public enum Induction {

    /** Naturally aspirated. No second voice; the exhaust is the whole sound. */
    NATURALLY_ASPIRATED(0.0, 0.0, false),

    /**
     * Exhaust-driven turbocharger. Broadband spool that lags the throttle, plus a release on lift.
     *
     * <p>The reference figure is a small twin-turbo installation. Its blade-pass rush sits well up in
     * the top octaves — far enough up that the very top of it reads as hiss rather than as pitch — so
     * the order here is the audible whistle rather than the true blade-pass frequency, and the
     * character is breathy and noisy rather than tonal.
     */
    TURBO(75.0, 0.35, true),

    /**
     * Crank-driven positive-displacement supercharger. A hard tone at a fixed multiple of engine
     * speed.
     *
     * <p>{@link #driveRatio} is the whine's order: a twin-screw blower turning about 2.4 times crank
     * speed with four-lobe rotors puts its fundamental at roughly ten times engine rotation, which at
     * 4,000 rpm is about 640 Hz. That is the note a listener recognises.
     */
    SUPERCHARGED(9.6, 0.85, false);

    private final double driveRatio;
    private final double tonality;
    private final boolean hasRelease;

    Induction(double driveRatio, double tonality, boolean hasRelease) {
        this.driveRatio = driveRatio;
        this.tonality = tonality;
        this.hasRelease = hasRelease;
    }

    /**
     * How many times per crank revolution this device's fundamental sounds.
     *
     * <p>Fixed for a supercharger, because it is geared: 9.6 puts a 4,000 rpm engine's whine at
     * 640 Hz. Nominal for a turbo, whose actual speed depends on exhaust flow — there it is the
     * rush's centre at full boost (75 gives 5 kHz at 4,000 rpm), and the runtime moves it with load
     * rather than treating it as locked to the crank.
     */
    public double driveRatio() {
        return driveRatio;
    }

    /**
     * How tonal rather than breathy the device is, {@code [0,1]}.
     *
     * <p>A positive-displacement blower is nearly a pure tone with harmonics; a turbo is mostly air
     * rush with a weak tone in it. This is the parameter that keeps them from being the same sound at
     * two pitches.
     */
    public double tonality() {
        return tonality;
    }

    /** Whether lifting off produces an audible release — a blow-off valve or a wastegate flutter. */
    public boolean hasRelease() {
        return hasRelease;
    }

    /** Whether this engine has a second voice at all. */
    public boolean isForced() {
        return this != NATURALLY_ASPIRATED;
    }

    /** The asset-id token, e.g. {@code supercharged}. */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }
}
