package cn.har01d.alist_tvbox.storage;

public class Alias extends Storage {
    public Alias(int id, String path, String content) {
        super(id, "Alias", path, "");
        setCacheExpiration(0);
        addAddition("paths", content);
        buildAddition();
    }
}
