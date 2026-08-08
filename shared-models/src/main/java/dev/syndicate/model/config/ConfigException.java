/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.config;

import dev.syndicate.model.ExitCode;

/**
 * A launch configuration problem that must terminate the process.
 *
 * <p>Carries the {@link ExitCode} rather than letting the caller guess, because D03-S4.4 assigns a
 * distinct code per cause and AC-D03-7 requires each to be produced by exactly its stated cause.
 */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ExitCode exitCode;

    public ConfigException(ExitCode exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public ConfigException(ExitCode exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /** The process exit code this failure maps to (D03-S4.4). */
    public ExitCode exitCode() {
        return exitCode;
    }
}
