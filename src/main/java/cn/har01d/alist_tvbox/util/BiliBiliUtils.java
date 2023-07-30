package cn.har01d.alist_tvbox.util;

import java.util.Base64;

public final class BiliBiliUtils {
    private static final String COOKIE = "11t9VCKbJ5jxU0VTU0RBVEE9NjYzNmJmYjIlMkMxNzA2MjQ2NzUwJTJDYjcyMzIlMkE3MXZrRmtGVlBBZEJZZUNQdkhIRVlIVEFCWmFDMzJBclBISlk5RllmalhJU0w2SXo0Yk1jUnAwTWVkV3lNNEV3b1ZuRFJ2MEFBQVJnQTtiaWxpX2pjdD0yNTllOTMwMmM2ODk4NGQ0ZWMxMGI3YjdiMGY5YmRiZDtEZWRlVXNlcklEPTE3ODQ4MDU3NzA7RGVkZVVzZXJJRF9fY2tNZDU9YThlNWQ4YTg0OGYyZDg4ZTtzaWQ9N2plNG9pb2M=";

    public static String getCookie() {
        return new String(Base64.getDecoder().decode(COOKIE.substring(12).getBytes()));
    }
}
