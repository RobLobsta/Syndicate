/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import java.util.Arrays;

/**
 * Writes values at bit granularity into a growable byte buffer
 * (docs/10_networking_multiplayer.md#D10-S4.4).
 *
 * <p>D10-R8 makes bit packing a requirement rather than an optimisation: a 12-player match with
 * 64-part vehicles is unshippable at byte granularity, and at bit granularity a full-detail vehicle
 * snapshot is about 40 bytes. Everything a snapshot carries is quantised to a field width chosen in
 * D10-S4.3 — 16 bits of position, 10 of a quaternion component, 8 of health — and none of those
 * widths is a whole number of bytes.
 *
 * <p>Bits are written <b>least-significant first within each byte</b>, and {@link BitReader} reads
 * them back in the same order. The choice is arbitrary and it is fixed: the two classes are the
 * only definition of the wire's bit order, and a peer that disagreed would decode plausible
 * nonsense rather than fail.
 *
 * <p>Instances are reusable. {@link #reset()} rewinds without releasing the buffer, because a
 * snapshot is built every 50 ms per peer and a fresh array per build is exactly the steady-state
 * garbage D04-S5.6 forbids.
 */
public final class BitWriter {

    /** Enough for a full snapshot of a dozen vehicles before the first grow. */
    private static final int DEFAULT_CAPACITY_BYTES = 1024;

    private byte[] buffer;
    private int bitPosition;

    public BitWriter() {
        this(DEFAULT_CAPACITY_BYTES);
    }

    public BitWriter(int initialCapacityBytes) {
        if (initialCapacityBytes <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + initialCapacityBytes);
        }
        this.buffer = new byte[initialCapacityBytes];
    }

    /** Rewinds to an empty buffer, keeping the allocation. */
    public void reset() {
        Arrays.fill(buffer, 0, byteLength(), (byte) 0);
        bitPosition = 0;
    }

    /** Bits written so far. */
    public int bitLength() {
        return bitPosition;
    }

    /** Bytes needed to hold what has been written, rounded up. */
    public int byteLength() {
        return (bitPosition + 7) >>> 3;
    }

    /**
     * The backing array. Valid only up to {@link #byteLength()}, and only until the next write —
     * it is the send path's scratch buffer, not a packet the caller may keep.
     */
    public byte[] buffer() {
        return buffer;
    }

    /** A right-sized copy, for a receiver that will outlive the next write. */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, byteLength());
    }

    /**
     * Writes the low {@code bitCount} bits of {@code value}.
     *
     * @param bitCount 1 to 32; a wider field needs {@link #writeLongBits}
     * @throws IllegalArgumentException when {@code value} does not fit, which is always a
     *     quantisation bug upstream — silently truncating it would put a plausible wrong number on
     *     the wire and there would be nothing left to find it by
     */
    public void writeBits(int value, int bitCount) {
        if (bitCount < 1 || bitCount > 32) {
            throw new IllegalArgumentException("bitCount must be in [1,32], got " + bitCount);
        }
        if (bitCount < 32) {
            // 1L, not 1: at bitCount 31 an int shift produces Integer.MIN_VALUE and every legal
            // value then compares as too wide.
            long limit = 1L << bitCount;
            if (value < 0 || Integer.toUnsignedLong(value) >= limit) {
                throw new IllegalArgumentException("value " + value + " does not fit in " + bitCount + " bits");
            }
        }
        writeLongBits(Integer.toUnsignedLong(value), bitCount);
    }

    /** Writes the low {@code bitCount} bits of {@code value}, up to all 64. */
    public void writeLongBits(long value, int bitCount) {
        if (bitCount < 1 || bitCount > 64) {
            throw new IllegalArgumentException("bitCount must be in [1,64], got " + bitCount);
        }
        ensureCapacityBits(bitCount);
        for (int i = 0; i < bitCount; i++) {
            if (((value >>> i) & 1L) != 0L) {
                int index = (bitPosition + i) >>> 3;
                buffer[index] |= (byte) (1 << ((bitPosition + i) & 7));
            }
        }
        bitPosition += bitCount;
    }

    /** One bit. */
    public void writeBoolean(boolean value) {
        writeBits(value ? 1 : 0, 1);
    }

    /** A whole float, unquantised. For fields no encoding in D10-S4.3 covers. */
    public void writeFloat(float value) {
        writeLongBits(Integer.toUnsignedLong(Float.floatToIntBits(value)), 32);
    }

    /** A signed 32-bit integer, whole. */
    public void writeInt(int value) {
        writeLongBits(Integer.toUnsignedLong(value), 32);
    }

    /** A tick number. Ticks are {@code uint32} on the wire (D10-S4.4); a match cannot reach 2^32. */
    public void writeTick(long tick) {
        if (tick < 0L || tick > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("tick " + tick + " is outside the uint32 wire range");
        }
        writeLongBits(tick, 32);
    }

    /** A UTF-8 string, length-prefixed with 8 bits. Longer strings are truncated to 255 bytes. */
    public void writeString(String value) {
        byte[] utf8 = value == null ? new byte[0] : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int length = Math.min(utf8.length, 255);
        writeBits(length, 8);
        for (int i = 0; i < length; i++) {
            writeBits(utf8[i] & 0xFF, 8);
        }
    }

    /** Pads with zero bits to the next byte boundary. */
    public void alignToByte() {
        int remainder = bitPosition & 7;
        if (remainder != 0) {
            bitPosition += 8 - remainder;
            ensureCapacityBits(0);
        }
    }

    private void ensureCapacityBits(int additionalBits) {
        int neededBytes = (bitPosition + additionalBits + 7) >>> 3;
        if (neededBytes > buffer.length) {
            int capacity = buffer.length;
            while (capacity < neededBytes) {
                capacity *= 2;
            }
            buffer = Arrays.copyOf(buffer, capacity);
        }
    }
}
