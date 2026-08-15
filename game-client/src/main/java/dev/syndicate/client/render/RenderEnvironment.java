/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cubemap;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import java.util.ArrayList;
import java.util.List;
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRTextureAttribute;
import net.mgsx.gltf.scene3d.lights.DirectionalLightEx;
import net.mgsx.gltf.scene3d.lights.SpotLightEx;
import net.mgsx.gltf.scene3d.utils.IBLBuilder;

/**
 * The lighting every drawn thing shares (docs/03_runtime_modes.md#D03-S5.1 step 8).
 *
 * <p>One sun, an image-based ambient, and whatever spot lights the vehicles are carrying. The IBL
 * maps are not decoration: a glTF PBR material with
 * no environment to reflect renders as flat, dark plastic, so a car built from real art would look
 * worse in the game than it does in the tool that produced it. They are generated procedurally
 * rather than loaded, which keeps the client free of a texture asset the pipeline does not author.
 *
 * <p><b>Owner of three cubemaps and a BRDF lookup</b> (G19). Disposed by {@link RenderContext},
 * before the models that reference them are dropped.
 */
public final class RenderEnvironment implements Disposable {

    /** How bright the sun is. Above 1 the PBR shader's tonemapping starts to clip highlights. */
    public static final float SUN_INTENSITY = 3.2f;

    /** Ambient term, so a surface facing away from the sun is readable rather than black. */
    public static final float AMBIENT = 0.55f;

    /**
     * What the sun falls to at midnight, as a fraction of {@link #SUN_INTENSITY}.
     *
     * <p>Not zero. A moonless night with no ambient at all is a black screen with two cones in it,
     * which is not atmospheric — it is unplayable, and it is the mistake every night mode makes
     * once. 6% keeps a silhouette readable against the ground while leaving headlights as by far
     * the brightest thing in the frame.
     */
    public static final float NIGHT_SUN_FRACTION = 0.06f;

    /** What the ambient falls to at midnight. Higher than the sun's: night is flat, not black. */
    public static final float NIGHT_AMBIENT_FRACTION = 0.11f;

    /** The sky at noon and at midnight, which the frame is cleared to. */
    public static final Color DAY_SKY = new Color(0.42f, 0.50f, 0.60f, 1f);

    public static final Color NIGHT_SKY = new Color(0.035f, 0.043f, 0.070f, 1f);

    /**
     * How dark every environment starts, set once at launch by {@code --night}.
     *
     * <p>Static, and that is not laziness: the client builds two of these — the garage's and the
     * match's — and a launch option that lit one of them would be a capture of a car in daylight
     * beside a car at midnight. It is temporary either way. Time of day is properly an arena's
     * property, D16-S4 reserves a {@code sky} block for it, and this goes when that block does
     * anything.
     */
    private static float launchNightFraction;

    /** Sets the hour every environment built after this call starts at. */
    public static void setLaunchNightFraction(float fraction) {
        launchNightFraction = Math.max(0f, Math.min(1f, fraction));
    }

    /** gdx-gltf ships the split-sum BRDF integration its PBR shader samples. */
    private static final String BRDF_LUT_RESOURCE = "net/mgsx/gltf/shaders/brdfLUT.png";

    private final Environment environment = new Environment();
    private final DirectionalLightEx sun = new DirectionalLightEx();
    private final ColorAttribute ambient =
            new ColorAttribute(ColorAttribute.AmbientLight, AMBIENT, AMBIENT, AMBIENT, 1f);
    private final List<SpotLightEx> spots = new ArrayList<>();
    private final Color sky = new Color(DAY_SKY);
    private float nightFraction;
    private final Cubemap diffuse;
    private final Cubemap specular;
    private final com.badlogic.gdx.graphics.Texture brdf;

    public RenderEnvironment() {
        // Angled rather than overhead: a light straight down flattens a car's shoulders, which are
        // the shapes that make one readable as a car at a distance.
        sun.direction.set(-0.45f, -0.8f, -0.4f).nor();
        sun.baseColor.set(Color.WHITE);
        sun.intensity = SUN_INTENSITY;
        sun.updateColor();
        environment.add(sun);
        environment.set(ambient);

        IBLBuilder ibl = IBLBuilder.createOutdoor(sun);
        diffuse = ibl.buildIrradianceMap(32);
        specular = ibl.buildRadianceMap(8);
        ibl.dispose();

        // Shipped inside gdx-gltf rather than authored here; without it the specular response of
        // every metal in the scene is wrong at grazing angles, which is most of a car's silhouette.
        brdf = new com.badlogic.gdx.graphics.Texture(Gdx.files.classpath(BRDF_LUT_RESOURCE));
        environment.set(new PBRTextureAttribute(PBRTextureAttribute.BRDFLUTTexture, brdf));
        environment.set(new PBRCubemapAttribute(PBRCubemapAttribute.DiffuseEnv, diffuse));
        environment.set(new PBRCubemapAttribute(PBRCubemapAttribute.SpecularEnv, specular));
        setNightFraction(launchNightFraction);
    }

    public Environment environment() {
        return environment;
    }

    /**
     * How dark it is: 0 is noon, 1 is midnight.
     *
     * <p>One number rather than a time of day, because everything that reads it wants the same
     * thing — a headlight's intensity, a beam's opacity, the sky's colour and the sun's strength all
     * scale with <em>how much the lights matter</em> and none of them cares what o'clock it is.
     */
    public void setNightFraction(float fraction) {
        nightFraction = Math.max(0f, Math.min(1f, fraction));

        float sunScale = lerp(1f, NIGHT_SUN_FRACTION, nightFraction);
        sun.intensity = SUN_INTENSITY * sunScale;
        // Moonlight is blue, and it is blue in every night scene anyone has ever shot. Cooling the
        // sun as it dims is most of what makes a dark frame read as night rather than as underexposed.
        sun.baseColor.set(
                lerp(1f, 0.62f, nightFraction), lerp(1f, 0.72f, nightFraction), lerp(1f, 1.0f, nightFraction), 1f);
        sun.updateColor();

        float ambientScale = lerp(1f, NIGHT_AMBIENT_FRACTION, nightFraction);
        float level = AMBIENT * ambientScale;
        ambient.color.set(level, level, level * 1.25f, 1f);
        environment.set(ambient);

        sky.set(DAY_SKY).lerp(NIGHT_SKY, nightFraction);
    }

    /** How dark it is, as {@link #setNightFraction} last set it. */
    public float nightFraction() {
        return nightFraction;
    }

    /** The colour the frame is cleared to, which is the sky at this hour. */
    public Color sky() {
        return sky;
    }

    /**
     * Drops every spot light the last frame contributed.
     *
     * <p>Rebuilt per frame rather than mutated, because a lamp that was shot off this frame must
     * stop lighting and there is no cheaper way to be sure of that than to start from none.
     */
    public void clearSpotLights() {
        for (int i = 0; i < spots.size(); i++) {
            environment.remove(spots.get(i));
        }
        spots.clear();
    }

    /** Adds one vehicle lamp for this frame (see {@link VehicleLights}). */
    public void addSpotLight(SpotLightEx light) {
        spots.add(light);
        environment.add(light);
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /** The sun's direction, for anything that wants to shade consistently with it. */
    public Vector3 sunDirection() {
        return sun.direction;
    }

    @Override
    public void dispose() {
        diffuse.dispose();
        specular.dispose();
        brdf.dispose();
    }
}
