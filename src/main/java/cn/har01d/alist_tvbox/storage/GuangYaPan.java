package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.util.Utils;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

public class GuangYaPan extends Storage {
    public GuangYaPan(DriverAccount account) {
        super(account, "GuangYaPan");
        setCustomCachePolicies("/alist-tvbox-offline:0");
        Map<String, Object> addition = readAddition(account.getAddition());
        addAddition("root_folder_id", StringUtils.defaultIfBlank(account.getFolder(), "0"));
        addAddition("refresh_token", text(addition.get("refresh_token")));
        String deviceId = text(addition.get("device_id"));
        if (StringUtils.isNotBlank(deviceId)) {
            addAddition("device_id", deviceId);
        }
        buildAddition();
    }

    private static Map<String, Object> readAddition(String addition) {
        if (StringUtils.isBlank(addition)) {
            return Map.of();
        }
        return Utils.readJson(addition);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
