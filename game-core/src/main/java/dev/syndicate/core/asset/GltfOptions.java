/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

/**
 * What {@link GltfReader} should bother decoding (docs/08_asset_pipeline.md#D08-S4.5).
 *
 * <p>The dedicated server reads the same files a client does and wants none of the same things out
 * of them: a collision hull is a point set, so normals, texture coordinates and material references
 * are megabytes it allocates, copies and never looks at (G17, D03-S5.5). The options are a switch on
 * that, not a performance knob — {@link #GEOMETRY} is what the headless path uses and
 * {@link #FULL} is what a renderer needs.
 *
 * <p>Positions and indices are never optional: without them there is nothing to return.
 */
public record GltfOptions(boolean readNormals, boolean readTexCoords, boolean readMaterials) {

    /** Positions and indices only — the headless collision path. */
    public static final GltfOptions GEOMETRY = new GltfOptions(false, false, false);

    /** Everything the reader understands, for a renderer. */
    public static final GltfOptions FULL = new GltfOptions(true, true, true);
}
