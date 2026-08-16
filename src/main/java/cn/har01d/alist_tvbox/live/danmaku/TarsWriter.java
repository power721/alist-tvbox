package cn.har01d.alist_tvbox.live.danmaku;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 虎牙弹幕 Tars 协议编码器(移植 pure_live TarsOutputStream,仅实现进房包用到的类型)。
 */
final class TarsWriter {
    private static final int TYPE_BYTE = 0;
    private static final int TYPE_SHORT = 1;
    private static final int TYPE_INT = 2;
    private static final int TYPE_LONG = 3;
    private static final int TYPE_STRING1 = 6;
    private static final int TYPE_STRING4 = 7;
    private static final int TYPE_LIST = 9;
    private static final int TYPE_STRUCT_BEGIN = 10;
    private static final int TYPE_STRUCT_END = 11;
    private static final int TYPE_ZERO_TAG = 12;
    private static final int TYPE_SIMPLE_LIST = 13;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private void writeHead(int type, int tag) {
        if (tag < 15) {
            out.write((tag << 4) | type);
        } else if (tag < 256) {
            out.write((15 << 4) | type);
            out.write(tag);
        } else {
            throw new IllegalArgumentException("tars tag too large: " + tag);
        }
    }

    void write(long n, int tag) {
        if (n == 0) {
            writeHead(TYPE_ZERO_TAG, tag);
        } else if (n >= -128 && n <= 127) {
            writeHead(TYPE_BYTE, tag);
            out.write((int) n);
        } else if (n >= -32768 && n <= 32767) {
            writeHead(TYPE_SHORT, tag);
            writeInt((int) n, 2);
        } else if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) {
            writeHead(TYPE_INT, tag);
            writeInt((int) n, 4);
        } else {
            writeHead(TYPE_LONG, tag);
            writeInt(n, 8);
        }
    }

    void write(boolean b, int tag) {
        write(b ? 1 : 0, tag);
    }

    void write(String s, int tag) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 255) {
            writeHead(TYPE_STRING4, tag);
            writeInt(bytes.length, 4);
        } else {
            writeHead(TYPE_STRING1, tag);
            out.write(bytes.length);
        }
        out.writeBytes(bytes);
    }

    void write(byte[] bytes, int tag) {
        writeHead(TYPE_SIMPLE_LIST, tag);
        writeHead(TYPE_BYTE, 0);
        write(bytes.length, 0);
        out.writeBytes(bytes);
    }

    private void writeInt(long n, int len) {
        for (int i = len - 1; i >= 0; i--) {
            out.write((int) ((n >> (i * 8)) & 0xFF));
        }
    }

    /** 写字符串列表:LIST 头 + tag0 长度 + 逐个 tag0 字符串 */
    void write(List<String> items, int tag) {
        writeHead(TYPE_LIST, tag);
        write(items.size(), 0);
        for (String item : items) {
            write(item, 0);
        }
    }

    /** 写嵌套结构体:STRUCT_BEGIN + payload + STRUCT_END */
    void writeStruct(byte[] payload, int tag) {
        writeHead(TYPE_STRUCT_BEGIN, tag);
        out.writeBytes(payload);
        writeHead(TYPE_STRUCT_END, 0);
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }
}
