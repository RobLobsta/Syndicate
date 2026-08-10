/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Who has hurt which vehicle, and how recently
 * (docs/01_product_game_design.md#D01-S5.4, docs/07_damage_destruction_model.md#D07-S5.2).
 *
 * <p>Two questions with different lifetimes, which is why one structure answers both. The <b>match
 * total</b> per attacker per victim is retained for the whole match, because the results screen
 * shows a full damage breakdown per player (D01-S5.4). The <b>recent</b> total, over
 * {@link #ASSIST_WINDOW_TICKS}, decides assists: a player who took a vehicle to 25% and then
 * disengaged deserves credit when a teammate finishes it ten seconds later, and does not deserve it
 * for a hit they landed three minutes ago.
 *
 * <p><b>The window is bucketed, not a log.</b> A list of every damage application would be exact and
 * would also grow without bound — propagation alone produces several entries per hit, and a busy
 * ten-minute match is six figures of them. Instead each attacker-victim pair carries
 * {@link #WINDOW_BUCKETS} buckets of {@link #BUCKET_TICKS} ticks, indexed by tick, each stamped with
 * the tick range it holds. A bucket whose stamp is out of range reads as zero and is overwritten on
 * its next write, so expiry costs nothing and memory is fixed per pair. The cost is that the window
 * boundary is quantised to {@link #BUCKET_TICKS} — a sixth of a second on a ten-second window, well
 * inside the precision anyone could observe.
 *
 * <p>Keys are sorted, so every iteration over contributors is in ascending entity id (G3): two peers
 * replaying the same match award the same assists in the same order.
 */
public final class DamageLedger {

    /** Ticks within which damage still counts toward an assist — ten seconds (D01-S5.4). */
    public static final int ASSIST_WINDOW_TICKS = 600;

    /** Fraction of a vehicle's effective hit points a contributor needs for an assist (D01-S5.4). */
    public static final float ASSIST_DAMAGE_FRACTION = 0.20f;

    /** How many buckets cover the assist window. */
    public static final int WINDOW_BUCKETS = 10;

    /** Ticks per bucket. {@code WINDOW_BUCKETS × BUCKET_TICKS} is exactly the window. */
    public static final int BUCKET_TICKS = ASSIST_WINDOW_TICKS / WINDOW_BUCKETS;

    /** One attacker's damage against one victim. */
    private static final class Contribution {

        /** Every hit point removed since the match began. */
        private float matchTotal;

        /** Damage per bucket; {@code bucketStart[i]} says which tick range bucket {@code i} holds. */
        private final float[] bucketAmount = new float[WINDOW_BUCKETS];

        private final long[] bucketStart = new long[WINDOW_BUCKETS];

        private Contribution() {
            Arrays.fill(bucketStart, Long.MIN_VALUE);
        }

        private void record(float amount, long tick) {
            matchTotal += amount;
            long start = bucketStartFor(tick);
            int index = bucketIndexFor(tick);
            if (bucketStart[index] != start) {
                bucketStart[index] = start;
                bucketAmount[index] = 0f;
            }
            bucketAmount[index] += amount;
        }

        private float recent(long now, int windowTicks) {
            long oldest = now - windowTicks;
            float total = 0f;
            for (int i = 0; i < WINDOW_BUCKETS; i++) {
                // A bucket is in range when any of its ticks is: its last tick is after the cut-off
                // and its first is not in the future.
                if (bucketStart[i] + BUCKET_TICKS > oldest && bucketStart[i] <= now) {
                    total += bucketAmount[i];
                }
            }
            return total;
        }

        private static long bucketStartFor(long tick) {
            return Math.floorDiv(tick, BUCKET_TICKS) * (long) BUCKET_TICKS;
        }

        private static int bucketIndexFor(long tick) {
            return (int) Math.floorMod(Math.floorDiv(tick, (long) BUCKET_TICKS), (long) WINDOW_BUCKETS);
        }
    }

    /** {@code (victimVehicle, attackerPlayer)} → their contribution, in ascending key order (G3). */
    private final TreeMap<Long, Contribution> contributions = new TreeMap<>();

    /** Records damage actually applied — the post-armour figure, not what was aimed. */
    public void record(int victimVehicleEntity, int attackerPlayerEntity, float appliedAmount, long tick) {
        if (appliedAmount <= 0f) {
            return;
        }
        contributions
                .computeIfAbsent(key(victimVehicleEntity, attackerPlayerEntity), unused -> new Contribution())
                .record(appliedAmount, tick);
    }

    /** Total hit points this player has removed from this vehicle since the match began. */
    public float matchTotal(int victimVehicleEntity, int attackerPlayerEntity) {
        Contribution contribution = contributions.get(key(victimVehicleEntity, attackerPlayerEntity));
        return contribution == null ? 0f : contribution.matchTotal;
    }

    /** Hit points this player removed from this vehicle within {@code windowTicks} of {@code tick}. */
    public float within(int victimVehicleEntity, int attackerPlayerEntity, long tick, int windowTicks) {
        Contribution contribution = contributions.get(key(victimVehicleEntity, attackerPlayerEntity));
        return contribution == null ? 0f : contribution.recent(tick, windowTicks);
    }

    /** Every player who has damaged this vehicle, in ascending entity id (G3). */
    public List<Integer> contributorsAgainst(int victimVehicleEntity) {
        List<Integer> players = new ArrayList<>();
        long low = (long) victimVehicleEntity << 32;
        long high = low | 0xFFFFFFFFL;
        for (Map.Entry<Long, Contribution> entry :
                contributions.subMap(low, true, high, true).entrySet()) {
            players.add((int) (entry.getKey() & 0xFFFFFFFFL));
        }
        return players;
    }

    /** How many attacker-victim pairs the ledger holds. For assertions and telemetry. */
    public int size() {
        return contributions.size();
    }

    /** Drops everything. Called when a match ends, never during one. */
    public void clear() {
        contributions.clear();
    }

    /**
     * The composite key, victim in the high word so a victim's contributors are contiguous.
     *
     * <p>The attacker half is masked to unsigned 32 bits rather than sign-extended. An
     * {@code EntityId} packs its generation into the high byte (D04-R23), so an id with a
     * generation past 127 is a negative Java {@code int} — sign-extending it would spill into the
     * victim's word and file the contribution under a different vehicle entirely.
     */
    private static long key(int victimVehicleEntity, int attackerPlayerEntity) {
        return ((long) victimVehicleEntity << 32) | (attackerPlayerEntity & 0xFFFFFFFFL);
    }
}
