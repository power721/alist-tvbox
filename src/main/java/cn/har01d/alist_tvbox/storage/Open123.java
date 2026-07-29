package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.util.Utils;

import java.util.Map;

public class Open123 extends Storage {
    public Open123(DriverAccount account) {
        super(account, "123 Open");
        // 用户无自有 client_id:经 oauth.litepan.top 扫码授权拿到 access/refresh token;
        // OAuthProxy=true 让 Go 123_open 走 oauth.litepan.top 代理刷新。
        Map<String, Object> add = Utils.readJson(account.getAddition());
        addAddition("AccessToken", account.getToken());
        addAddition("RefreshToken", str(add.get("refresh_token")));
        addAddition("OAuthProxy", true);
        addAddition("OAuthServer", "https://oauth.litepan.top");
        addAddition("root_folder_id", account.getFolder());
        addAddition("use_online_api", false);
        buildAddition();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
