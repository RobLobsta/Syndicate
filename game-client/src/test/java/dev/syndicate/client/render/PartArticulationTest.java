/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Cosmetic weapon articulation (docs/17_weapon_system.md#D17-S4.4, D17-S5.9).
 *
 * <p>The pose is a pure function of a phase in {@code [0,1]}, which is what makes it testable at all
 * and what guarantees two clients handed the same phase draw the same frame. T-D17-8 and T-D17-17.
 */
@Tag("unit")
class PartArticulationTest {

    private static PartArticulation.Articulation recoil(float travelM) {
        return new PartArticulation.Articulation(
                PartArticulation.Motion.RECOIL,
                PartArticulation.Driver.FIRE,
                new Vector3(0f, 0f, 1f),
                new Vector3(),
                0f,
                travelM,
                0.18f,
                0f,
                0,
                0f);
    }

    private static PartArticulation.Articulation spin(float rateDegPerSec) {
        return new PartArticulation.Articulation(
                PartArticulation.Motion.SPIN,
                PartArticulation.Driver.CONTINUOUS,
                new Vector3(0f, 0f, 1f),
                new Vector3(),
                0f,
                0f,
                0.18f,
                rateDegPerSec,
                0,
                0f);
    }

    @Test
    @DisplayName("A barrel at rest is exactly where its mesh was authored")
    void restPoseIsIdentity() {
        Vector3 translation =
                PartArticulation.pose(recoil(0.06f), 0f, new Matrix4()).getTranslation(new Vector3());
        // isCloseTo rather than isEqualTo: `-travelM * 0` is negative zero, which is numerically
        // identical to zero and not `equals` to it.
        assertThat(translation.x).isCloseTo(0f, within(1e-6f));
        assertThat(translation.y).isCloseTo(0f, within(1e-6f));
        assertThat(translation.z).isCloseTo(0f, within(1e-6f));
    }

    @Test
    @DisplayName("A null block never moves anything")
    void nullArticulationIsIdentity() {
        assertThat(PartArticulation.pose(null, 1f, new Matrix4()).val).isEqualTo(new Matrix4().val);
    }

    @Test
    @DisplayName("Recoil slides backwards along the bore, not forwards")
    void recoilGoesBackwards() {
        // The sign is the one thing here that is easy to get wrong and obvious when it is: a barrel
        // that recoiled forwards would make the gun visibly grow every time it fired.
        Vector3 translation =
                PartArticulation.pose(recoil(0.06f), 1f, new Matrix4()).getTranslation(new Vector3());

        assertThat(translation.z).isEqualTo(-0.06f);
        assertThat(translation.x).isZero();
        assertThat(translation.y).isZero();
    }

    @Test
    @DisplayName("Recoil is linear in phase, so half-way back is half the travel")
    void recoilScalesWithPhase() {
        Vector3 half = PartArticulation.pose(recoil(0.08f), 0.5f, new Matrix4()).getTranslation(new Vector3());
        assertThat(half.z).isCloseTo(-0.04f, within(1e-6f));
    }

    @Test
    @DisplayName("A spin at phase 1 is a full revolution, which is the identity")
    void aFullRevolutionReturnsToRest() {
        Matrix4 full = PartArticulation.pose(spin(360f), 1f, new Matrix4());
        Vector3 probe = new Vector3(1f, 0f, 0f).mul(full);
        assertThat(probe.x).isCloseTo(1f, within(1e-4f));
        assertThat(probe.y).isCloseTo(0f, within(1e-4f));
    }

    @Test
    @DisplayName("A quarter spin about +Z takes +X to +Y")
    void aQuarterSpinRotatesInTheRightDirection() {
        Matrix4 quarter = PartArticulation.pose(spin(360f), 0.25f, new Matrix4());
        Vector3 probe = new Vector3(1f, 0f, 0f).mul(quarter);
        assertThat(probe.x).isCloseTo(0f, within(1e-4f));
        assertThat(probe.y).isCloseTo(1f, within(1e-4f));
    }

    @Test
    @DisplayName("A rotation about a pivot leaves the pivot itself where it is")
    void pivotIsFixed() {
        PartArticulation.Articulation offset = new PartArticulation.Articulation(
                PartArticulation.Motion.SPIN,
                PartArticulation.Driver.CONTINUOUS,
                new Vector3(0f, 1f, 0f),
                new Vector3(0.3f, 0f, 0.2f),
                0f,
                0f,
                0.18f,
                360f,
                0,
                0f);

        Matrix4 pose = PartArticulation.pose(offset, 0.25f, new Matrix4());
        Vector3 pivot = new Vector3(0.3f, 0f, 0.2f).mul(pose);

        assertThat(pivot.x).isCloseTo(0.3f, within(1e-4f));
        assertThat(pivot.z).isCloseTo(0.2f, within(1e-4f));
    }

    @Test
    @DisplayName("An INDEX motion steps one position of its own count per shot")
    void indexStepsOnePosition() {
        PartArticulation.Articulation drum = new PartArticulation.Articulation(
                PartArticulation.Motion.INDEX,
                PartArticulation.Driver.FIRE,
                new Vector3(0f, 0f, 1f),
                new Vector3(),
                0f,
                0f,
                0.1f,
                0f,
                6,
                0f);

        // A full step of a six-position drum is 60 degrees: +X should land at 60 degrees round.
        Vector3 probe = new Vector3(1f, 0f, 0f).mul(PartArticulation.pose(drum, 1f, new Matrix4()));
        assertThat(probe.x).isCloseTo((float) Math.cos(Math.toRadians(60)), within(1e-4f));
        assertThat(probe.y).isCloseTo((float) Math.sin(Math.toRadians(60)), within(1e-4f));
    }

    @Test
    @DisplayName("ELEVATE takes a signed phase, so it depresses as well as elevates")
    void elevateIsSigned() {
        // -X, which is what the tool authors: the world is Y-up right-handed (D00-R14), so a
        // positive rotation about +X would take the bore toward the ground and a gun would depress
        // when the player aimed up.
        PartArticulation.Articulation quadrant = new PartArticulation.Articulation(
                PartArticulation.Motion.ELEVATE,
                PartArticulation.Driver.AIM,
                new Vector3(-1f, 0f, 0f),
                new Vector3(),
                0f,
                0f,
                0.18f,
                0f,
                0,
                20f);

        Vector3 up = new Vector3(0f, 0f, 1f).mul(PartArticulation.pose(quadrant, 1f, new Matrix4()));
        Vector3 down = new Vector3(0f, 0f, 1f).mul(PartArticulation.pose(quadrant, -1f, new Matrix4()));

        assertThat(up.y).isGreaterThan(0.3f);
        assertThat(down.y).isLessThan(-0.3f);
    }

    @Test
    @DisplayName("T-D17-17: the same phase always produces the same pose")
    void poseIsAPureFunctionOfPhase() {
        // The property that makes articulation safe under G6: it reads no clock, so two clients at
        // different frame rates handed the same phase draw the same frame, and nothing about it can
        // reach the simulation.
        for (float phase = 0f; phase <= 1f; phase += 0.1f) {
            Matrix4 first = PartArticulation.pose(recoil(0.05f), phase, new Matrix4());
            Matrix4 second = PartArticulation.pose(recoil(0.05f), phase, new Matrix4());
            assertThat(first.val).isEqualTo(second.val);
        }
    }
}
