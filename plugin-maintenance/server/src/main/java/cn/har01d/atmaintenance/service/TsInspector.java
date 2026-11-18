package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;

/** A bounded transport/PSI observation, never a codec or playback validator. */
final class TsInspector {
    private static final Set<Integer> MEDIA_TYPES = Set.of(1, 2, 3, 4, 15, 16, 17, 27, 36);

    static ObjectNode inspect(byte[] bytes, boolean prefixOnly, ObjectMapper mapper) throws IOException {
        if (bytes.length < 188 * 3) missing("MEDIA_TS_PACKET_SIGNATURE_INVALID", prefixOnly);
        if (!prefixOnly && bytes.length % 188 != 0) invalid("MEDIA_TS_PACKET_ALIGNMENT_INVALID");
        List<Packet> packets = packets(bytes);
        byte[] pat = table(bytes, packets, 0, 0, prefixOnly);
        if ((pat.length - 12) % 4 != 0) invalid("MEDIA_TS_PAT_INVALID");
        int program = -1, pmtPid = -1;
        for (int cursor = 8; cursor < pat.length - 4; cursor += 4) {
            int number = word(pat, cursor);
            if ((pat[cursor + 2] & 0xe0) != 0xe0) invalid("MEDIA_TS_PAT_INVALID");
            int mappedPid = word(pat, cursor + 2) & 0x1fff;
            if (!usablePid(mappedPid)) invalid("MEDIA_TS_PAT_INVALID");
            if (number == 0) continue;
            if (program >= 0) throw new IllegalArgumentException("MEDIA_TS_MULTIPLE_PROGRAMS_UNSUPPORTED");
            program = number;
            pmtPid = mappedPid;
        }
        if (program < 0) invalid("MEDIA_TS_PAT_INVALID");
        byte[] pmt = table(bytes, packets, pmtPid, 2, prefixOnly);
        if (pmt.length < 16 || word(pmt, 3) != program || (pmt[8] & 0xe0) != 0xe0
                || (pmt[10] & 0xf0) != 0xf0) invalid("MEDIA_TS_PMT_INVALID");
        int pcrPid = word(pmt, 8) & 0x1fff;
        if (!usablePid(pcrPid) && pcrPid != 8191) invalid("MEDIA_TS_PMT_INVALID");
        int end = pmt.length - 4;
        int cursor = 12 + (word(pmt, 10) & 0x0fff);
        descriptors(pmt, 12, cursor, end);
        ObjectNode result = mapper.createObjectNode().put("packetBytes", 188)
                .put("observedPackets", packets.size()).put("prefixOnly", prefixOnly)
                .put("trailingBytesNotInspected", bytes.length % 188)
                .put("programNumber", program).put("pmtPid", pmtPid)
                .put("pcrPid", pcrPid).put("psiCrcChecked", true)
                .put("codecDecoded", false).put("scope", "single_program_psi_and_pes_prefix");
        var streams = result.putArray("streams");
        Set<Integer> pids = new HashSet<>();
        int mediaDeclarations = 0, observedPes = 0;
        while (cursor < end) {
            if (end - cursor < 5 || (pmt[cursor + 1] & 0xe0) != 0xe0
                    || (pmt[cursor + 3] & 0xf0) != 0xf0) invalid("MEDIA_TS_PMT_INVALID");
            int type = pmt[cursor] & 255, pid = word(pmt, cursor + 1) & 0x1fff;
            if (!usablePid(pid) || pid == pmtPid || !pids.add(pid)) invalid("MEDIA_TS_PMT_INVALID");
            int next = cursor + 5 + (word(pmt, cursor + 3) & 0x0fff);
            descriptors(pmt, cursor + 5, next, end);
            boolean media = MEDIA_TYPES.contains(type);
            boolean pes = media && pes(bytes, packets, pid);
            if (media) mediaDeclarations++;
            if (pes) observedPes++;
            streams.addObject().put("pid", pid).put("streamType", type)
                    .put("recognizedMediaType", media).put("pesPrefixObserved", pes);
            cursor = next;
        }
        if (pids.isEmpty()) invalid("MEDIA_TS_PMT_INVALID");
        if (mediaDeclarations == 0) throw new IllegalArgumentException("MEDIA_TS_STREAM_TYPE_UNSUPPORTED");
        if (observedPes == 0) missing("MEDIA_TS_PES_MISSING", prefixOnly);
        return result.put("mediaStreamsWithPesPrefix", observedPes);
    }

