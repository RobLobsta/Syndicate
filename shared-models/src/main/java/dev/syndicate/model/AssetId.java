/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An authored, permanent content identifier: {@code armor_plate_medium_01}.
 *
 * <p>One of the three identifier kinds of docs/00_master_index.md#D00-S4.5, and the only one
 * permitted in authored content files (D00-R19). It is a distinct type rather than a bare
 * {@code String} precisely because the other two kinds — {@code EntityId} and native handles — are
 * numeric and would otherwise be interchangeable at a call site.
 *
 * @param value the lowercase-snake identifier, already validated against {@link #PATTERN}
 */
public record AssetId(String value) implements Comparable<AssetId> {

    /** D00-R19. Three to sixty-four characters, lowercase snake, leading letter. */
    public static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    public AssetId {
        Objects.requireNonNull(value, "asset id");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "invalid asset id '" + value + "': must match " + PATTERN.pattern() + " (D00-R19)");
        }
    }

    @JsonCreator
    public static AssetId of(String value) {
        return new AssetId(value);
    }

    /** True if {@code candidate} would be accepted, without constructing or throwing. */
    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }

    @JsonValue
    @Override
    public String toString() {
        return value;
    }

    /** Natural ordering is lexicographic, which gives deterministic iteration over asset maps (G3). */
    @Override
    public int compareTo(AssetId other) {
        return value.compareTo(other.value);
    }
}
