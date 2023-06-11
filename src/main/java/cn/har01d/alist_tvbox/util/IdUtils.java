package cn.har01d.alist_tvbox.util;

import java.util.concurrent.ThreadLocalRandom;

public final class IdUtils {
    private static final String TOKEN62 = "Ok4jShBpcvKY5gTQMVRsEHfGe3nDdb81IJwrqLFP0UC6xilazo2ZWut9yNmA7X";

    public static String generate(int len) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        char[] chars = TOKEN62.toCharArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; ++i) {
            sb.append(chars[random.nextInt(chars.length)]);
        }
        return sb.toString();
    }

}
