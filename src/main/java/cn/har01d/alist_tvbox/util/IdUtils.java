package cn.har01d.alist_tvbox.util;

import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

public final class IdUtils {
    private static final String TOKEN62 = "Ok4jShBpcvKY5gTQMVRsEHfGe3nDdb81IJwrqLFP0UC6xilazo2ZWut9yNmA7X";
    private static final String HASH = "MjI1ODk2Mjg6OTE3MTk5NjEzYmUzNzVmNWIzNTc4NGFlYzQ3MzQ3NDQ=";

    public static String generate(int len) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        char[] chars = TOKEN62.toCharArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; ++i) {
            sb.append(chars[random.nextInt(chars.length)]);
        }
        return sb.toString();
    }

    public static Integer getApiId() {
        String text = new String(Base64.getDecoder().decode(HASH));
        return Integer.parseInt(text.split(":")[0]);
    }

    public static String getApiHash() {
        String text = new String(Base64.getDecoder().decode(HASH));
        return text.split(":")[1];
    }
}
