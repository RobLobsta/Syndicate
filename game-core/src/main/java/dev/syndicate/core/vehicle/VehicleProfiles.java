/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.model.AssetId;
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
     * Apex GT — a mid-engine road supercar. Reference: <b>Maserati MC20 (2021-)</b>.
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
    public static final VehicleProfile APEX_GT = VehicleProfile.builder(
                    AssetId.of("vehicle_apex_gt_01"), "Apex GT", "Maserati MC20 (2021-)")
            .referenceSource("Maserati/Stellantis press material; Wikipedia 'Maserati MC20'")
            .vehicleClass("medium")
            .kerbMassKg(1500f)
            .engine(463f, 730f)
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
     * Stampede GT3 — a front-engine, rear-transaxle GT racer. Reference:
     * <b>Ford Mustang GT3 (2024)</b>.
     *
     * <p><b>Published.</b> 1289 kg (2842 lb); 410 kW (550 hp) from a naturally aspirated 5.4 L V8;
     * Xtrac six-speed sequential transaxle; double wishbones and Multimatic DSSV dampers at both
     * ends; Alcon brakes; 18-inch wheels on 12.8-inch front and 13.6-inch rear slicks. FIA GT3
     * balance-of-performance puts the class between 1200 and 1300 kg and 500 to 600 hp.
     *
     * <p><b>Derived.</b> Power is 92% of crank — a sequential transaxle loses less than a road
     * dual-clutch. Engine force calibrates to a 3.3 s 0-100 km/h, which is slower than the road car
     * despite far more grip, because a GT3 is geared for a circuit rather than for a standing start.
     * The resulting 11.3 kN is almost exactly the traction a slick-shod 1289 kg car can put down,
     * which is the right physical story for a class that is grip-limited off the line.
     *
     * <p><b>Estimated</b>, because none of it is published for a race car: {@code Cd·A} at 1.45 m²,
     * the figure a GT3 aero package with a splitter, a large rear wing and a full diffuser implies —
     * it puts the derived top speed at about 267 km/h, which is where GT3s actually run. Downforce at
     * roughly 600 kg at 250 km/h. Braking at 1.7 g, which slicks plus that downforce support.
     * Suspension is stiffer than the road car by the ratio of typical race to road spring rates, with
     * damping scaled by {@code √(k)} so it stays critically damped, and roll influence is low because
     * a GT3 has a low centre of gravity and stiff anti-roll bars.
     *
     * <p>The pair is meant to be a real choice rather than an upgrade: the Apex accelerates harder in
     * a straight line and the Stampede corners, brakes and holds speed better everywhere else.
     */
    public static final VehicleProfile STAMPEDE_GT3 = VehicleProfile.builder(
                    AssetId.of("vehicle_stampede_gt3_01"), "Stampede GT3", "Ford Mustang GT3 (2024)")
            .referenceSource("Ford Performance and Multimatic material; Wikipedia 'Ford Mustang GT3'")
            .vehicleClass("medium")
            .kerbMassKg(1289f)
            .engine(410f, 570f)
            .performance(3.30f, 267f, 24.0f)
            .aero(0.7256f, 2.00f, 1.22f)
            .drivelineEfficiency(0.92f)
            .geometry(2.72f, 1.70f, 1.68f, 0.50f)
            .wheels("325/680 R18 slick", "345/700 R18 slick", 0.340f, 0.350f, 38f)
            .steering(0.48f, 1.80f)
            .suspension(2.90f, 55f, 3.25f, 3.11f, 0.08f)
            .rollingResistance(0.014f)
            .build();

    private static final Map<AssetId, VehicleProfile> BY_ID = index(APEX_GT, STAMPEDE_GT3);

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
