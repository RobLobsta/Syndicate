/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.model.RuntimeMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a runtime mode's system schedule from the fixed catalogue
 * (docs/03_runtime_modes.md#D03-S5.2).
 *
 * <p>The schedule starts as the full ordered catalogue of D04-S4.4 and is <em>filtered</em>, never
 * reordered or assembled by hand. Filtering preserves order by construction — {@link SystemSlot}'s
 * declaration order is the catalogue order — so the {@code isSubsequenceOf(s, FULL_CATALOGUE_ORDER)}
 * assertion D03-S5.2 ends on holds structurally. {@link #verifyOrder} asserts it anyway, because
 * "structurally true" is a claim about code that a later edit can quietly falsify (G3).
 *
 * <p>Until this existed the schedule was assembled by hand inside test scenes, which is how a
 * process differs from a test: a test can list the four systems it cares about, while a running game
 * has to be able to say what its mode's schedule <em>is</em>. That list is now derived from one
 * table, so a mode cannot run a system D03-S5.2 excludes and cannot skip one it includes.
 */
public final class SystemSetFactory {

    private static final Logger LOG = LoggerFactory.getLogger(SystemSetFactory.class);

    private SystemSetFactory() {
        throw new AssertionError("no instances");
    }

    /**
     * The systems a mode runs, in catalogue order (D03-S5.2).
     *
     * <p>Providers are consulted in the order given and the first non-null wins, so a client passes
     * {@code (clientProvider, coreProvider)} and a dedicated server passes the core provider alone.
     * A slot no provider implements is left out and named in one summary log line — the schedule is
     * a description of what will actually run, not of what the catalogue wishes were written.
     *
     * @throws IllegalStateException if the assembled list is not in ascending slot order
     */
    public static List<EntitySystem> forMode(RuntimeMode mode, SystemProvider... providers) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(providers, "providers");

        List<EntitySystem> systems = new ArrayList<>();
        List<SystemSlot> missing = new ArrayList<>();
        for (SystemSlot slot : SystemSlot.catalogue()) {
            if (!slot.isPresentIn(mode)) {
                continue;
            }
            EntitySystem system = build(slot, mode, providers);
            if (system == null) {
                missing.add(slot);
                continue;
            }
            if (system.order() != slot.order()) {
                // A system whose order() disagrees with its slot would be sorted into a different
                // position by World.registerSystems, which is the one way filtering could still
                // produce a reordered schedule.
                throw new IllegalStateException("system " + system.systemName() + " reports order " + system.order()
                        + " but fills slot " + slot + " (" + slot.order() + ")");
            }
            systems.add(system);
        }
        verifyOrder(systems);

        if (!missing.isEmpty()) {
            LOG.warn(
                    "{} runs {} of {} scheduled systems; {} are not implemented yet: {}",
                    mode,
                    systems.size(),
                    systems.size() + missing.size(),
                    missing.size(),
                    missing);
        }
        LOG.info(
                "{} schedule: {}",
                mode,
                systems.stream().map(EntitySystem::systemName).toList());
        return List.copyOf(systems);
    }

    /** The slots D03-S5.2 puts in a mode's schedule, implemented or not. */
    public static List<SystemSlot> slotsFor(RuntimeMode mode) {
        Objects.requireNonNull(mode, "mode");
        return SystemSlot.catalogue().stream()
                .filter(slot -> slot.isPresentIn(mode))
                .toList();
    }

    /**
     * Asserts that a schedule is a subsequence of the catalogue (D03-S5.2, G3).
     *
     * @throws IllegalStateException on the first pair that is out of order
     */
    public static void verifyOrder(List<EntitySystem> systems) {
        int previous = Integer.MIN_VALUE;
        for (EntitySystem system : systems) {
            if (system.order() <= previous) {
                throw new IllegalStateException("schedule is not in catalogue order: " + system.systemName()
                        + " has order " + system.order() + " after " + previous);
            }
            previous = system.order();
        }
    }

    private static EntitySystem build(SystemSlot slot, RuntimeMode mode, SystemProvider[] providers) {
        for (SystemProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            EntitySystem system = provider.create(slot, mode);
            if (system != null) {
                return system;
            }
        }
        return null;
    }
}
