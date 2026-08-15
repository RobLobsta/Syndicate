/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import com.badlogic.gdx.math.Vector3;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Turns a seed into ground (docs/16_procedural_arena_generation.md#D16-S5.1).
 *
 * <p>The stages of D16-S5.1 that stand on their own: broad relief, the biome's dune layer, the
 * border rise, surface classification, drivability and the spawn connectivity check. Road carving
 * (stage 4) and structure pads (stage 5) are not here yet; when they arrive they sit between the
 * border rise and classification, exactly where the stage list puts them.
 *
 * <p><b>Every stage is a pure function of the stage before it and the parameters</b> (D16-R26), and
 * the class holds no state. Two processes handed the same {@code ArenaDef} produce byte-identical
 * grids, which is what lets terrain be derived on every peer instead of replicated (DEC-069). The
 * four rules that buy that (D16-R61) are honoured here as: no {@code java.util.Random} anywhere,
 * {@code StrictMath} for every transcendental, row-major iteration in a fixed order, and no
 * parallelism.
 */
public final class TerrainGenerator {

    /**
     * The most of a dune's wavelength its slip face may occupy.
     *
     * <p>Past this a dune stops being asymmetric and becomes a ridge, which is a different landform
     * and — because both faces would then be climbable — a landform with no gameplay in it. When the
     * requested dune height needs more than this, the height is scaled down instead, which is
     * D16-R33's remedy applied where it actually helps.
     */
    public static final double MAX_SLIP_FRACTION = 0.45;

    /** The least of a wavelength a slip face may occupy, so a dune always has a face at all. */
    public static final double MIN_SLIP_FRACTION = 0.02;

    /** Octaves in the dune layer's phase warp and crest modulation. Both are broad, so three. */
    public static final int DUNE_DETAIL_OCTAVES = 3;

    /** How far the phase warp displaces dune crests, in wavelengths. Straight dunes read as corduroy. */
    public static final double PHASE_WARP = 0.35;

    /**
     * Metres either side of a sample used to differentiate the phase warp along the wind.
     *
     * <p>Half a metre: short enough to resolve the warp, which varies over hundreds of metres, and
     * long enough that the difference is not dominated by the subtraction's own rounding.
     */
    private static final double WARP_DERIVATIVE_STEP_M = 0.5;

    /**
     * Floor on the local phase gradient, as a fraction of the nominal one.
     *
     * <p>The warp can in principle steepen the phase enough to run it backwards, which would fold
     * dunes through each other. Clamping the gradient rather than the warp keeps crests where the
     * warp put them and only refuses to let two of them cross.
     */
    private static final double MIN_PHASE_GRADIENT_FRAC = 0.25;

    /**
     * Where the crest field stops producing dunes, opening a pass.
     *
     * <p>Below {@link #CREST_GAP_LOW} there is no dune at all; above {@link #CREST_GAP_HIGH} there is
     * a full-height one; between them the dune grows. This is the difference between an arena and a
     * set of corridors, and it was found by measuring rather than by design (DISC-045): dunes run
     * transverse to the wind and every slip face stands past what a vehicle can climb, so a field of
     * dunes at uniformly non-zero height is a field of uniformly continuous <em>walls</em>. The first
     * version of this generator produced ground that was 73% drivable and split into 42 disconnected
     * strips, the largest of them under a quarter of the arena.
     *
     * <p><b>The band between the two is narrow on purpose.</b> The first attempt at a fix was one
     * threshold with a long ramp out of it, which connected the arena by deleting the dunes — peak
     * height fell from 9 m to 3.6 m and 63% of the field went dead flat. A pass has to be an opening
     * between dunes that are still dunes, not a general flattening, and the way to get that is a
     * sharp gate rather than a gentle one.
     */
    public static final double CREST_GAP_LOW = 0.40;

    /** Where a dune reaches {@link #CREST_PASS_FLOOR} of its height. See {@link #CREST_GAP_LOW}. */
    public static final double CREST_GAP_HIGH = 0.50;

    /** How tall a dune is the moment it exists at all, as a fraction of the nominal height. */
    public static final double CREST_PASS_FLOOR = 0.7;

    /**
     * The least a pad's ramp may extend beyond its flat disc, metres.
     *
     * <p>A floor, not the whole rule: where a pad cuts into a dune the ramp is widened until it is
     * drivable (see {@link #flattenPads}). Without that, a pad dug 9 m into a crest would be ringed
     * by a 37° wall and a car would spawn in a pit — which is the failure the pad exists to prevent,
     * reintroduced by the fix for it.
     */
    public static final float PAD_RAMP_MIN_M = 8.0f;

    /**
     * How much of the drivable budget a pad's ramp aims at.
     *
     * <p>Below 1 so a ramp is comfortably drivable rather than marginally: a slope exactly at the
     * limit is drivable by the grid's test and miserable to actually climb, and the grid samples it
     * at one point while a vehicle meets it at four wheels.
     */
    public static final float PAD_RAMP_SLOPE_FRAC = 0.8f;

    /** Slope above which sand is scoured off and gravel shows (D16-R41). */
    /** Octaves in the heap layer. Two is a lump, four starts to look like separate piles. */
    public static final int HEAP_OCTAVES = 4;

    /**
     * Fraction of the rectified heap field that stays flat ground.
     *
     * <p>The number that decides whether a scrapyard is a yard with piles in it or a field of
     * hillocks. Against {@link #HEAP_PEAK} this leaves roughly a sixth of the map carrying a heap,
     * which is what makes them cover you go around rather than terrain you drive over.
     */
    public static final double HEAP_FLOOR = 0.60;

    /**
     * Where the rectified heap field actually tops out.
     *
     * <p><b>Measured, not assumed.</b> {@code fbm} normalises by the sum of its octave amplitudes,
     * which bounds it to [-1, 1] in principle; in practice gradient noise summed over four octaves
     * reaches ±0.44, so the rectified field spans about 0.28 to 0.72 and never approaches either
     * end. Dividing by {@code 1 - HEAP_FLOOR} — the obvious thing, and what this did first — scales
     * the tallest heap on the map to a twelfth of its intended height, which is how a 14 m spoil
     * heap arrived as a 1.2 m bump and left the whole arena drivable.
     */
    public static final double HEAP_PEAK = 0.72;

    /**
     * Shapes a heap's flanks. Above 1 the pile has a broad base and a defined top.
     *
     * <p>Two is steep enough that a heap reads as tipped material rather than as a hill, and shallow
     * enough that a car can get up the easier faces.
     */
    public static final double HEAP_EXPONENT = 2.0;

    /** Keeps the heap layer's noise independent of the landform's at the same seed. */
    public static final long HEAP_SEED_SALT = 0x7F4A7C15L;

    public static final float GRAVEL_SLOPE_DEG = 18.0f;

    /** How far above the field's mean a plateau cap must stand to show rock (D16-R41). */
    public static final float ROCK_EXPOSURE_FRAC = 0.72f;

    private TerrainGenerator() {
        throw new AssertionError("no instances");
    }

    /**
     * Somewhere the ground is levelled: a spawn point's clearance, or a structure's footprint.
     *
     * <p>One type for both, because they are the same operation with the same failure mode, and
     * because a structure standing on the lip of a spawn pad is exactly what a shared, ordered pass
     * prevents (D16-R45).
     *
     * @param x world x of the centre
     * @param z world z of the centre
     * @param radiusM how much is levelled flat, before the ramp out
     */
    public record Pad(float x, float z, float radiusM) {}

    /**
     * Generates an arena's ground.
     *
     * <p>Takes the three numbers it needs rather than an {@code ArenaDef}, so that this package does
     * not depend on {@code core.asset} — which depends on it, for the terrain block on the arena
     * record. The adapting is one line in {@code ArenaFactory}, which already holds both.
     *
     * @param min the arena's lower bound corner
     * @param max its upper bound corner
     * @param groundY the datum heights are generated above
     * @param params the terrain block
     * @throws IllegalArgumentException if the grid does not match the arena's bounds (D16-R5, A410)
     */
    public static TerrainField generate(Vector3 min, Vector3 max, float groundY, TerrainParams params) {
        return generate(min, max, groundY, params, List.of());
    }

    /**
     * Generates an arena's ground, levelling a pad at each of {@code pads} (D16-S5.1 stage 5).
     *
     * @param pads spawn clearances and structure footprints, applied in list order (G3)
     */
    public static TerrainField generate(Vector3 min, Vector3 max, float groundY, TerrainParams params, List<Pad> pads) {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(params, "params");

        float spanX = max.x - min.x;
        float spanZ = max.z - min.z;
        float expected = params.spanM();
        // A410. Checked rather than assumed: every query in TerrainField maps world to grid by
        // arithmetic, so a grid that does not cover the bounds is not slightly wrong — it silently
        // reads the ground from somewhere else in the arena.
        if (Math.abs(spanX - expected) > 1e-3f || Math.abs(spanZ - expected) > 1e-3f) {
            throw new IllegalArgumentException("terrain grid spans " + expected + " m but the arena bounds span "
                    + spanX + " x " + spanZ + " m (D16-R5)");
        }

        int grid = params.gridSize();
        float[] heights = new float[params.sampleCount()];

        // Feature-layer rotation, evaluated once rather than per sample. The one place this class
        // needs trigonometry at all, and it is two calls for the whole arena rather than 361,000.
        double bearingRad = StrictMath.toRadians(params.featureBearingDeg());
        double bearingCos = StrictMath.cos(bearingRad);
        double bearingSin = StrictMath.sin(bearingRad);
        double reposeTan = StrictMath.tan(StrictMath.toRadians(TerrainParams.SAND_REPOSE_DEG));
        ArenaTheme.Relief relief = params.theme().relief();

        // [1] and [2]: broad relief, then the theme's relief layer. Row-major, ascending, and that
        // order is part of the contract (D16-R61 rule 3).
        for (int j = 0; j < grid; j++) {
            double z = min.z + j * (double) params.cellSizeM();
            for (int i = 0; i < grid; i++) {
                double x = min.x + i * (double) params.cellSizeM();
                double h = params.reliefM()
                        * TerrainNoise.fbm(x, z, params.baseFrequency(), params.octaves(), params.seed());
                h += switch (relief) {
                    case DUNES -> duneHeight(x, z, params, bearingCos, bearingSin, reposeTan);
                    case HEAPS -> heapHeight(x, z, params);
                    case NONE -> 0.0;};
                heights[j * grid + i] = (float) h;
            }
        }

        // [3] Border rise, applied after the landform so the rim is always the highest thing at the
        // edge whatever the landform did there.
        applyBorderRise(heights, params);

        // [5] Pads. Before classification and drivability, because levelling ground changes both.
        flattenPads(heights, min, params, pads);

        // [6] and [7]: what the ground is made of, and whether a vehicle can be on it. Both need the
        // finished heights, so both run over a field nothing will change again.
        TerrainField bare =
                new TerrainField(params, min.x, min.z, groundY, heights, new byte[params.sampleCount()], new BitSet());
        byte[] surfaces = classify(bare, params);
        BitSet drivable = markDrivable(bare, params);

        return new TerrainField(params, min.x, min.z, groundY, heights, surfaces, drivable);
    }

    /**
     * The dune layer at a point: a long windward rise and a short face at the angle of repose.
     *
     * <p><b>The slip face's angle is an input, not an output.</b> D16-S5.3 wrote the profile with a
     * fixed windward fraction, which makes the resulting angle a consequence of height and wavelength
     * and needs a correction pass to bring it back — a pass that can only ever reduce the height, and
     * so cannot fix a face that came out too shallow. Solving for the fraction instead makes
     * D16-R33's property hold <em>by construction</em>: the slip width is whatever puts the local
     * crest height at the repose angle. D16-S5.3 was amended to match (DISC-044).
     *
     * <p>The fraction is solved per sample from the <em>local</em> crest height, so a shorter dune
     * gets a proportionally shorter face rather than a shallower one. Real dunes all stand at repose
     * regardless of size, and computing the fraction once from the nominal height would have made
     * every dune below full height too gentle — which, since crest heights are modulated down to 55%,
     * would have been most of them.
     */
    /**
     * The {@link ArenaTheme.Relief#HEAPS} layer: discrete piles on otherwise flat ground.
     *
     * <p>Deliberately not a second kind of hill. Fractal noise used directly gives rolling relief
     * everywhere, and a breaker's yard is flat ground with heaps <em>on</em> it — the flatness
     * between the piles is what makes them cover rather than scenery. Raising a rectified noise
     * field to a power does exactly that: values near zero are pushed to nothing and the ground
     * stays flat, while the few high values keep most of their height and become piles.
     *
     * <p>Isotropic, unlike the dune layer: a spoil heap has no prevailing wind, so
     * {@code featureBearingDeg} is ignored here and a scrapyard's is zero to say so.
     *
     * <p>No repose correction. A heap of crushed scrap stands steeper than dry sand and is meant to
     * be climbable from some angles and not others; the {@link #HEAP_EXPONENT} shapes the flanks and
     * the drivability pass decides what that costs, rather than a slip face being solved for.
     */
    private static double heapHeight(double x, double z, TerrainParams params) {
        // One cycle of noise per feature wavelength, so "heaps about 55 m apart" is what the number
        // means here as much as "dune crests 90 m apart" is what it means in the dune layer.
        double frequency = 1.0 / params.featureWavelengthM();
        double n = TerrainNoise.fbm(x, z, frequency, HEAP_OCTAVES, params.seed() ^ HEAP_SEED_SALT);

        // fbm is signed and roughly symmetric; rectify to [0,1] so the lower half of the field
        // becomes flat yard rather than pits.
        double normalised = 0.5 + 0.5 * n;
        if (normalised <= HEAP_FLOOR) {
            return 0.0;
        }
        double above = Math.min(1.0, (normalised - HEAP_FLOOR) / (HEAP_PEAK - HEAP_FLOOR));
        return params.featureHeightM() * Math.pow(above, HEAP_EXPONENT);
    }

    private static double duneHeight(
            double x, double z, TerrainParams params, double windCos, double windSin, double reposeTan) {

        // Dunes run transverse to the wind, so only the wind-aligned coordinate matters.
        double u = x * windCos + z * windSin;

        double warpFreq = params.baseFrequency() * 2.0;
        long warpSeed = params.seed() ^ 0x51ED270BL;
        double warp = TerrainNoise.fbm(x, z, warpFreq, DUNE_DETAIL_OCTAVES, warpSeed);
        double phase = u / params.featureWavelengthM() + PHASE_WARP * warp;
        double t = phase - Math.floor(phase);

        // How fast phase advances *here*, per metre along the wind. The nominal 1/wavelength plus
        // whatever the warp is doing, differenced along the wind direction rather than along x.
        double stepX = WARP_DERIVATIVE_STEP_M * windCos;
        double stepZ = WARP_DERIVATIVE_STEP_M * windSin;
        double warpAhead = TerrainNoise.fbm(x + stepX, z + stepZ, warpFreq, DUNE_DETAIL_OCTAVES, warpSeed);
        double warpBehind = TerrainNoise.fbm(x - stepX, z - stepZ, warpFreq, DUNE_DETAIL_OCTAVES, warpSeed);
        double nominalGradient = 1.0 / params.featureWavelengthM();
        double phaseGradient = nominalGradient + PHASE_WARP * (warpAhead - warpBehind) / (2.0 * WARP_DERIVATIVE_STEP_M);
        phaseGradient = Math.max(phaseGradient, MIN_PHASE_GRADIENT_FRAC * nominalGradient);

        double crest = TerrainNoise.fbm(x, z, params.baseFrequency(), DUNE_DETAIL_OCTAVES, params.seed() ^ 0x2545F491L);
        double crestScale = crestScale(0.5 + 0.5 * crest);
        if (crestScale <= 0.0) {
            return 0.0;
        }
        double amplitude = params.featureHeightM() * crestScale;

        // The slip face occupies `slipFraction` of a phase cycle, and a phase cycle is
        // `1 / phaseGradient` metres of ground *here* — not `featureWavelengthM`, which is only the
        // mean. Solving with the local figure is what makes every face stand at repose rather than
        // only the faces where the warp happens to be flat.
        double slipFraction = amplitude * phaseGradient / reposeTan;
        if (slipFraction > MAX_SLIP_FRACTION) {
            // D16-R33's remedy, in the direction it works: the dune is too tall for its spacing to
            // hold a repose face, so the height gives way rather than the angle.
            amplitude = MAX_SLIP_FRACTION * reposeTan / phaseGradient;
            slipFraction = MAX_SLIP_FRACTION;
        } else if (slipFraction < MIN_SLIP_FRACTION) {
            slipFraction = MIN_SLIP_FRACTION;
        }
        double windwardFraction = 1.0 - slipFraction;

        if (t < windwardFraction) {
            // A long rise that flattens onto the crest: 1 - (1 - t/wf)².
            double s = 1.0 - t / windwardFraction;
            return amplitude * (1.0 - s * s);
        }
        // The face, linear at the repose angle, from the crest down to the next trough.
        return amplitude * (1.0 - (t - windwardFraction) / slipFraction);
    }

    /**
     * How tall a dune stands here, as a fraction of the nominal height, given the crest field.
     *
     * <p>Three regions: nothing, the gate, and the dune field proper. The gate is smoothstepped
     * rather than lerped because a linear ramp out of zero puts a slope discontinuity along the edge
     * of every pass — a ridge exactly where the pass is supposed to be an opening.
     */
    private static double crestScale(double normalisedCrest) {
        if (normalisedCrest <= CREST_GAP_LOW) {
            return 0.0;
        }
        if (normalisedCrest < CREST_GAP_HIGH) {
            double t = (normalisedCrest - CREST_GAP_LOW) / (CREST_GAP_HIGH - CREST_GAP_LOW);
            return CREST_PASS_FLOOR * t * t * (3.0 - 2.0 * t);
        }
        // Past the gate, height varies with the crest field so a dune field is not a uniform comb.
        double u = (normalisedCrest - CREST_GAP_HIGH) / (1.0 - CREST_GAP_HIGH);
        return CREST_PASS_FLOOR + (1.0 - CREST_PASS_FLOOR) * u;
    }

    /**
     * Raises the outer band into an impassable rim (D16-S5.5).
     *
     * <p>This replaces the four invisible box walls of the flat arena. A rim is a soft boundary — a
     * car at speed gets part way up and slides back — and that is deliberate (D16-R39): an invisible
     * wall is the most immersion-breaking object a driving game can have, and it is also a surface
     * that ramming somebody into is a free kill. The kill plane stays as the hard backstop.
     */
    private static void applyBorderRise(float[] heights, TerrainParams params) {
        int grid = params.gridSize();
        if (params.borderWidthM() <= 0f || params.borderRiseM() == 0f) {
            return;
        }
        float cell = params.cellSizeM();
        for (int j = 0; j < grid; j++) {
            float distZ = Math.min(j, grid - 1 - j) * cell;
            for (int i = 0; i < grid; i++) {
                float distX = Math.min(i, grid - 1 - i) * cell;
                float d = Math.min(distX, distZ);
                if (d >= params.borderWidthM()) {
                    continue;
                }
                float w = smoothstepDown(d / params.borderWidthM());
                heights[j * grid + i] += params.borderRiseM() * w * w;
            }
        }
    }

    /**
     * Levels a disc at each pad and ramps out of it (D16-S5.1 stage 5, D16-S9 E2).
     *
     * <p>The ramp's width is derived, not fixed. A pad is levelled to the height at its centre, so
     * where it cuts into a dune the surrounding ground can be metres above it — and a fixed-width
     * falloff would then be a wall around the pad. The width is instead whatever puts the ramp at
     * {@link #PAD_RAMP_SLOPE_FRAC} of the drivable slope, which is what makes "you can always drive
     * off your spawn" true rather than usually true.
     *
     * <p>Pads are applied in list order and each sees what the previous ones did, so two overlapping
     * pads produce one levelled region rather than a step between them.
     */
    private static void flattenPads(float[] heights, Vector3 min, TerrainParams params, List<Pad> pads) {
        if (pads.isEmpty()) {
            return;
        }
        int grid = params.gridSize();
        float cell = params.cellSizeM();
        double rampTan = StrictMath.tan(StrictMath.toRadians(params.maxDrivableSlopeDeg() * PAD_RAMP_SLOPE_FRAC));

        for (Pad pad : pads) {
            int centreI = clamp(Math.round((pad.x() - min.x) / cell), grid);
            int centreJ = clamp(Math.round((pad.z() - min.z) / cell), grid);
            float level = heights[centreJ * grid + centreI];

            // How far the ground around the pad departs from the level it is being cut to, which is
            // what decides how long the ramp has to be.
            float worstDelta = 0f;
            int probe = (int) Math.ceil((pad.radiusM() + PAD_RAMP_MIN_M) / cell);
            for (int j = Math.max(0, centreJ - probe); j <= Math.min(grid - 1, centreJ + probe); j++) {
                for (int i = Math.max(0, centreI - probe); i <= Math.min(grid - 1, centreI + probe); i++) {
                    worstDelta = Math.max(worstDelta, Math.abs(heights[j * grid + i] - level));
                }
            }
            float ramp = (float) Math.max(PAD_RAMP_MIN_M, worstDelta / rampTan);
            float reach = pad.radiusM() + ramp;

            int span = (int) Math.ceil(reach / cell);
            for (int j = Math.max(0, centreJ - span); j <= Math.min(grid - 1, centreJ + span); j++) {
                float dz = (min.z + j * cell) - pad.z();
                for (int i = Math.max(0, centreI - span); i <= Math.min(grid - 1, centreI + span); i++) {
                    float dx = (min.x + i * cell) - pad.x();
                    float d = (float) Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (d >= reach) {
                        continue;
                    }
                    int index = j * grid + i;
                    if (d <= pad.radiusM()) {
                        heights[index] = level;
                    } else {
                        float w = smoothstepDown((d - pad.radiusM()) / ramp);
                        heights[index] = heights[index] + (level - heights[index]) * w;
                    }
                }
            }
        }
    }

    private static int clamp(int i, int grid) {
        return i < 0 ? 0 : Math.min(i, grid - 1);
    }

    /** Surface classification, physical rather than decorative (D16-S5.6, R41). */
    private static byte[] classify(TerrainField field, TerrainParams params) {
        int grid = params.gridSize();
        byte[] surfaces = new byte[params.sampleCount()];
        ArenaTheme.Palette palette = params.theme().palette();
        if (palette.isUniform()) {
            java.util.Arrays.fill(surfaces, (byte) palette.floor().ordinal());
            return surfaces;
        }
        // The exposure height is a fraction of the field's own relief rather than an absolute, so a
        // low-relief arena does not come out entirely capped and a tall one entirely floor.
        float exposure = field.minHeight() + ROCK_EXPOSURE_FRAC * (field.maxHeight() - field.minHeight());
        for (int j = 0; j < grid; j++) {
            for (int i = 0; i < grid; i++) {
                float slope = field.slopeDegAtSample(i, j);
                float height = field.heightAtSample(i, j);
                Surface s;
                if (slope > TerrainParams.SAND_REPOSE_DEG + 2f) {
                    // Loose material does not sit on a face steeper than its own angle of repose, so
                    // what shows there is what is under it. That it also grips better than it looks
                    // is the payoff for deriving the rule rather than painting it (D16-R42).
                    s = palette.pastRepose();
                } else if (height > exposure) {
                    s = palette.high();
                } else if (slope > GRAVEL_SLOPE_DEG) {
                    s = palette.slope();
                } else {
                    s = palette.floor();
                }
                surfaces[j * grid + i] = (byte) s.ordinal();
            }
        }
        return surfaces;
    }

    /** Drivability: slope within budget (D16-S5.11, R57). Structure footprints join this at stage 4. */
    private static BitSet markDrivable(TerrainField field, TerrainParams params) {
        int grid = params.gridSize();
        BitSet drivable = new BitSet(params.sampleCount());
        for (int j = 0; j < grid; j++) {
            for (int i = 0; i < grid; i++) {
                if (field.slopeDegAtSample(i, j) <= params.maxDrivableSlopeDeg()) {
                    drivable.set(j * grid + i);
                }
            }
        }
        return drivable;
    }

    /**
     * What is wrong with this arena, or null if nothing is (D16-S5.11, R58).
     *
     * <p>Three findings from one flood fill of the region a player actually plays in — the drivable
     * ground reachable from the first spawn point. An arena passes only if every spawn point is in
     * that region and the region does not touch the arena's edge.
     *
     * <p><b>The edge test is why this is a check and not a constant.</b> The border rim is an
     * additive rise (D16-S5.5), so how impassable it is depends on what the landform under it was
     * doing: at the shipped parameters it holds with 33 m to spare, and at three quarters of the
     * rise it has a gully a car can climb out through. That gully is a property of the seed, so
     * tuning the rise until one arena is sealed says nothing about the next one. Measuring the
     * region instead makes a leaky rim a load-time failure rather than a player discovering they can
     * drive into the void.
     *
     * <p>The fill is four-connected, not eight: a diagonal step between two impassable cells would
     * let it through a gap no vehicle fits down.
     *
     * @return a human-readable finding, or null when the arena is playable
     */
    public static String playabilityFinding(TerrainField field, List<Vector3> spawnPositions) {
        if (spawnPositions.isEmpty()) {
            return null;
        }
        int grid = field.params().gridSize();
        Vector3 first = spawnPositions.get(0);
        int startI = sampleIndexX(field, first.x);
        int startJ = sampleIndexZ(field, first.z);
        if (!field.isDrivableSample(startI, startJ)) {
            return "spawn point at " + first + " is on ground too steep to drive";
        }

        BitSet seen = new BitSet(field.params().sampleCount());
        Deque<int[]> queue = new ArrayDeque<>();
        seen.set(startJ * grid + startI);
        queue.add(new int[] {startI, startJ});
        boolean touchesEdge = false;
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            int i = cell[0];
            int j = cell[1];
            if (i == 0 || j == 0 || i == grid - 1 || j == grid - 1) {
                touchesEdge = true;
            }
            expand(field, seen, queue, i + 1, j, grid);
            expand(field, seen, queue, i - 1, j, grid);
            expand(field, seen, queue, i, j + 1, grid);
            expand(field, seen, queue, i, j - 1, grid);
        }

        for (Vector3 point : spawnPositions) {
            int i = sampleIndexX(field, point.x);
            int j = sampleIndexZ(field, point.z);
            if (!seen.get(j * grid + i)) {
                return "spawn point at " + point + " cannot be reached from " + first + " over drivable ground";
            }
        }
        if (touchesEdge) {
            return "drivable ground reaches the arena edge; the border rim does not contain the "
                    + "playable region (D16-R38)";
        }
        return null;
    }

    /** Whether every spawn point can reach every other, and the rim contains them all. */
    public static boolean spawnPointsConnected(TerrainField field, List<Vector3> spawnPositions) {
        return playabilityFinding(field, spawnPositions) == null;
    }

    private static void expand(TerrainField field, BitSet seen, Deque<int[]> queue, int i, int j, int grid) {
        if (i < 0 || j < 0 || i >= grid || j >= grid) {
            return;
        }
        int index = j * grid + i;
        if (seen.get(index) || !field.isDrivableSample(i, j)) {
            return;
        }
        seen.set(index);
        queue.add(new int[] {i, j});
    }

    private static int sampleIndexX(TerrainField field, float worldX) {
        int grid = field.params().gridSize();
        int i = Math.round((worldX - field.sampleX(0)) / field.params().cellSizeM());
        return Math.max(0, Math.min(i, grid - 1));
    }

    private static int sampleIndexZ(TerrainField field, float worldZ) {
        int grid = field.params().gridSize();
        int j = Math.round((worldZ - field.sampleZ(0)) / field.params().cellSizeM());
        return Math.max(0, Math.min(j, grid - 1));
    }

    /** Smoothstep from 1 at t=0 to 0 at t=1 — the falloff shape, written once. */
    private static float smoothstepDown(float t) {
        float u = 1f - Math.max(0f, Math.min(1f, t));
        return u * u * (3f - 2f * u);
    }
}
