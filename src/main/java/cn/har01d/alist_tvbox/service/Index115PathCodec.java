package cn.har01d.alist_tvbox.service;

/** Encodes index115 identity into TVBox paths threaded through proxyService.
 *  Root "/" = list shares. "/idx/<sc>:<rc>" = share root (parent "0").
 *  "/idx/<sc>:<rc>/<id>" = id is parentID (browse) or fileID (play). */
public final class Index115PathCodec {
    private static final String PREFIX = "/idx/";

    private Index115PathCodec() {}

    public static String shareRoot(String shareCode, String receiveCode) {
        return PREFIX + shareCode + ":" + receiveCode;
    }

    public static String child(String shareCode, String receiveCode, String id) {
        return PREFIX + shareCode + ":" + receiveCode + "/" + id;
    }

    /** @return null for root/non-index paths; else {shareCode, receiveCode, id} (id null at share root). */
    public static String[] decode(String path) {
        if (path == null || path.isEmpty() || path.equals("/") || !path.startsWith(PREFIX)) {
            return null;
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String head = slash < 0 ? rest : rest.substring(0, slash);
        String id = slash < 0 ? null : rest.substring(slash + 1);
        int colon = head.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return new String[]{head.substring(0, colon), head.substring(colon + 1), id};
    }
}
