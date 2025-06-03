package cn.har01d.alist_tvbox.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.har01d.alist_tvbox.entity.Share;
import lombok.Data;

@Data
public class Storage {
    private int id;
    private String driver;
    private String path;
    private String addition = "";
    private String webdavPolicy = "302_redirect";
    private int cacheExpiration = 30;
    private boolean webProxy;
    private boolean disabled;
    private Instant time = Instant.now();
    private Map<String, Object> map = new HashMap<>();

    public Storage() {
    }

    public Storage(int id, String driver, String path, Instant time) {
        this.id = id;
        this.driver = driver;
        this.path = path;
        this.time = time;
    }

    public Storage(int id, String driver, String path, String addition) {
        this.id = id;
        this.driver = driver;
        this.path = path;
        this.addition = addition;
    }

    public void addAddition(String key, Object value) {
        map.put(key, value);
    }

    public void buildAddition() {
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String dim = entry.getValue() instanceof String ? "\"" : "";
            list.add("\"" + entry.getKey() + "\":" + dim + entry.getValue() + dim);
        }
        addition = "{" + String.join(",", list) + "}";
    }

    public static String getMountPath(Share share) {
        String path = share.getPath();
        if (path.startsWith("/")) {
            return path;
        }
        if (share.getType() == null || share.getType() == 0) {
            return "/\uD83C\uDE34我的阿里分享/" + path;
        } else if (share.getType() == 1) {
            return "/\uD83D\uDD78️我的PikPak分享/" + path;
        } else if (share.getType() == 5) {
            return "/我的夸克分享/" + path;
        } else if (share.getType() == 7) {
            return "/我的UC分享/" + path;
        } else if (share.getType() == 8) {
            return "/我的115分享/" + path;
        } else if (share.getType() == 9) {
            return "/我的天翼分享/" + path;
        } else if (share.getType() == 2) {
            return "/我的迅雷分享/" + path;
        } else if (share.getType() == 3) {
            return "/我的123分享/" + path;
        } else if (share.getType() == 6) {
            return "/我的移动分享/" + path;
        }
        return path;
    }

}
