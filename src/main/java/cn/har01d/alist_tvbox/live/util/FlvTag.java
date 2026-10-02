package cn.har01d.alist_tvbox.live.util;

/**
 * FLV 完整 tag 包(11 字节 tag 头 + 数据 + 4 字节 PreviousTagSize)的字段读取与改写。
 * 斗鱼租约拼接引擎用(pure_live flv_splice_relay FlvTag 同款口径)。
 */
public final class FlvTag {
    public static final int AUDIO = 8;
    public static final int VIDEO = 9;
    public static final int SCRIPT = 18;

    private FlvTag() {
    }

    public static int type(byte[] tag) {
        return tag[0] & 0x1f;
    }

    /** tag 时间戳(毫秒,含最高字节在 tag[7] 的 FLV 扩展位)。 */
    public static int timestamp(byte[] tag) {
        return ((tag[7] & 0xff) << 24) | ((tag[4] & 0xff) << 16) | ((tag[5] & 0xff) << 8) | (tag[6] & 0xff);
    }

    public static byte[] withTimestamp(byte[] tag, int timestamp) {
        byte[] copy = tag.clone();
        int value = timestamp;
        copy[4] = (byte) ((value >> 16) & 0xff);
        copy[5] = (byte) ((value >> 8) & 0xff);
        copy[6] = (byte) (value & 0xff);
        copy[7] = (byte) ((value >> 24) & 0xff);
        return copy;
    }

    private static boolean enhanced(byte[] tag) {
        return (tag[11] & 0x80) != 0;
    }

    /** AVC/HEVC 解码器配置(传统 FLV 的 AVC sequence header 或 Enhanced FLV 的 SequenceStart)。 */
    public static boolean isVideoConfig(byte[] tag) {
        if (type(tag) != VIDEO || tag.length < 17) {
            return false;
        }
        if (enhanced(tag)) {
            return (tag[11] & 0x0f) == 0;
        }
        int codec = tag[11] & 0x0f;
        return (codec == 7 || codec == 12) && tag[12] == 0;
    }

    public static boolean isKeyframe(byte[] tag) {
        return type(tag) == VIDEO && tag.length > 12 && ((tag[11] >> 4) & 7) == 1 && !isVideoConfig(tag);
    }

    /** AAC AudioSpecificConfig。 */
    public static boolean isAudioConfig(byte[] tag) {
        return type(tag) == AUDIO && tag.length > 16 && ((tag[11] & 0xff) >> 4) == 10 && tag[12] == 0;
    }

    /** 数据载荷逐字节相同(跳过 tag 头与尾随 PreviousTagSize):判定两连接的解码器配置是否变化。 */
    public static boolean samePayload(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 11; i < a.length - 4; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
