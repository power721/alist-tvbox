package cn.har01d.alist_tvbox.util;

public final class Utils {
    private static final int KB = 1024;
    private static final int MB = 1024 * KB;
    private static final int GB = 1024 * MB;

    public static String byte2size(long size) {
        String result;
        String unit = "B";
        if (size >= GB) {
            result = String.format("%.2f", size / (double) GB);
            unit = "GB";
        } else if (size >= MB) {
            result = String.format("%.2f", size / (double) MB);
            unit = "MB";
        } else if (size >= KB) {
            result = String.format("%.2f", size / (double) KB);
            unit = "KB";
        } else {
            result = String.format("%d", size);
        }
        if (result.endsWith(".00")) {
            result = result.substring(0, result.length() - 3);
        }
        if (result.endsWith("0") && result.charAt(result.length() - 3) == '.') {
            result = result.substring(0, result.length() - 1);
        }
        return result + " " + unit;
    }

}
