package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.tvbox.IndexContext;
import cn.har01d.alist_tvbox.tvbox.IndexRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class IndexService {
    private final AListService aListService;
    private final AppProperties appProperties;

    public IndexService(AListService aListService, AppProperties appProperties) {
        this.aListService = aListService;
        this.appProperties = appProperties;
    }

    public void index(IndexRequest indexRequest) throws IOException {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("create file");
        File dir = new File("data/" + indexRequest.getSite());
        dir.mkdirs();
        File file = new File(dir, "index.txt");
        file.createNewFile();

        File fullFile = new File(dir, "index.full.txt");
        fullFile.createNewFile();
        stopWatch.stop();

        try (FileWriter writer = new FileWriter(file, true);
             FileWriter fullWriter = new FileWriter(fullFile, true)) {
            for (String path : indexRequest.getCollection()) {
                stopWatch.start("index " + path);
                IndexContext context = new IndexContext(indexRequest, false, writer, fullWriter);
                index(context, path, 0);
                stopWatch.stop();
            }

            for (String path : indexRequest.getSingle()) {
                stopWatch.start("index " + path);
                IndexContext context = new IndexContext(indexRequest, true, writer, fullWriter);
                index(context, path, 0);
                stopWatch.stop();
            }
        }

        log.info("index done: {}", stopWatch.prettyPrint());
    }

    private void index(IndexContext context, String path, int depth) throws IOException {
        if (context.getMaxDepth() > 0 && depth == context.getMaxDepth()) {
            return;
        }

        FsResponse fsResponse = aListService.listFiles(context.getSite(), path, 1, 0);
        if (fsResponse == null) {
            return;
        }

        List<String> files = new ArrayList<>();
        for (FsInfo fsInfo : fsResponse.getFiles()) {
            if (fsInfo.getType() == 1) {
                String newPath = fixPath(path + "/" + fsInfo.getName());
                if (exclude(context.getExcludes(), newPath)) {
                    continue;
                }

                index(context, newPath, depth + 1);
            } else if (isMediaFormat(fsInfo.getName())) {
                String newPath = fixPath(path + "/" + fsInfo.getName());
                if (exclude(context.getExcludes(), newPath)) {
                    continue;
                }

                files.add(newPath);
            }
        }

        if (files.size() > 0) {
            context.getWriter().write(path + "\n");
            context.getFullWriter().write(path + "\n");
        }

        for (String line : files) {
            if (context.isIncludeFile()) {
                context.getWriter().write(line + "\n");
            }
            context.getFullWriter().write(line + "\n");
        }

        context.getWriter().flush();
        context.getFullWriter().flush();
    }

    private boolean exclude(Set<String> rules, String path) {
        for (String rule : rules) {
            if (rule.startsWith("^") && rule.endsWith("$") && path.equals(rule.substring(1, rule.length() - 1))) {
                return true;
            }
            if (rule.startsWith("^") && path.startsWith(rule.substring(1))) {
                return true;
            }
            if (rule.endsWith("$") && path.endsWith(rule.substring(0, rule.length() - 1))) {
                return true;
            }
            if (path.contains(rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMediaFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1);
            return appProperties.getFormats().contains(suffix);
        }
        return false;
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/");
    }

}
