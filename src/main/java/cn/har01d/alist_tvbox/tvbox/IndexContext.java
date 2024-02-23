package cn.har01d.alist_tvbox.tvbox;

import cn.har01d.alist_tvbox.dto.IndexRequest;
import lombok.Data;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

@Data
public class IndexContext {
    public Stats stats = new Stats();
    private final IndexRequest indexRequest;
    private final cn.har01d.alist_tvbox.entity.Site site;
    private final FileWriter writer;
    private final Integer taskId;
    private Set<String> set = new HashSet<>();
    private boolean includeFiles;
    private int maxDepth = 10;

    public String getSiteName() {
        return site.getName();
    }

    public boolean isExcludeExternal() {
        return indexRequest.isExcludeExternal();
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
        if (set.add(path)) {
            stats.indexed++;
            writer.write(path + "\n");
        }
    }

    @Data
    public static class Stats {
        public int files;
        public int indexed;
        public int errors;
        public int excluded;

        @Override
        public String toString() {
            return "Stats{" +
                    "files=" + files +
                    ", indexed=" + indexed +
                    ", errors=" + errors +
                    ", excluded=" + excluded +
                    '}';
        }
    }
}
