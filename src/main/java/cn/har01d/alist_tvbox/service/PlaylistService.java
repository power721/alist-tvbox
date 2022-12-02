package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.model.FsInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlaylistService {
    private final AListService aListService;
    private final AppProperties appProperties;

    public PlaylistService(AListService aListService, AppProperties appProperties) {
        this.aListService = aListService;
        this.appProperties = appProperties;
    }

    public String generate(String site, String path, boolean includeSub) {
        String header = "#name \n" +
                "#type \n" +
                "#actor \n" +
                "#director \n" +
                "#content \n" +
                "#lang \n" +
                "#area \n" +
                "#year \n\n";
        return header + generate(site, path, "播放列表", "", includeSub);
    }

    private String generate(String site, String path, String name, String parent, boolean includeSub) {
        StringBuilder sb = new StringBuilder();

        List<String> files = new ArrayList<>();
        List<String> folders = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (FsInfo fsInfo : aListService.listFiles(site, path, 1, 0).getFiles()) {
            if (fsInfo.getType() != 1) {
                files.add(fsInfo.getName());
            } else if (includeSub) {
                folders.add(fsInfo.getName());
            }
        }

        for (String file : files) {
            if (!isMediaFormat(file)) {
                continue;
            }
            lines.add(getName(file) + "," + parent + (parent.isEmpty() ? "" : "/") + file + "\n");
        }

        if (lines.size() > 1) {
            sb.append(name).append(",#genre#\n");
            lines.forEach(sb::append);
            sb.append("\n");
        }

        for (String file : folders) {
            sb.append(generate(site, path + "/" + file, file, parent + (parent.isEmpty() ? "" : "/") + file, true));
        }

        return sb.toString();
    }

    private String getName(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            return name.substring(0, index).trim();
        }
        return name;
    }

    private boolean isMediaFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1);
            return appProperties.getFormats().contains(suffix);
        }
        return false;
    }

}
