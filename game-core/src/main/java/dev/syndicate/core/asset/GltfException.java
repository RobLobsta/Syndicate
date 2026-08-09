/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

/**
 * A glTF document that cannot be read: not glTF, truncated, malformed, or referring to something
 * that is not there (docs/08_asset_pipeline.md#D08-S5.3).
 *
 * <p>Unchecked, and thrown rather than accumulated. {@link AssetLoader} collects findings instead of
 * throwing because a content directory is fixed by seeing every problem at once — but a file that
 * does not parse has exactly one problem, and there is nothing to carry on with. The loader's seam
 * turns this into an A503 finding for the part it was reading (DEV-010).
 */
public final class GltfException extends RuntimeException {

    public GltfException(String message) {
        super(message);
    }

    public GltfException(String message, Throwable cause) {
        super(message, cause);
    }
}