    private static List<Packet> packets(byte[] bytes) throws IOException {
        List<Packet> packets = new ArrayList<>();
        for (int offset = 0; offset + 188 <= bytes.length; offset += 188) {
            if ((bytes[offset] & 255) != 0x47 || (bytes[offset + 1] & 0x80) != 0)
                invalid("MEDIA_TS_PACKET_SIGNATURE_INVALID");
            int control = (bytes[offset + 3] >>> 4) & 3;
            if (control == 0) invalid("MEDIA_TS_PACKET_SIGNATURE_INVALID");
            if ((bytes[offset + 3] & 0xc0) != 0)
                throw new IllegalArgumentException("MEDIA_TS_SCRAMBLED_UNSUPPORTED");
            int payload = offset + 4;
            boolean discontinuity = false;
            if ((control & 2) != 0) {
                int length = bytes[payload] & 255;
                if (length > 183 || control == 2 && length != 183 || control == 3 && length > 182)
                    invalid("MEDIA_TS_ADAPTATION_INVALID");
                discontinuity = length > 0 && (bytes[payload + 1] & 0x80) != 0;
                payload += length + 1;
            }
            packets.add(new Packet(offset, ((bytes[offset + 1] & 31) << 8) | (bytes[offset + 2] & 255),
                    (control & 1) == 0 ? offset + 188 : payload,
                    (bytes[offset + 1] & 0x40) != 0, bytes[offset + 3] & 15, discontinuity));
        }
        return packets;
    }

    private static byte[] table(byte[] bytes, List<Packet> packets, int pid, int tableId, boolean prefixOnly)
            throws IOException {
        Sections sections = new Sections();
        for (Packet packet : packets) {
            if (packet.pid() == pid) sections.accept(bytes, packet);
        }
        byte[] current = null;
        for (byte[] section : sections.complete) {
            if (section.length < 12 || (section[0] & 255) != tableId
                    || (section[1] & 0xf0) != 0xb0 || (section[5] & 0xc0) != 0xc0)
                invalid("MEDIA_TS_PSI_INVALID");
            if (crc(section) != 0) invalid("MEDIA_TS_PSI_CRC_INVALID");
            if ((section[5] & 1) == 0) continue;
            if (section[6] != 0 || section[7] != 0)
                throw new IllegalArgumentException("MEDIA_TS_MULTISECTION_UNSUPPORTED");
            if (current != null && !Arrays.equals(current, section))
                throw new IllegalArgumentException("MEDIA_TS_TABLE_CHANGE_UNSUPPORTED");
            current = section;
        }
        if (sections.retained > 0) missing("MEDIA_TS_PSI_INCOMPLETE", prefixOnly);
        if (current == null) missing(tableId == 0 ? "MEDIA_TS_PAT_MISSING" : "MEDIA_TS_PMT_MISSING", prefixOnly);
        return current;
    }

    private static boolean pes(byte[] bytes, List<Packet> packets, int pid) throws IOException {
        byte[] header = new byte[6];
        int retained = 0, previous = -1;
        boolean collecting = false;
        for (Packet packet : packets) {
            if (packet.pid() != pid) continue;
            if (packet.discontinuity()) collecting = false;
            if (packet.payload() == packet.offset() + 188) continue;
            if (packet.start()) {
                retained = 0;
                collecting = true;
            } else if (previous >= 0 && packet.counter() != ((previous + 1) & 15)) collecting = false;
            previous = packet.counter();
            if (!collecting) continue;
            int count = Math.min(6 - retained, packet.offset() + 188 - packet.payload());
            System.arraycopy(bytes, packet.payload(), header, retained, count);
            retained += count;
            if (retained == 6) {
                int streamId = header[3] & 255;
                if (header[0] != 0 || header[1] != 0 || header[2] != 1
                        || !(streamId >= 0xc0 && streamId <= 0xef || streamId == 0xbd))
                    invalid("MEDIA_TS_PES_INVALID");
                return true;
            }
        }
        return false;
    }

