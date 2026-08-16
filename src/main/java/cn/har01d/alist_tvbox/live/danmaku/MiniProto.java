package cn.har01d.alist_tvbox.live.danmaku;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 极简 protobuf wire-format 读写器(仅 varint / length-delimited / bool),用于抖音弹幕消息解析,
 * 避免引入 protobuf 依赖。用法:
 * <pre>
 * ProtoReader r = new ProtoReader(bytes);
 * while (r.nextField()) {
 *     switch (r.tag()) {
 *         case 3 -> content = r.readString();
 *         case 2 -> user = new ProtoReader(r.readBytes());
 *         default -> r.skip();
 *     }
 * }
 * </pre>
 */
final class MiniProto {

    private MiniProto() {
    }

    static final class ProtoReader {
        private final byte[] data;
        private final int limit;
        private int pos;
        private int tag;

        ProtoReader(byte[] data) {
            this(data, 0, data.length);
        }

        ProtoReader(byte[] data, int offset, int limit) {
            this.data = data;
            this.pos = offset;
            this.limit = limit;
        }

        /** 移动到下一个字段头,越界返回 false */
        boolean nextField() {
            if (pos >= limit) {
                return false;
            }
            tag = (int) readVarint();
            return true;
        }

        int tag() {
            return tag >>> 3;
        }

        int wireType() {
            return tag & 0x07;
        }

        long readVarint() {
            long v = 0;
            int shift = 0;
            while (true) {
                byte b = data[pos++];
                v |= (b & 0x7FL) << shift;
                if ((b & 0x80) == 0) {
                    return v;
                }
                shift += 7;
                if (shift >= 64) {
                    throw new IllegalStateException("varint too long");
                }
            }
        }

        int readFixed32() {
            int v = ((data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8) | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24));
            pos += 4;
            return v;
        }

        byte[] readBytes() {
            int len = (int) readVarint();
            byte[] bytes = new byte[len];
            System.arraycopy(data, pos, bytes, 0, len);
            pos += len;
            return bytes;
        }

        String readString() {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        boolean readBool() {
            return readVarint() != 0;
        }

        /** 跳过当前字段的值 */
        void skip() {
            switch (wireType()) {
                case 0 -> readVarint();
                case 1 -> pos += 8;
                // 不能写成 pos += readVarint():复合赋值先取 pos 旧值,再求右侧表达式,
                // 而 readVarint() 自身会推进 pos,结果长度前缀那几字节被少跳,后续解析全部错位
                case 2 -> {
                    int length = (int) readVarint();
                    pos += length;
                }
                case 5 -> pos += 4;
                default -> throw new IllegalStateException("unknown wire type: " + wireType());
            }
        }
    }

    static final class ProtoWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void writeString(int tag, String value) {
            writeBytes(tag, value.getBytes(StandardCharsets.UTF_8));
        }

        void writeBytes(int tag, byte[] value) {
            writeVarint((tag << 3) | 2);
            writeVarint(value.length);
            out.writeBytes(value);
        }

        void writeVarintField(int tag, long value) {
            writeVarint(tag << 3);
            writeVarint(value);
        }

        private void writeVarint(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
