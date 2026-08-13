/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import java.nio.charset.StandardCharsets;

/**
 * Reads back what {@link BitWriter} wrote (docs/10_networking_multiplayer.md#D10-S4.4).
 *
 * <p>Every read is bounds-checked against the packet's length, and running off the end throws
 * rather than returning zeroes. A truncated packet is a hostile or corrupt packet, and the receiver
 * that must survive one is the authority: {@code NetworkAuthority} catches the exception, drops the
 * datagram and counts it, which is the only correct response to bytes that do not decode (D10-R26 —
 * nothing a client sends is trusted).
 */
public final class BitReader {

    private static final byte[] EMPTY = new byte[0];

    private byte[] buffer;
    private int offset;
    private int bitLimit;
    private int bitPosition;

    /** An empty reader, to be pointed at a packet with {@link #reset}. */
    public BitReader() {
        this(EMPTY, 0, 0);
    }

    /** Reads the whole array. */
    public BitReader(byte[] buffer) {
        this(buffer, 0, buffer.length);
    }

    /** Reads {@code length} bytes starting at {@code offset}. */
    public BitReader(byte[] buffer, int offset, int length) {
        reset(buffer, offset, length);
    }

    /**
     * Points this reader at another packet, rewinding to its first bit.
     *
     * <p>Exists so a receiver can keep one reader for the life of a connection rather than
     * allocating one per datagram: at 60 Hz per peer that is the steady-state garbage D04-S5.6
     * exists to prevent.
     */
    public BitReader reset(byte[] buffer, int offset, int length) {
        this.buffer = buffer;
        this.offset = offset;
        this.bitLimit = length * 8;
        this.bitPosition = 0;
        return this;
    }

    /** Bits consumed so far. */
    public int bitPosition() {
        return bitPosition;
    }

    /** Bits left before the end of the packet. */
    public int bitsRemaining() {
        return bitLimit - bitPosition;
    }

    /** True while at least one bit is left. Padding to a byte boundary can leave up to seven. */
    public boolean hasMore(int bitCount) {
        return bitsRemaining() >= bitCount;
    }

    /** Reads {@code bitCount} bits as an unsigned int. */
    public int readBits(int bitCount) {
        if (bitCount < 1 || bitCount > 32) {
            throw new IllegalArgumentException("bitCount must be in [1,32], got " + bitCount);
        }
        return (int) readLongBits(bitCount);
    }

    /** Reads up to 64 bits. */
    public long readLongBits(int bitCount) {
        if (bitCount < 1 || bitCount > 64) {
            throw new IllegalArgumentException("bitCount must be in [1,64], got " + bitCount);
        }
        if (bitPosition + bitCount > bitLimit) {
            throw new MalformedPacketException("packet ends after " + bitLimit + " bits; a read of " + bitCount + " at "
                    + bitPosition + " would run past it");
        }
        long value = 0L;
        for (int i = 0; i < bitCount; i++) {
            int absolute = bitPosition + i;
            int index = offset + (absolute >>> 3);
            if (((buffer[index] >>> (absolute & 7)) & 1) != 0) {
                value |= 1L << i;
            }
        }
        bitPosition += bitCount;
        return value;
    }

    public boolean readBoolean() {
        return readBits(1) != 0;
    }

    public float readFloat() {
        return Float.intBitsToFloat((int) readLongBits(32));
    }

    public int readInt() {
        return (int) readLongBits(32);
    }

    /** A {@code uint32} tick, widened to the {@code long} the simulation counts in. */
    public long readTick() {
        return readLongBits(32);
    }

    /** A length-prefixed UTF-8 string. */
    public String readString() {
        int length = readBits(8);
        if (length == 0) {
            return "";
        }
        byte[] utf8 = new byte[length];
        for (int i = 0; i < length; i++) {
            utf8[i] = (byte) readBits(8);
        }
        return new String(utf8, StandardCharsets.UTF_8);
    }

    /** Skips to the next byte boundary, mirroring {@link BitWriter#alignToByte()}. */
    public void alignToByte() {
        int remainder = bitPosition & 7;
        if (remainder != 0) {
            bitPosition += 8 - remainder;
        }
    }

    /** Thrown when a packet does not contain what its own structure claims. */
    public static final class MalformedPacketException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MalformedPacketException(String message) {
            super(message);
        }
    }
}
