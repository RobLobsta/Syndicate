/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

/**
 * The screens the client can be showing, and the only vocabulary for a transition.
 *
 * <p>An enum rather than a screen returning its successor object, so a transition is a value that
 * can be logged, asserted on in a test with no GL context, and named on the command line by
 * {@code --start-screen} — which is how a machine with no display captures the menu.
 */
public enum ScreenId {

    /** Title, and the choice to play or leave. */
    MAIN_MENU,

    /** Pick the vehicle you will drive (D01-NG1: choose a prebuilt one, do not build one). */
    GARAGE,

    /** The match itself: the only screen with a world in it. */
    MATCH,

    /** Not a screen. Asking for it closes the window. */
    QUIT;

    /** Parses a {@code --start-screen} argument, case-insensitively. */
    public static ScreenId parse(String raw) {
        for (ScreenId id : values()) {
            if (id.name().equalsIgnoreCase(raw)) {
                return id;
            }
        }
        throw new IllegalArgumentException("unknown screen '" + raw + "'");
    }
}
