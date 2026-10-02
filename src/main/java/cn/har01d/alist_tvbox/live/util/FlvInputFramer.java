package cn.har01d.alist_tvbox.live.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * 把连续 FLV 字节流拆成完整包:首个包为 FLV 文件头(headerSize 字节 + 4 字节 PreviousTagSize0),
 * 其后每个包为完整 tag(11 字节 tag 头 + 数据 + 4 字节尾随字段)。以 tag 头声明的 DataSize 定界
 * (部分 FLV 产生端的 PreviousTagSize 不可信但 FFmpeg 接受,尾随字段原样保留)。
 * pure_live FlvInputFramer 同款口径,单线程使用。
 */
public final class FlvInputFramer {
    private static final int MAX_HEADER_SIZE = 65536;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final ArrayDeque<byte[]> packets = new ArrayDeque<>();
    private int expected = 9;
    private int phase = 0;

    /** 喂入一段字节;畸形流(非 FLV 头/头长度越界/初始 tag size 非零)抛 IOException。 */
    public void add(byte[] src, int offset, int length) throws IOException {
        int end = offset + length;
        while (offset < end) {
            int take = Math.min(expected - buffer.size(), end - offset);
            buffer.write(src, offset, take);
            offset += take;
            if (buffer.size() != expected) {
                continue;
            }
            byte[] packet = buffer.toByteArray();
            buffer.reset();
            switch (phase) {
                case 0 -> {
                    if (packet[0] != 0x46 || packet[1] != 0x4c || packet[2] != 0x56 || packet[3] != 1) {
                        throw new IOException("invalid FLV header");
                    }
                    long headerSize = uint32(packet, 5);
                    if (headerSize < 9 || headerSize > MAX_HEADER_SIZE) {
                        throw new IOException("invalid FLV header size " + headerSize);
                    }
                    expected = (int) headerSize + 4;
                    buffer.write(packet, 0, packet.length);
                    phase = 1;
                }
                case 1 -> {
                    if (uint32(packet, packet.length - 4) != 0) {
                        throw new IOException("invalid FLV initial tag size");
                    }
                    expected = 11;
                    phase = 2;
                    packets.add(packet);
                }
                case 2 -> {
                    int dataSize = ((packet[1] & 0xff) << 16) | ((packet[2] & 0xff) << 8) | (packet[3] & 0xff);
                    expected = 11 + dataSize + 4;
                    buffer.write(packet, 0, packet.length);
                    phase = 3;
                }
                default -> {
                    expected = 11;
                    phase = 2;
                    packets.add(packet);
                }
            }
        }
    }

    public boolean hasNext() {
        return !packets.isEmpty();
    }

    public byte[] next() {
        return packets.poll();
    }

    private static long uint32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }
}
