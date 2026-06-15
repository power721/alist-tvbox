package cn.har01d.alist_tvbox.domain;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DriveId {
    private static final Map<Integer, String> TYPE_TO_DRIVE = new LinkedHashMap<>();
    private static final Map<String, Integer> DRIVE_TO_TYPE = new LinkedHashMap<>();

    static {
        register(0, "ali");
        register(1, "pikpak");
        register(2, "thunder");
        register(3, "123");
        register(4, "local");
        register(5, "quark");
        register(6, "139");
        register(7, "uc");
        register(8, "115");
        register(9, "189");
        register(10, "baidu");
        register(11, "strm");
        register(12, "duck");
    }

    private DriveId() {
    }

    public static Integer toTypeOrNull(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = StringUtils.lowerCase(value.trim());
        Integer type = DRIVE_TO_TYPE.get(normalized);
        if (type != null) {
            return type;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unknown drive identifier: " + value, e);
        }
    }

    public static int toType(String value) {
        Integer type = toTypeOrNull(value);
        if (type == null) {
            throw new IllegalArgumentException("Drive identifier is blank");
        }
        return type;
    }

    public static String toDrive(int type) {
        return TYPE_TO_DRIVE.getOrDefault(type, String.valueOf(type));
    }

    public static String normalize(String value) {
        return toDrive(toType(value));
    }

    public static boolean isShareTokenName(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        String[] parts = value.split("@", 3);
        if (parts.length < 2) {
            return false;
        }
        try {
            toType(parts[0]);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void register(int type, String drive) {
        TYPE_TO_DRIVE.put(type, drive);
        DRIVE_TO_TYPE.put(drive, type);
        DRIVE_TO_TYPE.put(String.valueOf(type), type);
    }
}
