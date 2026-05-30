package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.util.Utils;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

public class GuangYaPan extends Storage {
    public GuangYaPan(DriverAccount account) {
        super(account, "GuangYaPan");
        Map<String, Object> addition = readAddition(account.getAddition());
        String accessToken = StringUtils.defaultIfBlank(account.getToken(), text(addition.get("access_token")));
        addAddition("root_folder_id", StringUtils.defaultIfBlank(account.getFolder(), "0"));
        addAddition("access_token", StringUtils.trimToEmpty(accessToken));
        addAddition("refresh_token", text(addition.get("refresh_token")));
        String deviceId = text(addition.get("device_id"));
        if (StringUtils.isNotBlank(deviceId)) {
            addAddition("device_id", deviceId);
        }
        addAddition("page_size", intValue(addition.get("page_size"), 100));
        addAddition("order_by", intValue(addition.get("order_by"), 3));
        addAddition("sort_type", intValue(addition.get("sort_type"), 1));
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

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.isNumeric(text)) {
            return Integer.parseInt(text);
        }
        return fallback;
    }
}
