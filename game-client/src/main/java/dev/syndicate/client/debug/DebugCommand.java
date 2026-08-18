/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.debug;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One row of the debug console: a label, what it currently reads, and what it does.
 *
 * <p>A record carrying two functions rather than an enum with a switch, because the set of controls
 * is expected to grow every time somebody needs to see something — and the cost of adding one
 * should be one line in {@link DebugConsole}, not an enum entry plus a case in a dispatcher plus a
 * label in a table that will drift from both.
 *
 * <p>{@link #value} is read every frame, so a row shows the simulation's real answer rather than
 * what the console last asked for. That distinction is the whole point of a live tool: when a spawn
 * is refused or a toggle is overridden by the simulation, the row says so instead of continuing to
 * report the request.
 */
public record DebugCommand(String label, Supplier<String> value, Runnable action, boolean actionable) {

    public DebugCommand {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(action, "action");
    }

    /** A row that does something when activated. */
    public static DebugCommand of(String label, Supplier<String> value, Runnable action) {
        return new DebugCommand(label, value, action, true);
    }

    /** A row that only reports. Never selectable, so the cursor cannot rest somewhere inert. */
    public static DebugCommand readout(String label, Supplier<String> value) {
        return new DebugCommand(label, value, () -> {}, false);
    }

    /** Runs this row's action, if it has one. */
    public void activate() {
        if (actionable) {
            action.run();
        }
    }
}
