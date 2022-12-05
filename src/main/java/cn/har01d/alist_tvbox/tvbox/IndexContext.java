package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

@Data
public class IndexContext {
    private final IndexRequest indexRequest;
    private final FileWriter writer;
    private final FileWriter fullWriter;
    private boolean includeFile;
    private Set<String> set = new HashSet<>();

    public String getSite() {
        return indexRequest.getSite();
    }

    public boolean isWriteFull() {
        return indexRequest.isWriteFull();
    }

    public boolean isExcludeExternal() {
        return indexRequest.isExcludeExternal();
    }

    public int getMaxDepth() {
        return indexRequest.getMaxDepth();
    }

    public Set<String> getExcludes() {
        return indexRequest.getExcludes();
    }

    public boolean contains(String key) {
        return set.contains(key);
    }

    public void write(String path) throws IOException {
        set.add(path);
        writer.write(path + "\n");
    }

    public void writeFull(String path) throws IOException {
        if (indexRequest.isWriteFull()) {
            set.add(path);
            fullWriter.write(path + "\n");
        }
    }
}
