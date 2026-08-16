package cn.har01d.alist_tvbox.live.danmaku;

import java.nio.charset.StandardCharsets;

/**
 * 虎牙弹幕 Tars 协议只读解码器(移植 pure_live TarsInputStream,仅实现弹幕消息用到的类型)。
 * Head 字节:type = b &amp; 0x0F,tag = (b &gt;&gt; 4) &amp; 0x0F;tag == 15 时后跟 1 字节真实 tag。整数大端。
 */
final class TarsReader {
    private static final int TYPE_BYTE = 0;
    private static final int TYPE_SHORT = 1;
    private static final int TYPE_INT = 2;
    private static final int TYPE_LONG = 3;
    private static final int TYPE_FLOAT = 4;
    private static final int TYPE_DOUBLE = 5;
    private static final int TYPE_STRING1 = 6;
    private static final int TYPE_STRING4 = 7;
    private static final int TYPE_MAP = 8;
    private static final int TYPE_LIST = 9;
    private static final int TYPE_STRUCT_BEGIN = 10;
    private static final int TYPE_STRUCT_END = 11;
    private static final int TYPE_ZERO_TAG = 12;
    private static final int TYPE_SIMPLE_LIST = 13;

    private final byte[] data;
    private int pos;

    TarsReader(byte[] data) {
        this(data, 0);
    }

    TarsReader(byte[] data, int pos) {
        this.data = data;
        this.pos = pos;
    }

    private static final class Head {
        int type;
        int tag;
    }

    private int readHead(Head hd) {
        int b = data[pos++] & 0xFF;
        hd.type = b & 0x0F;
        hd.tag = (b >> 4) & 0x0F;
        if (hd.tag == 15) {
            hd.tag = data[pos++] & 0xFF;
            return 2;
        }
        return 1;
    }

    private boolean skipToTag(int tag) {
        Head hd = new Head();
        while (pos < data.length) {
            int cur = pos;
            readHead(hd);
            if (tag <= hd.tag || hd.type == TYPE_STRUCT_END) {
                pos = cur;
                return tag == hd.tag && hd.type != TYPE_STRUCT_END;
            }
            skipField(hd.type);
        }
        return false;
    }

    private void skipToStructEnd() {
        Head hd = new Head();
        do {
            readHead(hd);
            skipField(hd.type);
        } while (hd.type != TYPE_STRUCT_END && pos < data.length);
    }

    private void skipField(int type) {
        switch (type) {
            case TYPE_BYTE -> pos += 1;
            case TYPE_ZERO_TAG, TYPE_STRUCT_END -> {
            }
            case TYPE_SHORT -> pos += 2;
            case TYPE_INT, TYPE_FLOAT -> pos += 4;
            case TYPE_LONG, TYPE_DOUBLE -> pos += 8;
            case TYPE_STRING1 -> pos += 1 + (data[pos] & 0xFF);
            case TYPE_STRING4 -> pos += readInt4();
            case TYPE_MAP, TYPE_LIST -> {
                int size = readIntTag0();
                for (int i = 0; i < size * (type == TYPE_MAP ? 2 : 1); i++) {
                    Head hd = new Head();
                    readHead(hd);
                    skipField(hd.type);
                }
            }
            case TYPE_SIMPLE_LIST -> {
                Head hd = new Head();
                readHead(hd);
                int size = readIntTag0();
                pos += size;
            }
            case TYPE_STRUCT_BEGIN -> skipToStructEnd();
            default -> throw new IllegalStateException("tars skip unknown type: " + type);
        }
    }

    private int readInt4() {
        int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    private int readInt2() {
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    private long readInt8() {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[pos + i] & 0xFFL);
        }
        pos += 8;
        return v;
    }

    /** 读 tag0 的整型长度字段(Map/List/SimpleList 的 size) */
    private int readIntTag0() {
        Head hd = new Head();
        readHead(hd);
        return switch (hd.type) {
            case TYPE_ZERO_TAG -> 0;
            case TYPE_BYTE -> data[pos++] & 0xFF;
            case TYPE_SHORT -> readInt2();
            case TYPE_INT -> readInt4();
            case TYPE_LONG -> (int) readInt8();
            default -> throw new IllegalStateException("tars length type mismatch: " + hd.type);
        };
    }

    /** 读整型字段(可选,不存在返回 0),按无符号处理 */
    long readInt(int tag) {
        if (!skipToTag(tag)) {
            return 0;
        }
        Head hd = new Head();
        readHead(hd);
        return switch (hd.type) {
            case TYPE_ZERO_TAG -> 0;
            case TYPE_BYTE -> data[pos++] & 0xFF;
            case TYPE_SHORT -> readInt2();
            case TYPE_INT -> readInt4() & 0xFFFFFFFFL;
            case TYPE_LONG -> readInt8();
            default -> throw new IllegalStateException("tars int type mismatch: " + hd.type);
        };
    }

    /** 读字符串字段(可选,不存在返回空串) */
    String readString(int tag) {
        if (!skipToTag(tag)) {
            return "";
        }
        Head hd = new Head();
        readHead(hd);
        int len = switch (hd.type) {
            case TYPE_STRING1 -> data[pos++] & 0xFF;
            case TYPE_STRING4 -> readInt4();
            default -> throw new IllegalStateException("tars string type mismatch: " + hd.type);
        };
        String s = new String(data, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    /** 读 bytes 字段(SimpleList,可选,不存在返回空数组) */
    byte[] readBytes(int tag) {
        if (!skipToTag(tag)) {
            return new byte[0];
        }
        Head hd = new Head();
        readHead(hd);
        if (hd.type != TYPE_SIMPLE_LIST) {
            throw new IllegalStateException("tars bytes type mismatch: " + hd.type);
        }
        Head inner = new Head();
        readHead(inner);
        int size = readIntTag0();
        byte[] bytes = new byte[size];
        System.arraycopy(data, pos, bytes, 0, size);
        pos += size;
        return bytes;
    }

    /**
     * 进入 tag 处的结构体(消费 STRUCT_BEGIN 头),此后在本流上按 tag 顺序读取字段,
     * 字段读取完毕后调用 {@link #endStruct()} 同步到结构体结束(与 pure_live 顺序读语义一致,
     * 不依赖精确的 struct 边界,服务端字段可变时容错更好)。
     */
    boolean enterStruct(int tag) {
        if (!skipToTag(tag)) {
            return false;
        }
        Head hd = new Head();
        readHead(hd);
        return hd.type == TYPE_STRUCT_BEGIN;
    }

    /**
     * 进入 tag 处的 LIST,返回元素个数。随后按序对每个元素调用 {@link #enterStructElement()}
     * 读字段、再调用 {@link #endStruct()} 同步到该元素结尾。
     */
    int enterList(int tag) {
        if (!skipToTag(tag)) {
            return 0;
        }
        Head hd = new Head();
        readHead(hd);
        if (hd.type != TYPE_LIST) {
            return 0;
        }
        return readIntTag0();
    }

    /** 消费列表中一个结构体元素的 STRUCT_BEGIN 头 */
    boolean enterStructElement() {
        if (pos >= data.length) {
            return false;
        }
        Head hd = new Head();
        readHead(hd);
        return hd.type == TYPE_STRUCT_BEGIN;
    }

    /**
     * 同步到结构体结束。虎牙消息字段可变(嵌套 struct/LIST/双字节 tag),
     * 同步失败不应影响已读出的字段,因此吞掉异常尽力而为。
     */
    void endStruct() {
        try {
            skipToStructEnd();
        } catch (Exception ignored) {
        }
    }
}
