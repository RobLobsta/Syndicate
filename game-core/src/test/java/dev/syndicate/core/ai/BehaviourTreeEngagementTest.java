/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.BotControllerComponent;
import dev.syndicate.model.BotDifficulty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the {@code engage} branch asks the driver for
 * (docs/11_ai_bots_and_match_simulation.md#D11-S5.1 {@code MaintainEngagementRange}).
 *
 * <p>The interesting case is not "close" or "back off" — it is the third one, a bot that is already
 * about the right distance away. The obvious radial answer hands it a destination a couple of metres
 * from where it stands, which no steering angle reaches, and the bot then shuffles at walking pace
 * for the rest of the match (DISC-059). Every assertion below is about the destination being
 * <em>reachable</em>, because that is the property the radial version silently lost.
 */
@Tag("unit")
class BehaviourTreeEngagementTest {

    private static final Vector3 NORTH = new Vector3(0f, 0f, 1f);

    private final BehaviourTree tree = new BehaviourTree();
    private final TargetSelection targeting = new TargetSelection();
    private final Vector3 aimPoint = new Vector3();

    /** Well outside the band, the bot still closes straight in along the radius. */
    @Test
    void aDistantTargetIsClosedOnAlongTheRadius() {
        BotControllerComponent bot = engage(new Vector3(0f, 0f, -60f), new Vector3(0f, 0f, 0f), NORTH);

        // Standoff short of the target, on the line between the two.
        assertThat(bot.blackboard.destination.z).isCloseTo(-BehaviourTree.ENGAGE_STANDOFF_M, within(0.01f));
        assertThat(bot.blackboard.destination.x).isCloseTo(0f, within(0.01f));
    }

    /**
     * At standoff range the destination is a chord around the target, not a point underfoot.
     *
     * <p>Both halves matter: the range is held, so the bot is still fighting at the distance its
     * guns want, and the destination is far enough away to be steered to rather than pivoted onto.
     */
    @Test
    void aTargetAtStandoffRangeIsOrbited() {
        Vector3 target = new Vector3(0f, 0f, 0f);
        Vector3 self = new Vector3(0f, 0f, -BehaviourTree.ENGAGE_STANDOFF_M);
        BotControllerComponent bot = engage(self, target, NORTH);

        Vector3 destination = bot.blackboard.destination;
        assertThat(destination.dst(target))
                .as("the orbit holds the standoff radius")
                .isCloseTo(BehaviourTree.ENGAGE_STANDOFF_M, within(0.01f));
        assertThat(destination.dst(self))
                .as("a destination inside the turning circle is what caused DISC-059")
                .isGreaterThan(SteeringSolver.ARRIVE_RADIUS_M);
    }

    /** A bot a little inside the band orbits too, rather than reversing two metres. */
    @Test
    void aTargetJustInsideTheBandIsOrbitedRatherThanBackedAwayFrom() {
        Vector3 target = new Vector3(0f, 0f, 0f);
        Vector3 self = new Vector3(0f, 0f, -(BehaviourTree.ENGAGE_STANDOFF_M - BehaviourTree.ENGAGE_BAND_M + 1f));
        BotControllerComponent bot = engage(self, target, NORTH);

        assertThat(bot.blackboard.destination.dst(self)).isGreaterThan(SteeringSolver.ARRIVE_RADIUS_M);
    }

    /** Which way round is decided by the nose, so two bots facing opposite ways circle opposite ways. */
    @Test
    void theOrbitDirectionFollowsTheNose() {
        Vector3 target = new Vector3(0f, 0f, 0f);
        Vector3 self = new Vector3(0f, 0f, -BehaviourTree.ENGAGE_STANDOFF_M);

        float clockwise = engage(self, target, new Vector3(1f, 0f, 0f)).blackboard.destination.x;
        float anticlockwise = engage(self, target, new Vector3(-1f, 0f, 0f)).blackboard.destination.x;

        assertThat(clockwise).isNotEqualTo(anticlockwise);
        assertThat(Math.signum(clockwise)).isEqualTo(-Math.signum(anticlockwise));
    }

    /** G3: the same inputs produce the same destination, every time, with no stream consulted. */
    @Test
    void theOrbitIsDeterministic() {
        Vector3 target = new Vector3(12f, 0f, -4f);
        Vector3 self = new Vector3(-2f, 0f, 3f);

        Vector3 first = new Vector3(engage(self, target, NORTH).blackboard.destination);
        Vector3 second = new Vector3(engage(self, target, NORTH).blackboard.destination);

        assertThat(first).isEqualTo(second);
    }

    /** Runs the tree with one visible target and returns the bot it decided for. */
    private BotControllerComponent engage(Vector3 self, Vector3 target, Vector3 forward) {
        BotControllerComponent bot = new BotControllerComponent();
        SensorSnapshot snapshot = bot.perceivedWorld;
        snapshot.selfPosition.set(self);
        snapshot.selfIntegrity = 1f;
        snapshot.nearbyObstacles.clear();
        snapshot.beginTargets();
        PerceivedTarget seen = snapshot.addTarget();
        seen.entity = 7;
        seen.position.set(target);
        seen.integrity = 1f;
        seen.hasLineOfSight = true;

        BtState state = tree.tick(
                bot,
                BotDifficultyTable.defaults().get(BotDifficulty.NORMAL),
                snapshot,
                forward.nor(),
                seen.entity,
                true,
                null,
                targeting,
                aimPoint);
        assertThat(state).isEqualTo(BtState.ENGAGE);
        return bot;
    }
}
