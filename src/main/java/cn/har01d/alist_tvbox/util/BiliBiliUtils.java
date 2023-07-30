package cn.har01d.alist_tvbox.util;

public final class BiliBiliUtils {
    static {
        System.loadLibrary("bilibili");
    }

    public static native String getCookie();
}
