package cn.har01d.atmaintenance.dto;

/** In-memory declaration only; URI and IV must not be copied into reports. */
public record HlsKey(int declaration, String method, String uri, String iv, boolean supported) {
    public boolean encrypted() { return !method.equals("NONE"); }
}
