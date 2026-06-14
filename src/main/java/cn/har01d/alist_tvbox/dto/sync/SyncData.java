package cn.har01d.alist_tvbox.dto.sync;

import cn.har01d.alist_tvbox.entity.*;
import lombok.Data;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class SyncData {
    private String appVersion;  // 应用版本号
    private Map<String, Object> modules = new HashMap<>();

    // 辅助方法：设置各个模块数据
    public void setSites(List<Site> sites) {
        modules.put("sites", sites);
    }

    public void setShares(List<Share> shares) {
        modules.put("shares", shares);
    }

    public void setAccounts(List<Account> accounts) {
        modules.put("accounts", accounts);
    }

    public void setDriverAccounts(List<DriverAccount> driverAccounts) {
        modules.put("driverAccounts", driverAccounts);
    }

    public void setPikpakAccounts(List<PikPakAccount> pikpakAccounts) {
        modules.put("pikpakAccounts", pikpakAccounts);
    }

    public void setSubscriptions(List<Subscription> subscriptions) {
        modules.put("subscriptions", subscriptions);
    }

    public void setPlugins(List<Plugin> plugins) {
        modules.put("plugins", plugins);
    }

    public void setPluginFilters(List<PluginFilter> pluginFilters) {
        modules.put("pluginFilters", pluginFilters);
    }

    public void setSettings(Map<String, String> settings) {
        modules.put("settings", settings);
    }
}
