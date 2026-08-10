/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.match;

import dev.syndicate.model.BotDifficulty;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.MatchOutcome;
import java.util.List;

/**
 * What one complete match produced (docs/11_ai_bots_and_match_simulation.md#D11-S5.8).
 *
 * <p>The deliverable of an offline run, and the unit a balance sweep aggregates. It is a record of
 * records with no world reference in it, deliberately: the world it describes is torn down before
 * the report is read, and a report that held entity ids would be a report you could not compare with
 * one from another run.
 *
 * <p><b>Determinism is checkable on this type.</b> AC-D11-14 requires two runs with the same seed
 * and configuration to produce identical reports. That is an equality test on this record — which is
 * only meaningful because every field is a value and the lists are in a fixed order (players by
 * {@code playerId}, parts by id).
 *
 * <p>{@link #timing} is the exception and is excluded from equality, which is why this record
 * overrides {@code equals} rather than taking the generated one. How many milliseconds a tick took
 * is a measurement of the host, not an output of the simulation: two runs of the same seed on the
 * same machine differ there by a few percent every time. Including it would make AC-D11-14
 * permanently red for a reason that has nothing to do with determinism. Everything in
 * {@link #physics} — entity and body high-water marks, NaN events — <em>is</em> a simulation output
 * and stays in.
 *
 * @param seed the match seed the run was reproducible from (G4)
 * @param mode the game mode played
 * @param botDifficulty the difficulty the bots ran at
 * @param botCount how many bots were in the match
 * @param durationTicks how many ticks the run took to reach {@code RESULTS}
 * @param reachedSafetyCap true when the run was stopped by the cap rather than by the match ending;
 *     D11-E15 makes that a failure, never a quietly truncated report
 * @param outcome how the match ended
 * @param winnerPlayerId the winning player's {@code playerId}, or -1
 * @param winnerTeamId the winning team, or -1
 * @param players one row per participant, ascending by {@code playerId}
 * @param physics the run's simulation health counters, which are deterministic
 * @param timing how long the host took, which is not
 */
public record MatchReport(
        long seed,
        GameMode mode,
        BotDifficulty botDifficulty,
        int botCount,
        long durationTicks,
        boolean reachedSafetyCap,
        MatchOutcome outcome,
        int winnerPlayerId,
        int winnerTeamId,
        List<PlayerRow> players,
        PhysicsSummary physics,
        TimingSummary timing) {

    public MatchReport {
        players = List.copyOf(players);
    }

    /** Equality over everything the simulation decided, and nothing the host measured. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MatchReport that)) {
            return false;
        }
        return seed == that.seed
                && durationTicks == that.durationTicks
                && reachedSafetyCap == that.reachedSafetyCap
                && winnerPlayerId == that.winnerPlayerId
                && winnerTeamId == that.winnerTeamId
                && botCount == that.botCount
                && mode == that.mode
                && botDifficulty == that.botDifficulty
                && outcome == that.outcome
                && players.equals(that.players)
                && physics.equals(that.physics);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                seed,
                mode,
                botDifficulty,
                botCount,
                durationTicks,
                reachedSafetyCap,
                outcome,
                winnerPlayerId,
                winnerTeamId,
                players,
                physics);
    }

    /**
     * One participant's line (D11-S5.8 {@code perPlayer}).
     *
     * @param playerId the stable id, which is also this list's sort key
     * @param name the display name
     * @param isBot whether a bot drove it
     * @param assemblyId which vehicle they fielded
     * @param kills vehicles destroyed
     * @param deaths vehicles lost
     * @param assists kills contributed to within the assist window
     * @param objectiveScore the D01-S5.4 running total
     * @param damageDealt hit points removed from other vehicles
     * @param distanceTravelledM how far they drove, integrated over the ticks they were alive
     * @param timeAliveTicks ticks spent with a living vehicle
     */
    public record PlayerRow(
            int playerId,
            String name,
            boolean isBot,
            String assemblyId,
            int kills,
            int deaths,
            int assists,
            int objectiveScore,
            float damageDealt,
            float distanceTravelledM,
            long timeAliveTicks) {}

    /**
     * Counters that say whether the run was <em>healthy</em>, not just who won
     * (D11-S5.8 {@code physics}).
     *
     * <p>A sweep that only reported outcomes would pass with a simulation that produced a NaN an
     * hour. Every field here is a function of the seed and the configuration, so two runs of the
     * same match agree on all of them.
     *
     * @param maxEntities the high-water mark of live entities
     * @param maxBodies the high-water mark of Bullet rigid bodies
     * @param nanEvents how many bodies the physics world removed for non-finite state
     */
    public record PhysicsSummary(int maxEntities, int maxBodies, int nanEvents) {}

    /**
     * What the host machine took to run it, which D12-S5.6's budgets are checked against.
     *
     * <p>Separate from {@link PhysicsSummary} because it is the one part of a report that is not
     * reproducible: the same seed on the same machine varies by a few percent every run. See the
     * class note on equality.
     *
     * @param maxTickDurationMs the slowest single tick
     * @param meanTickDurationMs the mean tick
     */
    public record TimingSummary(double maxTickDurationMs, double meanTickDurationMs) {}
}
