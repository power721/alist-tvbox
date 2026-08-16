package cn.har01d.alist_tvbox.live.danmaku;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 抖音直播 WebSocket 的本地 X-Bogus 签名(移植 pure_live xbogus.dart,原参考 stream-rec)。
 */
final class XBogus {
    private static final String XBOGUS_ALPHABET = "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe";
    /** 参考实现(stream-rec)的固定填充字节,不随输入变化 */
    private static final byte[] GARBAGE_BYTES = {0x45, 0x3f};

    private XBogus() {
    }

    public static String generate(String msStub, int counter) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return generate(msStub, counter, random.nextInt(256), random.nextInt(255));
    }

    /** random1/random2 参数化便于单测对拍 */
    static String generate(String msStub, int counter, int random1, int random2) {
        if (msStub.length() != 32) {
            throw new IllegalArgumentException("msStub must be 32-char md5 hex string");
        }
        int header = 0x40 | (random1 & 0x1f);
        byte[] md5Bytes = md5Last2(msStub);
        byte[] payload = {
                (byte) (counter & 0x3f),
                0,
                1,
                0x0e,
                GARBAGE_BYTES[0],
                GARBAGE_BYTES[1],
                md5Bytes[0],
                md5Bytes[1],
                (byte) random2,
                0,
        };
        int checksum = 0;
        for (int i = 0; i < 9; i++) {
            checksum ^= payload[i] & 0xFF;
        }
        payload[9] = (byte) checksum;
        rc4(random2, payload);

        byte[] finalData = new byte[12];
        finalData[0] = (byte) header;
        finalData[1] = (byte) random2;
        System.arraycopy(payload, 0, finalData, 2, 10);
        return encodeBase64(finalData);
    }

    /** md5(hexDecode(msStub)) 的最后两个字节 */
    private static byte[] md5Last2(String hexStr) {
        byte[] bytes = new byte[16];
        for (int i = 0; i < 16; i++) {
            bytes[i] = (byte) Integer.parseInt(hexStr.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] digest = md5(bytes);
        return new byte[]{digest[14], digest[15]};
    }

    private static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String md5Hex(String input) {
        byte[] digest = md5(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(32);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 单字节 key 的 RC4,原地加解密 */
    private static void rc4(int key, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + key) & 0xff;
            int tmp = s[i];
            s[i] = s[j];
            s[j] = tmp;
        }
        int ii = 0;
        j = 0;
        for (int k = 0; k < data.length; k++) {
            ii = (ii + 1) & 0xff;
            j = (j + s[ii]) & 0xff;
            int tmp = s[ii];
            s[ii] = s[j];
            s[j] = tmp;
            data[k] ^= (byte) s[(s[ii] + s[j]) & 0xff];
        }
    }

    /** 标准 base64 字符按同下标映射到 X-Bogus 字符表(输入长度须为 3 的倍数) */
    private static String encodeBase64(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = data[i + 1] & 0xFF;
            int b2 = data[i + 2] & 0xFF;
            out.append(XBOGUS_ALPHABET.charAt((b0 >> 2) & 0x3f));
            out.append(XBOGUS_ALPHABET.charAt(((b0 << 4) | (b1 >> 4)) & 0x3f));
            out.append(XBOGUS_ALPHABET.charAt(((b1 << 2) | (b2 >> 6)) & 0x3f));
            out.append(XBOGUS_ALPHABET.charAt(b2 & 0x3f));
        }
        return out.toString();
    }
}