    private static void descriptors(byte[] section, int start, int end, int limit) throws IOException {
        if (end > limit) invalid("MEDIA_TS_PMT_INVALID");
        for (int offset = start; offset < end;) {
            if (end - offset < 2 || offset + 2 + (section[offset + 1] & 255) > end)
                invalid("MEDIA_TS_PMT_INVALID");
            offset += 2 + (section[offset + 1] & 255);
        }
    }

    private static int crc(byte[] bytes) {
        int value = -1;
        for (byte item : bytes) {
            value ^= (item & 255) << 24;
            for (int bit = 0; bit < 8; bit++)
                value = (value << 1) ^ (value < 0 ? 0x04c11db7 : 0);
        }
        return value;
    }

    private static int word(byte[] bytes, int offset) { return ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255); }
    private static boolean usablePid(int pid) { return pid >= 16 && pid < 8191; }
    private static void invalid(String code) throws IOException { throw new IOException(code); }
    private static void missing(String code, boolean prefixOnly) throws IOException {
        throw new IOException(prefixOnly ? "MEDIA_TS_PREFIX_INCOMPLETE" : code);
    }

    private record Packet(int offset, int pid, int payload, boolean start, int counter, boolean discontinuity) { }

    /** Reassembles at most a 1024-byte PAT/PMT section, including pointer and CC boundaries. */
    private static final class Sections {
        private final List<byte[]> complete = new ArrayList<>();
        private final byte[] pending = new byte[1024];
        private int retained, expected, previousCounter = -1, previousOffset = -1;

        void accept(byte[] bytes, Packet packet) throws IOException {
            if (packet.discontinuity()) { retained = 0; expected = 0; previousCounter = -1; }
            int cursor = packet.payload(), end = packet.offset() + 188;
            if (cursor == end) return;
            if (previousCounter >= 0) {
                if (packet.counter() == previousCounter && Arrays.equals(
                        bytes, previousOffset, previousOffset + 188, bytes, packet.offset(), end)) return;
                if (packet.counter() != ((previousCounter + 1) & 15)) invalid("MEDIA_TS_PSI_CONTINUITY_INVALID");
            }
            previousCounter = packet.counter();
            previousOffset = packet.offset();
            if (packet.start()) {
                int pointer = bytes[cursor++] & 255;
                if (pointer >= end - cursor) invalid("MEDIA_TS_PSI_POINTER_INVALID");
                if (retained > 0) {
                    int finished = append(bytes, cursor, cursor + pointer);
                    if (retained != 0) invalid("MEDIA_TS_PSI_INVALID");
                    stuffing(bytes, finished, cursor + pointer);
                }
                cursor += pointer;
                if ((bytes[cursor] & 255) == 255) invalid("MEDIA_TS_PSI_POINTER_INVALID");
            } else {
                if (retained == 0) return;
                cursor = append(bytes, cursor, end);
                if (retained == 0) stuffing(bytes, cursor, end);
                return;
            }
            while (cursor < end) {
                if (retained == 0 && (bytes[cursor] & 255) == 255) { stuffing(bytes, cursor, end); return; }
                cursor = append(bytes, cursor, end);
            }
        }

        private int append(byte[] bytes, int cursor, int end) throws IOException {
            while (cursor < end) {
                pending[retained++] = bytes[cursor++];
                if (retained == 3) {
                    int length = word(pending, 1) & 0x0fff;
                    if (length < 9 || length > 1021) invalid("MEDIA_TS_PSI_INVALID");
                    expected = 3 + length;
                }
                if (expected > 0 && retained == expected) {
                    complete.add(Arrays.copyOf(pending, retained));
                    retained = 0;
                    expected = 0;
                    break;
                }
            }
            return cursor;
        }

        private void stuffing(byte[] bytes, int start, int end) throws IOException {
            for (int index = start; index < end; index++)
                if ((bytes[index] & 255) != 255) invalid("MEDIA_TS_PSI_INVALID");
        }
    }
}
