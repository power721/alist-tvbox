package cn.har01d.alist_tvbox.storage;

public class UrlTree extends Storage {
    public UrlTree(int id, String path, String content) {
        super(id, "UrlTree", path, "");
        setCacheExpiration(0);
        addAddition("url_structure", content);
        addAddition("head_size", false);
        buildAddition();
    }
}
