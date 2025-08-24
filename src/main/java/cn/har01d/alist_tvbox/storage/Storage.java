package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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

    public Storage(Site site) {
        this.id = 8000 + site.getId();
        this.driver = site.getVersion() == 4 ? "OpenList" : "AList V" + site.getVersion();
        this.path = "/\uD83C\uDF8E我的套娃/" + site.getName();
    }

    public Storage(PikPakAccount account) {
        this.id = 4500 + account.getId();
        this.driver = "PikPak";
        this.path = "/\uD83C\uDD7F️我的PikPak/" + account.getNickname();
    }

    public Storage(Account account, String type) {
        this.id = 4600 + (account.getId() - 1) * 2 + ("backup".equals(type) ? 1 : 0);
        this.driver = "AliyundriveOpen";
        String name = account.getNickname();
        if (StringUtils.isBlank(name)) {
            name = String.valueOf(account.getId());
        }
        this.path = String.format("/\uD83D\uDCC0我的阿里云盘/%s/%s", name, "backup".equals(type) ? "备份盘" : "资源盘");
    }

    public Storage(DriverAccount account, String driver) {
        this.id = 4000 + account.getId();
        this.driver = driver;
        this.path = getMountPath(account);
    }

    public Storage(Share share, String driver) {
        this.id = share.getId();
        this.driver = driver;
        this.path = getMountPath(share);
        if (share.getTime() != null) {
            this.time = share.getTime();
        }
    }

    public Storage(int id, String driver, String path) {
        this.id = id;
        this.driver = driver;
        this.path = path;
    }

    public void addAddition(String key, Object value) {
        map.put(key, value);
    }

    public void buildAddition() {
        addition = Utils.toJsonString(map);
    }

    public void buildAddition(DriverAccount account) {
        if (StringUtils.isNotBlank(account.getAddition())) {
            map.putAll(Utils.readJson(account.getAddition()));
        }
        addition = Utils.toJsonString(map);
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
        } else if (share.getType() == 10) {
            return "/我的百度分享/" + path;
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
        } else if (account.getType() == DriverType.BAIDU) {
            return "/我的百度网盘/" + account.getName();
        }
        return "/网盘" + account.getName();
        // cn.har01d.alist_tvbox.service.TvBoxService.addMyFavorite
    }

}
