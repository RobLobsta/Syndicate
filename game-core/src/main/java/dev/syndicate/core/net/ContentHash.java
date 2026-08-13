/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.core.component.ComponentCatalogue;
import dev.syndicate.core.ecs.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The {@code contentHash} two peers compare at the handshake
 * (docs/10_networking_multiplayer.md#D10-S4.5).
 *
 * <p>D10-R11 defines it as SHA-256 over {@code asset-index.json} plus the component type list. The
 * asset index is the digest of everything in {@code assets/} (D08-S4.7) and the component list is
 * the wire numbering of D04-R22, so between them they cover every way two builds can disagree about
 * what a packet means.
 *
 * <p>The list is fed in as text rather than read from a file: {@link ComponentCatalogue} <em>is</em>
 * the checked-in list (its class note says so), and hashing a file generated from it would only add
 * a way for the two to differ.
 *
 * <p><b>It is an integrity check, not a security measure</b> (D10-R26, D10-E14). It exists to catch
 * honest version skew — the case where a desync would otherwise be unexplainable — and it detects
 * tampering only by accident.
 */
public final class ContentHash {

    private ContentHash() {
        throw new AssertionError("no instances");
    }

    /**
     * Hashes an asset index document together with the component catalogue.
     *
     * @param assetIndexJson the bytes of {@code asset-index.json}, or an empty array when a peer has
     *     no index — a match with no content still needs a defined hash, and two peers that both
     *     have none legitimately agree
     * @return the first 64 bits of the SHA-256 digest
     */
    public static long of(byte[] assetIndexJson) {
        return of(assetIndexJson, ComponentCatalogue.TYPES);
    }

    /** As {@link #of(byte[])}, with an explicit component list. For tests that vary one input. */
    public static long of(byte[] assetIndexJson, List<Class<? extends Component>> componentTypes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every conforming JRE; if it is missing, the platform is not one.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        digest.update(assetIndexJson);
        for (Class<? extends Component> type : componentTypes) {
            digest.update(type.getName().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        byte[] full = digest.digest();

        long truncated = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            truncated = (truncated << 8) | (full[i] & 0xFFL);
        }
        return truncated;
    }
}
