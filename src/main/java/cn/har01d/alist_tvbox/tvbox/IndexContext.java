package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

@Data
public class IndexContext {
    public Stats stats = new Stats();
    private final IndexRequest indexRequest;
    private final FileWriter writer;
    private Set<String> set = new HashSet<>();

    public String getSite() {
        return indexRequest.getSite();
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

    public Set<String> getStopWords() {
        return indexRequest.getStopWords();
    }

    public boolean contains(String key) {
        return set.contains(key);
    }

    public void write(String path) throws IOException {
        set.add(path);
        stats.indexed++;
        writer.write(path + "\n");
    }

    @Data
    public static class Stats {
        public int files;
        public int indexed;
        public int errors;
        public int excluded;
    }
}
