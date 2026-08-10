/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.model.AssetId;
import dev.syndicate.model.EngineConfiguration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every vehicle handling profile the game ships (docs/05_vehicle_part_system.md#D05-S5.6).
 *
 * <p>Each profile records a real car's published performance and the arithmetic that turns it into
 * simulation parameters; see {@link VehicleProfile} for why that is a research record rather than a
 * duplicate of the content in {@code assets/vehicles/}.
 *
 * <p><b>Adding a profile is three steps</b>, and the tests fail loudly if any is skipped: add it
 * here with its sourced figures, author the matching {@code part.json} and {@code assembly.json}
 * under {@code assets/}, and re-run {@code :game-core:test} — {@code VehicleTableTest} rewrites
 * {@code VEHICLES.md} and fails until the committed copy matches.
 */
public final class VehicleProfiles {

    /**
     * Eclipse — a mid-engine road supercar. Reference: <b>Maserati MC20 (2021-)</b>.
     *
     * <p><b>Published.</b> 1500 kg kerb; 463 kW (630 PS) at 7500 rpm and 730 N·m from a 3.0 L
     * twin-turbo V6; 0-100 km/h under 2.9 s; 0-200 km/h under 8.8 s; over 325 km/h; 100-0 km/h under
     * 33 m; Cd under 0.38; 245/35 ZR20 front and 305/30 ZR20 rear; 2700 mm wheelbase; 4669 x 1965 x
     * 1224 mm.
     *
     * <p><b>Derived.</b> Frontal area is taken as {@code width × height × 0.83} = 2.00 m², the usual
     * fill factor for a low coupé, giving {@code k_drag = ½·1.225·0.38·2.00 = 0.466}. Engine force
     * calibrates to the 2.88 s 0-100. Power is 90% of crank, a dual-clutch road car's driveline, and
     * the two cross over at 28.2 m/s — which is where a real car hands off from a traction-limited
     * first gear to a power-limited everything else. Wheel radii come from the tyre codes:
     * {@code (508 + 2·0.35·245)/2 = 340 mm} front, {@code (508 + 2·0.30·305)/2 = 346 mm} rear.
     *
     * <p><b>Estimated</b>, and labelled as such because Maserati publishes neither: downforce at
     * about 100 kg at 250 km/h, which is a flat floor and a fixed spoiler rather than a wing; track
     * widths; and the 40/60 mass split a rear mid-engine layout implies.
     */
    public static final VehicleProfile ECLIPSE = VehicleProfile.builder(
                    AssetId.of("vehicle_eclipse_01"), "Eclipse", "Maserati MC20 (2021-)")
            .referenceSource("Maserati/Stellantis press material; Wikipedia 'Maserati MC20'")
            .vehicleClass("medium")
            .kerbMassKg(1500f)
            .engine(463f, 730f)
            // A 3.0 L twin-turbo V6 revving to 8,000. Against the Stampede's supercharged V8 that
            // is two fewer cylinders and 1,000 rpm more: a higher, thinner, busier note.
            .engineVoice(EngineConfiguration.V6, 850f, 8000f)
            .performance(2.88f, 325f, 33f)
            .aero(0.38f, 2.00f, 0.20f)
            .drivelineEfficiency(0.90f)
            .geometry(2.70f, 1.68f, 1.64f, 0.40f)
            .wheels("245/35 ZR20", "305/30 ZR20", 0.340f, 0.346f, 37.5f)
            .steering(0.60f, 1.35f)
            .suspension(2.00f, 30f, 2.4f, 2.3f, 0.15f)
            .rollingResistance(0.015f)
            .build();

    /**
     * Stampede — a front-engine, rear-transaxle road supercar. Reference:
     * <b>Ford Mustang GTD (2025)</b>.
     *
     * <p><b>Published.</b> 1969 kg (4340 lb); 608 kW (815 hp) and 900 N·m (664 lb·ft) from a
     * supercharged 5.2 L V8 revving past 7500 rpm; an eight-speed dual-clutch rear transaxle on a
     * carbon driveshaft, giving a near 50/50 split; 325/30 ZR20 front and 345/30 ZR20 rear;
     * 325 km/h (202 mph); over 8.4 kN of downforce at 290 km/h with the track package; 6:52.072 at
     * the Nürburgring.
     *
     * <p><b>Derived.</b> {@code Cd·A} is solved backwards from the published top speed rather than
     * guessed: 90% of 608 kW at 90.3 m/s, less rolling resistance, leaves 523 kW of drag power and
     * therefore {@code k_drag = 0.711}, i.e. {@code Cd·A = 1.16 m²} — {@code Cd = 0.54} on a 2.15 m²
     * frontal area, which is what a fixed rear wing costs. Downforce follows straight from the
     * 8.4 kN figure: {@code 8451 / 80.5² = 1.30 N per (m/s)²}. Power is 90% of crank, the same
     * dual-clutch driveline loss as the Eclipse.
     *
     * <p><b>Estimated.</b> The 0-100 km/h is the one figure Ford states loosely — "under three
     * seconds" to 60 mph, with independent numbers nearer 3.3 s — so 3.40 s to 100 km/h is the
     * conservative reading, and it is what an 815 hp rear-drive car on road tyres can actually put
     * down. 100-0 in 30 m (1.31 g) for carbon-ceramics on 325-section semi-slicks. Grip and springs
     * sit between the Eclipse's and a race car's: this is a road car with adjustable Multimatic DSSV
     * dampers and a ride-height change, not a GT3.
     *
     * <p>The pair is meant to be a real choice rather than an upgrade, and against a GTD that choice
     * is a different one than it was against the GT3 this profile used to describe: the Eclipse is
     * 470 kg lighter and still wins the standing start, while the Stampede carries far more power,
     * downforce and brake, and holds speed where the Eclipse cannot.
     */
    public static final VehicleProfile STAMPEDE = VehicleProfile.builder(
                    AssetId.of("vehicle_stampede_01"), "Stampede", "Ford Mustang GTD (2025)")
            .referenceSource("Ford Performance press material; Ford.com Mustang GTD specifications")
            .vehicleClass("heavy")
            .kerbMassKg(1969f)
            .engine(608f, 900f)
            // A supercharged 5.2 L cross-plane V8 past 7,500. Eight bigger cylinders firing
            // unevenly, 145 kW more, and 470 kg more car to move: lower, louder, and heavier.
            .engineVoice(EngineConfiguration.V8, 750f, 7600f)
            .performance(3.40f, 325f, 30.0f)
            .aero(0.54f, 2.15f, 1.30f)
            .drivelineEfficiency(0.90f)
            .geometry(2.72f, 1.71f, 1.71f, 0.50f)
            .wheels("325/30 ZR20", "345/30 ZR20", 0.3515f, 0.3575f, 34f)
            .steering(0.55f, 1.50f)
            .suspension(2.50f, 45f, 2.94f, 2.82f, 0.10f)
            .rollingResistance(0.014f)
            .build();

    private static final Map<AssetId, VehicleProfile> BY_ID = index(ECLIPSE, STAMPEDE);

    private VehicleProfiles() {
        throw new AssertionError("no instances");
    }

    /** Every profile, in ascending id order (G3). */
    public static List<VehicleProfile> all() {
        return List.copyOf(BY_ID.values());
    }

    /** One profile by id, or null. */
    public static VehicleProfile byId(AssetId profileId) {
        return BY_ID.get(profileId);
    }

    private static Map<AssetId, VehicleProfile> index(VehicleProfile... profiles) {
        Map<AssetId, VehicleProfile> byId = new TreeMap<>();
        for (VehicleProfile profile : profiles) {
            if (byId.put(profile.profileId(), profile) != null) {
                throw new IllegalStateException(
                        "duplicate profile id " + profile.profileId().value());
            }
        }
        return byId;
    }
}
