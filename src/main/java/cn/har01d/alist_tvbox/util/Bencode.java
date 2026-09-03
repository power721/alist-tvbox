package cn.har01d.alist_tvbox.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bencode 解码器(仅解码,磁力种子元数据预筛用)。
 * <p>
 * 格式:{@code i<整数>e} / {@code <长度>:<字节>} / {@code l<元素...>e} / {@code d<键><值>...e}。
 * 值里的字符串保持原始 {@code byte[]}(文件名按 UTF-8 取用,不在解码层假设编码);
 * 字典键转 String(bencode 键几乎全 ASCII,且 byte[] 作 Map 键是引用相等语义无法查找),
 * 用 {@link LinkedHashMap} 保持种子文件内的原始顺序。
 * 畸形输入抛 {@link IllegalArgumentException},调用方按"解析失败"降级。
 */
public final class Bencode {

    private Bencode() {
    }

    public static Object decode(byte[] data) {
        Decoder decoder = new Decoder(data);
        Object value = decoder.readValue();
        if (decoder.pos < data.length) {
            throw new IllegalArgumentException("trailing bytes after bencode value");
        }
        return value;
    }

    /** 解码出的字符串值转 UTF-8;非 byte[] 返回 null。 */
    public static String asString(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    /** 解码出的字典;非字典返回 null。键为 String。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asDict(Object value) {
        return value instanceof Map<?, ?> dict ? (Map<String, Object>) dict : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    private static final class Decoder {
        private final byte[] data;
        private int pos;

        Decoder(byte[] data) {
            this.data = data;
        }

        Object readValue() {
            if (pos >= data.length) {
                throw new IllegalArgumentException("unexpected end of bencode data");
            }
            char c = (char) data[pos];
            return switch (c) {
                case 'i' -> readInteger();
                case 'l' -> readList();
                case 'd' -> readDict();
                default -> {
                    if (c < '0' || c > '9') {
                        throw new IllegalArgumentException("invalid bencode token at " + pos);
                    }
                    yield readString();
                }
            };
        }

        private Long readInteger() {
            pos++; // 'i'
            int end = indexOf('e');
            if (end < 0) {
                throw new IllegalArgumentException("unterminated integer");
            }
            long value = Long.parseLong(new String(data, pos, end - pos, StandardCharsets.UTF_8));
            pos = end + 1;
            return value;
        }

        private byte[] readString() {
            int colon = indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("invalid string length prefix");
            }
            int length = Integer.parseInt(new String(data, pos, colon - pos, StandardCharsets.UTF_8));
            if (length < 0 || colon + 1 + length > data.length) {
                throw new IllegalArgumentException("string length out of bounds");
            }
            byte[] value = Arrays.copyOfRange(data, colon + 1, colon + 1 + length);
            pos = colon + 1 + length;
            return value;
        }

        private List<Object> readList() {
            pos++; // 'l'
            List<Object> list = new ArrayList<>();
            while (peek() != 'e') {
                list.add(readValue());
            }
            pos++; // 'e'
            return list;
        }

        private Map<String, Object> readDict() {
            pos++; // 'd'
            Map<String, Object> dict = new LinkedHashMap<>();
            while (peek() != 'e') {
                Object key = readValue();
                if (!(key instanceof byte[] keyBytes)) {
                    throw new IllegalArgumentException("dict key must be a string");
                }
                dict.put(new String(keyBytes, StandardCharsets.UTF_8), readValue());
            }
            pos++; // 'e'
            return dict;
        }

        private char peek() {
            if (pos >= data.length) {
                throw new IllegalArgumentException("unexpected end of bencode data");
            }
            return (char) data[pos];
        }

        private int indexOf(char c) {
            for (int i = pos; i < data.length; i++) {
                if (data[i] == c) {
                    return i;
                }
            }
            return -1;
        }
    }
}
