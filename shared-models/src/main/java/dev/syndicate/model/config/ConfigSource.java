/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.config;

/**
 * Where an effective configuration value came from (docs/03_runtime_modes.md#D03-R5).
 *
 * <p>Every value is logged at startup with its source, so a misconfiguration is always diagnosable
 * from the log alone — the ordinal order here is the precedence order, later winning.
 */
public enum ConfigSource {
    DEFAULT,
    CONFIG_FILE,
    ENVIRONMENT,
    CLI;
}
