/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Quaternion;
import dev.syndicate.model.net.MessageType;
import dev.syndicate.model.net.NetConstants;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The bit-level wire format (docs/10_networking_multiplayer.md#D10-S4.3, #D10-S4.4).
 *
 * <p>These are the tests that would catch a change to one side of the codec and not the other, and
 * the ones that pin the two properties everything above them assumes: a value survives a round trip
 * within its quantisation step, and re-encoding a decoded value does not move it again.
 */
@Tag("unit")
class WireFormatTest {

    @Test
    void bitFields_roundTripAtEveryWidth() {
        BitWriter writer = new BitWriter(4);
        for (int bits = 1; bits <= 32; bits++) {
            writer.writeBits(bits == 32 ? -1 : (1 << bits) - 1, bits);
            writer.writeBits(0, bits);
        }
        BitReader reader = new BitReader(writer.toByteArray());
        for (int bits = 1; bits <= 32; bits++) {
            int expected = bits == 32 ? -1 : (1 << bits) - 1;
            assertThat(reader.readBits(bits)).as("all ones at %d bits", bits).isEqualTo(expected);
            assertThat(reader.readBits(bits)).as("all zeroes at %d bits", bits).isZero();
        }
    }

    @Test
    void writer_rejectsAValueTooWideForItsField() {
        // Truncating would put a plausible wrong number on the wire with nothing left to find it by.
        assertThatThrownBy(() -> new BitWriter().writeBits(256, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit");
    }

    @Test
    void reader_throwsRatherThanReadingPastTheEnd() {
        BitWriter writer = new BitWriter();
        writer.writeBits(1, 4);
        BitReader reader = new BitReader(writer.toByteArray());
        reader.readBits(8);
        assertThatThrownBy(() -> reader.readBits(8)).isInstanceOf(BitReader.MalformedPacketException.class);
    }

    @Test
    void stringsAndTicks_roundTrip() {
        BitWriter writer = new BitWriter();
        writer.writeString("Stampede GT3");
        writer.writeTick(4_294_967_295L);
        writer.writeString("");

        BitReader reader = new BitReader(writer.toByteArray());
        assertThat(reader.readString()).isEqualTo("Stampede GT3");
        assertThat(reader.readTick()).isEqualTo(4_294_967_295L);
        assertThat(reader.readString()).isEmpty();
    }

    @Test
    void position_roundTripsWithinItsQuantisationStep() {
        // D10-S4.3 asks for "≈1.2 cm" over the arena bounds; 800 m over 65,535 steps is 1.22 cm, so
        // the worst-case error is half a step.
        float step = 2f * NetConstants.POSITION_RANGE_M / ((1 << NetConstants.POSITION_BITS) - 1);
        for (float metres : new float[] {0f, 0.005f, -0.005f, 1.234f, -87.5f, 399.9f, -399.9f}) {
            float decoded = Quantisation.decodePositionAxis(Quantisation.encodePositionAxis(metres));
            assertThat(decoded).as("%f m", metres).isCloseTo(metres, within(step / 2f + 1e-4f));
        }
        assertThat(step).isCloseTo(0.0122f, within(0.0002f));
    }

    @Test
    void position_clampsRatherThanFailingOutsideTheArena() {
        // A vehicle outside the bounds is a bug in the arena or a kill plane that has not fired; the
        // clamp keeps it visibly wrong instead of taking the whole snapshot down with it.
        assertThat(Quantisation.decodePositionAxis(Quantisation.encodePositionAxis(10_000f)))
                .isCloseTo(NetConstants.POSITION_RANGE_M, within(0.02f));
        assertThat(Quantisation.decodePositionAxis(Quantisation.encodePositionAxis(-10_000f)))
                .isCloseTo(-NetConstants.POSITION_RANGE_M, within(0.02f));
    }

    @Test
    void quantisation_isIdempotentAcrossASecondRoundTrip() {
        // The property every empty delta depends on: a value that has been on the wire once encodes
        // to the same bits for ever after, so a parked car costs nothing.
        for (float metres : new float[] {0f, 3.3333f, -12.7f, 250.125f}) {
            int once = Quantisation.encodePositionAxis(metres);
            int twice = Quantisation.encodePositionAxis(Quantisation.decodePositionAxis(once));
            assertThat(twice).as("%f m", metres).isEqualTo(once);
        }
        for (float mps : new float[] {0f, 1f, -33.3f, 59.9f}) {
            int once = Quantisation.encodeLinearVelocityAxis(mps);
            int twice = Quantisation.encodeLinearVelocityAxis(Quantisation.decodeLinearVelocityAxis(once));
            assertThat(twice).isEqualTo(once);
        }
    }

    @Test
    void rotation_survivesSmallestThreePackingAsTheSameOrientation() {
        Quaternion[] orientations = {
            new Quaternion().idt(),
            new Quaternion(new com.badlogic.gdx.math.Vector3(0f, 1f, 0f), 90f),
            new Quaternion(new com.badlogic.gdx.math.Vector3(1f, 0f, 0f), -37.5f),
            new Quaternion(new com.badlogic.gdx.math.Vector3(0.3f, 0.5f, 0.81f).nor(), 175f),
        };
        Quaternion decoded = new Quaternion();
        for (Quaternion orientation : orientations) {
            Quantisation.unpackRotation(Quantisation.packRotation(orientation), decoded);
            // q and -q are the same rotation, which is exactly what the technique exploits, so the
            // comparison has to be on the angle between them rather than on the components.
            float dot = Math.abs(orientation.x * decoded.x
                    + orientation.y * decoded.y
                    + orientation.z * decoded.z
                    + orientation.w * decoded.w);
            float angleRad = (float) (2.0 * Math.acos(Math.min(1f, dot)));
            assertThat(angleRad).as("%s", orientation).isLessThan(0.005f);
        }
    }

    @Test
    void rotation_fitsInThirtyTwoBits() {
        assertThat(Quantisation.rotationBits()).isEqualTo(32);
    }

    @Test
    void messageTypes_haveUniqueStableWireIds() {
        // Append-only, like the component catalogue: a renumbering would make two builds that both
        // believe they agree disagree about what arrived.
        Set<Integer> seen = new HashSet<>();
        for (MessageType type : MessageType.values()) {
            assertThat(seen.add(type.wireId())).as("%s", type).isTrue();
            assertThat(MessageType.byWireId(type.wireId())).isEqualTo(type);
        }
        assertThat(MessageType.byWireId(200)).isNull();
    }

    @Test
    void replicatedComponents_takeTheirWireIdsFromTheComponentCatalogue() {
        for (ReplicatedComponent component : ReplicatedComponent.values()) {
            assertThat(dev.syndicate.core.component.ComponentCatalogue.TYPES.get(component.wireTypeId()))
                    .as("%s", component)
                    .isEqualTo(component.componentType());
            assertThat(ReplicatedComponent.byWireTypeId(component.wireTypeId())).isEqualTo(component);
        }
    }

    @Test
    void contentHash_differsWhenTheComponentListDiffers() {
        // D10-R11: the hash covers the component numbering, so two builds that disagree about it are
        // refused at the handshake rather than desynchronising unexplainably (D10-E18).
        long withCatalogue = ContentHash.of(new byte[] {1, 2, 3});
        long withOneFewer = ContentHash.of(
                new byte[] {1, 2, 3},
                dev.syndicate.core.component.ComponentCatalogue.TYPES.subList(
                        0, dev.syndicate.core.component.ComponentCatalogue.TYPES.size() - 1));
        assertThat(withCatalogue).isNotEqualTo(withOneFewer);
        assertThat(ContentHash.of(new byte[] {1, 2, 3})).isEqualTo(withCatalogue);
        assertThat(ContentHash.of(new byte[] {1, 2, 4})).isNotEqualTo(withCatalogue);
    }
}
