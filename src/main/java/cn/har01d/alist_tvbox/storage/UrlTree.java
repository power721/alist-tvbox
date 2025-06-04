package cn.har01d.alist_tvbox.storage;

public class UrlTree extends Storage {
    public UrlTree(int id, String path, String content) {
        super(id, "UrlTree", path);
        addAddition("url_structure", content);
        buildAddition();
    }
}
