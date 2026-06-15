package cn.har01d.alist_tvbox.dto.sync;

import cn.har01d.alist_tvbox.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class SyncData {
    private String appVersion;  // 应用版本号
    private Map<String, Object> modules = new HashMap<>();

    // ObjectMapper 用于类型转换
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    public void setJellyfins(List<Jellyfin> jellyfins) {
        modules.put("jellyfins", jellyfins);
    }

    public void setEmbys(List<Emby> embys) {
        modules.put("embys", embys);
    }

    public void setFenius(List<Feiniu> fenius) {
        modules.put("fenius", fenius);
    }

    // 类型化的 getter 方法，处理 JSON 反序列化后的类型转换
    @SuppressWarnings("unchecked")
    public List<Site> getSites() {
        return convertList(modules.get("sites"), Site.class);
    }

    @SuppressWarnings("unchecked")
    public List<Share> getShares() {
        return convertList(modules.get("shares"), Share.class);
    }

    @SuppressWarnings("unchecked")
    public List<Account> getAccounts() {
        return convertList(modules.get("accounts"), Account.class);
    }

    @SuppressWarnings("unchecked")
    public List<DriverAccount> getDriverAccounts() {
        return convertList(modules.get("driverAccounts"), DriverAccount.class);
    }

    @SuppressWarnings("unchecked")
    public List<PikPakAccount> getPikpakAccounts() {
        return convertList(modules.get("pikpakAccounts"), PikPakAccount.class);
    }

    @SuppressWarnings("unchecked")
    public List<Subscription> getSubscriptions() {
        return convertList(modules.get("subscriptions"), Subscription.class);
    }

    @SuppressWarnings("unchecked")
    public List<Plugin> getPlugins() {
        return convertList(modules.get("plugins"), Plugin.class);
    }

    @SuppressWarnings("unchecked")
    public List<PluginFilter> getPluginFilters() {
        return convertList(modules.get("pluginFilters"), PluginFilter.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getSettings() {
        Object obj = modules.get("settings");
        if (obj == null) {
            return null;
        }
        if (obj instanceof Map) {
            return (Map<String, String>) obj;
        }
        return MAPPER.convertValue(obj, Map.class);
    }

    @SuppressWarnings("unchecked")
    public List<Jellyfin> getJellyfins() {
        return convertList(modules.get("jellyfins"), Jellyfin.class);
    }

    @SuppressWarnings("unchecked")
    public List<Emby> getEmbys() {
        return convertList(modules.get("embys"), Emby.class);
    }

    @SuppressWarnings("unchecked")
    public List<Feiniu> getFenius() {
        return convertList(modules.get("fenius"), Feiniu.class);
    }

    /**
     * 将 Object 转换为指定类型的 List
     * 处理 JSON 反序列化后的 List<LinkedHashMap> 情况
     */
    private <T> List<T> convertList(Object obj, Class<T> elementType) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            if (list.isEmpty()) {
                return (List<T>) list;
            }
            // 检查第一个元素类型
            Object first = list.get(0);
            if (elementType.isInstance(first)) {
                // 已经是正确类型
                return (List<T>) list;
            }
            // 需要转换（JSON 反序列化后是 LinkedHashMap）
            return MAPPER.convertValue(obj,
                MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        }
        return null;
    }
}
