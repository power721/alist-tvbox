package cn.har01d.atmaintenance.service;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Synthetic PSI/PES structure only; the PES does not contain decodable H.264. */
final class TsFixtures {
    static final int PROGRAM = 1;
    static final int PMT_PID = 0x100;
    static final int VIDEO_PID = 0x101;

    private TsFixtures() { }

    static byte[] stream() {
        return concat(psiPacket(0, 0, pat(PROGRAM, PMT_PID)),
                psiPacket(PMT_PID, 0, pmt(PROGRAM, new byte[0], elementary(0x1b, VIDEO_PID))),
                packet(VIDEO_PID, true, 0, pesPrefix(0xe0)));
    }

    static byte[] pat(int... programsAndPids) {
        if (programsAndPids.length % 2 != 0) throw new IllegalArgumentException("program/PID pairs");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int index = 0; index < programsAndPids.length; index += 2) {
            word(payload, programsAndPids[index]);
            word(payload, 0xe000 | programsAndPids[index + 1]);
        }
        return section(0, 1, 0, 0, payload.toByteArray());
    }

    static byte[] pmt(int program, byte[] descriptors, byte[]... elementaryStreams) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        word(payload, 0xe000 | VIDEO_PID);
        word(payload, 0xf000 | descriptors.length);
        payload.writeBytes(descriptors);
        for (byte[] elementaryStream : elementaryStreams) payload.writeBytes(elementaryStream);
        return section(2, program, 0, 0, payload.toByteArray());
    }

    static byte[] elementary(int type, int pid, byte... descriptors) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        value.write(type);
        word(value, 0xe000 | pid);
        word(value, 0xf000 | descriptors.length);
        value.writeBytes(descriptors);
        return value.toByteArray();
    }

    static byte[] section(int tableId, int extension, int section, int lastSection, byte[] payload) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        value.write(tableId);
        word(value, 0xb000 | (9 + payload.length));
        word(value, extension);
        value.write(0xc1);
        value.write(section);
        value.write(lastSection);
        value.writeBytes(payload);
        return appendCrc(value.toByteArray());
    }

    static byte[] psiPacket(int pid, int counter, byte[] section) {
        return packet(pid, true, counter, concat(new byte[]{0}, section));
    }

    static byte[] packet(int pid, boolean start, int counter, byte[] payload) {
        if (payload.length < 1 || payload.length > 184) throw new IllegalArgumentException("payload length");
        byte[] packet = new byte[188];
        Arrays.fill(packet, (byte) 0xff);
        packet[0] = 0x47;
        packet[1] = (byte) ((start ? 0x40 : 0) | ((pid >>> 8) & 0x1f));
        packet[2] = (byte) pid;
        int payloadOffset = 4;
        if (payload.length == 184) {
            packet[3] = (byte) (0x10 | (counter & 15));
        } else {
            packet[3] = (byte) (0x30 | (counter & 15));
            int adaptationLength = 183 - payload.length;
            packet[4] = (byte) adaptationLength;
            if (adaptationLength > 0) packet[5] = 0;
            payloadOffset = 5 + adaptationLength;
        }
        System.arraycopy(payload, 0, packet, payloadOffset, payload.length);
        return packet;
    }

    static byte[] nullPacket(int counter) {
        byte[] payload = new byte[184];
        Arrays.fill(payload, (byte) 0xff);
        return packet(0x1fff, false, counter, payload);
    }

    static byte[] pesPrefix(int streamId) {
        return new byte[]{0, 0, 1, (byte) streamId, 0, 0};
    }

    static byte[] appendCrc(byte[] bytes) {
        long crc = crc32Mpeg2(bytes);
        return concat(bytes, new byte[]{(byte) (crc >>> 24), (byte) (crc >>> 16),
                (byte) (crc >>> 8), (byte) crc});
    }

    static byte[] renewCrc(byte[] section) {
        return appendCrc(Arrays.copyOf(section, section.length - 4));
    }

    /** A long accumulator keeps this independent of the inspector's signed-int CRC loop. */
    static long crc32Mpeg2(byte[] bytes) {
        long register = 0xffff_ffffL;
        for (byte value : bytes) {
            register ^= (long) (value & 255) << 24;
            for (int shift = 0; shift < 8; shift++) {
                boolean topBit = (register & 0x8000_0000L) != 0;
                register = (register << 1) & 0xffff_ffffL;
                if (topBit) register ^= 0x04c1_1db7L;
            }
        }
        return register;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        for (byte[] part : parts) value.writeBytes(part);
        return value.toByteArray();
    }

    private static void word(ByteArrayOutputStream target, int value) {
        target.write(value >>> 8);
        target.write(value);
    }
}
