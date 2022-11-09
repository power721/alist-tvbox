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

    public String generate(String site, String path) {
        String header = "#name \n" +
                "#type \n" +
                "#actor \n" +
                "#director \n" +
                "#content \n" +
                "#lang \n" +
                "#area \n" +
                "#year \n\n";
        return header + generate(site, path, "播放列表", "");
    }

    private String generate(String site, String path, String name, String parent) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(",#genre#\n");

        List<String> files = new ArrayList<>();
        List<String> folders = new ArrayList<>();
        for (FsInfo fsInfo : aListService.listFiles(site, path)) {
            if (fsInfo.getType() != 1) {
                files.add(fsInfo.getName());
            } else {
                folders.add(fsInfo.getName());
            }
        }

        for (String file : files) {
            if (!isMediaFormat(file)) {
                continue;
            }
            sb.append(getName(file))
                    .append(",")
                    .append(parent)
                    .append(parent.isEmpty() ? "" : "/")
                    .append(file)
                    .append("\n");
        }
        sb.append("\n");

        for (String file : folders) {
            sb.append(generate(site, path + "/" + file, file, file));
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
