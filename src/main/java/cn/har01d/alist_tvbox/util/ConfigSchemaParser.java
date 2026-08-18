package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.model.PluginFilterConfigField;
import cn.har01d.alist_tvbox.model.PluginFilterConfigSchema;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量解析插件自声明的配置结构 (config schema)。
 *
 * <p>过滤器 (PluginFilter) 与 spider 插件 (Plugin) 共用：脚本顶层用常量声明
 * (如 {@code FILTER_CONFIG_SCHEMA} / {@code PLUGIN_CONFIG_SCHEMA = {...}})，或兼容旧的注释式
 * {@code // @config-schema {...}}。只覆盖当前 schema 结构所需字段，避免引入额外 JSON 反序列化依赖。</p>
 */
public final class ConfigSchemaParser {

    // 兼容早期注释式 schema 声明：// @config-schema { ... }
    // 同时识别 secspider 打包输出的明文头：//@config-schema:{ ... }（宿主不解密 payload 时的回退通道）。
    private static final Pattern COMMENT_SCHEMA =
            Pattern.compile("(?s)//\\s*@config-schema\\s*:?\\s*(\\{.*?})\\s*(?:\\R\\s*//\\s*@|\\z)");

    private ConfigSchemaParser() {
    }

    /**
     * 解析脚本内容中自声明的配置结构。
     *
     * @param content    脚本原文 (.py / .txt)
     * @param constNames 可识别的顶层常量名 (如 "FILTER_CONFIG_SCHEMA"、"PLUGIN_CONFIG_SCHEMA")
     * @return 解析出的 schema；未声明或解析失败返回 null
     */
    public static PluginFilterConfigSchema parse(String content, String... constNames) {
        if (StringUtils.isBlank(content)) {
            return null;
        }
        // 先兼容旧的注释式声明，便于平滑迁移。
        Matcher jsonCommentMatcher = COMMENT_SCHEMA.matcher(content);
        if (jsonCommentMatcher.find()) {
            String json = StringUtils.trimToEmpty(jsonCommentMatcher.group(1));
            if (!json.isBlank()) {
                try {
                    return parseSchemaJson(json);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }

        // 再读取脚本顶层常量声明。
        if (constNames != null) {
            for (String name : constNames) {
                if (StringUtils.isBlank(name)) {
                    continue;
                }
                Pattern constPattern = Pattern.compile(
                        "(?s)^\\s*" + Pattern.quote(name) + "\\s*=\\s*(\\{.*?})\\s*(?:\\R\\s*\\w+\\s*=|\\R\\s*class\\s+|\\z)",
                        Pattern.MULTILINE);
                Matcher constMatcher = constPattern.matcher(content);
                if (!constMatcher.find()) {
                    continue;
                }
                String json = StringUtils.trimToEmpty(constMatcher.group(1));
                if (json.isBlank()) {
                    continue;
                }
                try {
                    return parseSchemaJson(json);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static PluginFilterConfigSchema parseSchemaJson(String json) {
        // 这里保持轻量解析：只覆盖当前 schema 结构所需字段，
        // 避免为了一个约定格式引入额外 JSON 反序列化依赖。
        PluginFilterConfigSchema schema = new PluginFilterConfigSchema();
        String normalized = json.replace("\r", "");
        schema.setAllowAdditional(!normalized.contains("\"allowAdditional\": false"));
        schema.setSource(extractJsonString(normalized, "source"));
        schema.setDescription(extractJsonString(normalized, "description"));
        schema.setSingleValueKey(extractJsonString(normalized, "singleValueKey"));
        schema.setExample(extractJsonObject(normalized, "example"));
        String fieldsJson = extractJsonArray(normalized, "fields");
        if (fieldsJson == null) {
            return schema;
        }
        for (String fieldJson : splitTopLevelObjects(fieldsJson)) {
            PluginFilterConfigField field = parseFieldJson(fieldJson);
            if (field != null && StringUtils.isNotBlank(field.getKey())) {
                schema.getFields().add(field);
            }
        }
        return schema;
    }

    public static PluginFilterConfigField parseFieldJson(String fieldJson) {
        PluginFilterConfigField field = new PluginFilterConfigField();
        field.setKey(extractJsonString(fieldJson, "key"));
        field.setLabel(extractJsonString(fieldJson, "label"));
        field.setType(defaultIfBlank(extractJsonString(fieldJson, "type"), "string"));
        field.setRequired(fieldJson.contains("\"required\": true"));
        field.setDescription(extractJsonString(fieldJson, "description"));
        field.setPlaceholder(extractJsonString(fieldJson, "placeholder"));
        String defaultValue = extractJsonValue(fieldJson, "defaultValue");
        if (defaultValue != null) {
            field.setDefaultValue(parseScalarValue(defaultValue));
        }
        field.setAliases(parseStringArray(extractJsonArray(fieldJson, "aliases")));
        field.setItemLabel(extractJsonString(fieldJson, "itemLabel"));
        String childrenJson = extractJsonArray(fieldJson, "children");
        if (childrenJson != null) {
            for (String childJson : splitTopLevelObjects(childrenJson)) {
                PluginFilterConfigField child = parseFieldJson(childJson);
                if (child != null && StringUtils.isNotBlank(child.getKey())) {
                    field.getChildren().add(child);
                }
            }
        }
        return field;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value;
    }

    private static String extractJsonString(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

    private static String extractJsonArray(String json, String key) {
        return extractBracketValue(json, key, '[', ']');
    }

    private static String extractJsonObject(String json, String key) {
        return extractBracketValue(json, key, '{', '}');
    }

    private static String extractJsonValue(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([^,}\\n]+)").matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return StringUtils.trimToEmpty(matcher.group(1));
    }

    private static String extractBracketValue(String json, String key, char open, char close) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*" + Pattern.quote(String.valueOf(open))).matcher(json);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end() - 1;
        int depth = 0;
        boolean inString = false;
        for (int index = start; index < json.length(); index++) {
            char current = json.charAt(index);
            if (current == '"' && (index == 0 || json.charAt(index - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == open) {
                depth++;
            } else if (current == close) {
                depth--;
                if (depth == 0) {
                    return json.substring(start, index + 1);
                }
            }
        }
        return null;
    }

    private static List<String> parseStringArray(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyList();
        }
        Matcher matcher = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return values;
    }

    private static List<String> splitTopLevelObjects(String jsonArray) {
        if (StringUtils.isBlank(jsonArray)) {
            return Collections.emptyList();
        }
        String body = StringUtils.strip(jsonArray, "[]");
        if (StringUtils.isBlank(body)) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = -1;
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '"' && (index == 0 || body.charAt(index - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    values.add(body.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        return values;
    }

    private static Object parseScalarValue(String raw) {
        String value = StringUtils.trimToEmpty(raw);
        if (StringUtils.isBlank(value)) {
            return "";
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }
}
