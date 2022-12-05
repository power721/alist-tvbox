package cn.har01d.alist_tvbox.tvbox;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.FileWriter;
import java.util.Set;

@Data
@AllArgsConstructor
public class IndexContext {
    private IndexRequest indexRequest;
    private boolean includeFile;
    private FileWriter writer;
    private FileWriter fullWriter;

    public String getSite() {
        return indexRequest.getSite();
    }

    public int getMaxDepth() {
        return indexRequest.getMaxDepth();
    }

    public Set<String> getExcludes() {
        return indexRequest.getExcludes();
    }
}
