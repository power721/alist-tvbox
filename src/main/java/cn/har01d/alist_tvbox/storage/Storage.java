package cn.har01d.alist_tvbox.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
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

    public Storage(int id, String driver, String path) {
        this.id = id;
        this.driver = driver;
        this.path = path;
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

    public static String getMountPath(DriverAccount account) {
        if (account.getName().startsWith("/")) {
            return account.getName();
        }
        if (account.getType() == DriverType.QUARK) {
            return "/\uD83C\uDF1E我的夸克网盘/" + account.getName();
        } else if (account.getType() == DriverType.UC) {
            return "/\uD83C\uDF1E我的UC网盘/" + account.getName();
        } else if (account.getType() == DriverType.QUARK_TV) {
            return "/我的夸克网盘/" + account.getName();
        } else if (account.getType() == DriverType.UC_TV) {
            return "/我的UC网盘/" + account.getName();
        } else if (account.getType() == DriverType.PAN115) {
            return "/115云盘/" + account.getName();
        } else if (account.getType() == DriverType.OPEN115) {
            return "/115网盘/" + account.getName();
        } else if (account.getType() == DriverType.THUNDER) {
            return "/我的迅雷云盘/" + account.getName();
        } else if (account.getType() == DriverType.CLOUD189) {
            return "/我的天翼云盘/" + account.getName();
        } else if (account.getType() == DriverType.PAN139) {
            return "/我的移动云盘/" + account.getName();
        } else if (account.getType() == DriverType.PAN123) {
            return "/我的123网盘/" + account.getName();
        }
        return "/网盘" + account.getName();
        // cn.har01d.alist_tvbox.service.TvBoxService.addMyFavorite
    }

}
