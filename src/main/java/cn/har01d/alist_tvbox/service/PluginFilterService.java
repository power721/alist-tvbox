package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.PluginFilter;
import cn.har01d.alist_tvbox.entity.PluginFilterRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.model.PluginFilterConfigField;
import cn.har01d.alist_tvbox.model.PluginFilterConfigSchema;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

    @Service
public class PluginFilterService {
    private static final Pattern FILTER_VERSION = Pattern.compile("(?m)^\\s*//@version:(\\d+)\\s*$");
    // 兼容早期注释式 schema 声明：
    // // @config-schema { ... }
    private static final Pattern FILTER_CONFIG_SCHEMA_JSON = Pattern.compile("(?s)//\\s*@config-schema\\s*(\\{.*?})\\s*(?:\\R\\s*//\\s*@|\\z)");
    // 当前正式约定：过滤器脚本顶层声明 FILTER_CONFIG_SCHEMA = { ... }
    // 这样不会影响旧运行环境执行，同时便于主项目通用读取。
    private static final Pattern FILTER_CONFIG_SCHEMA_CONST = Pattern.compile("(?s)^\\s*FILTER_CONFIG_SCHEMA\\s*=\\s*(\\{.*?})\\s*(?:\\R\\s*\\w+\\s*=|\\R\\s*class\\s+|\\z)", Pattern.MULTILINE);
    private static final String GITHUB_PROXY = "github_proxy";
    private static final String STRICT = "strict";
    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_INCLUDE = "include";
    private static final String SCOPE_EXCLUDE = "exclude";

    private final PluginFilterRepository pluginFilterRepository;
    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;

    public PluginFilterService(PluginFilterRepository pluginFilterRepository,
                               SettingRepository settingRepository,
                               RestTemplateBuilder builder) {
        this.pluginFilterRepository = pluginFilterRepository;
        this.settingRepository = settingRepository;
        this.restTemplate = builder.build();
    }

    private record DownloadedFilter(String body, String sourceName, Integer version) {
    }

    public List<PluginFilter> findAll() {
        return pluginFilterRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .peek(this::applyConfigSchema)
                .toList();
    }

    @Transactional
    public PluginFilter create(PluginFilter filter) {
        filter.setId(null);
        validateUrlUniqueness(filter.getUrl(), null);
        applyDownloadedFilter(filter, downloadFilterData(filter.getUrl()), false);
        filter.setEnabled(true);
        filter.setSortOrder((int) pluginFilterRepository.count() + 1);
        filter.setStages(normalizeStages(filter.getStages()));
        filter.setErrorStrategy(normalizeErrorStrategy(filter.getErrorStrategy()));
        // 统一收敛作用范围字段，避免前端传入空值或非法值
        String pluginScope = normalizePluginScope(filter.getPluginScope());
        filter.setPluginScope(pluginScope);
        filter.setPluginIds(normalizePluginIds(pluginScope, filter.getPluginIds()));
        filter.setLastCheckedAt(OffsetDateTime.now());
        filter.setLastError("");
        PluginFilter saved = pluginFilterRepository.save(filter);
        applyConfigSchema(saved);
        return saved;
    }

    @Transactional
    public PluginFilter update(Integer id, PluginFilter input) {
        PluginFilter filter = pluginFilterRepository.findById(id).orElseThrow(NotFoundException::new);
        boolean urlChanged = !StringUtils.equals(filter.getUrl(), input.getUrl());
        if (urlChanged) {
            validateUrlUniqueness(input.getUrl(), id);
            filter.setUrl(input.getUrl());
            applyDownloadedFilter(filter, downloadFilterData(input.getUrl()), shouldUpdateName(filter));
            filter.setLastCheckedAt(OffsetDateTime.now());
            filter.setLastError("");
        }
        filter.setName(StringUtils.defaultIfBlank(input.getName(), filter.getSourceName()));
        filter.setEnabled(input.isEnabled());
        filter.setStages(normalizeStages(input.getStages()));
        filter.setExtend(input.getExtend());
        filter.setErrorStrategy(normalizeErrorStrategy(input.getErrorStrategy()));
        // 更新时和创建时保持相同的作用范围归一化规则
        String pluginScope = normalizePluginScope(input.getPluginScope());
        filter.setPluginScope(pluginScope);
        filter.setPluginIds(normalizePluginIds(pluginScope, input.getPluginIds()));
        PluginFilter saved = pluginFilterRepository.save(filter);
        applyConfigSchema(saved);
        return saved;
    }

    @Transactional
    public PluginFilter refresh(Integer id) {
        PluginFilter filter = pluginFilterRepository.findById(id).orElseThrow(NotFoundException::new);
        try {
            applyDownloadedFilter(filter, downloadFilterData(filter.getUrl()), shouldUpdateName(filter));
            filter.setLastError("");
        } catch (RuntimeException e) {
            filter.setLastError(e.getMessage());
        }
        filter.setLastCheckedAt(OffsetDateTime.now());
        PluginFilter saved = pluginFilterRepository.save(filter);
        applyConfigSchema(saved);
        return saved;
    }

    @Transactional
    public void reorder(List<Integer> ids) {
        List<PluginFilter> filters = new ArrayList<>();
        int order = 1;
        for (Integer id : ids) {
            PluginFilter filter = pluginFilterRepository.findById(id).orElseThrow(NotFoundException::new);
            filter.setSortOrder(order++);
            filters.add(filter);
        }
        pluginFilterRepository.saveAll(filters);
    }

    @Transactional
    public void delete(Integer id) {
        PluginFilter filter = pluginFilterRepository.findById(id).orElseThrow(NotFoundException::new);
        pluginFilterRepository.delete(filter);
        normalizeSortOrder();
    }

    @Transactional
    public int deleteBatch(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<PluginFilter> filters = pluginFilterRepository.findAllById(ids);
        pluginFilterRepository.deleteAll(filters);
        normalizeSortOrder();
        return filters.size();
    }

    public String readContent(Integer id) {
        PluginFilter filter = pluginFilterRepository.findById(id).orElseThrow(NotFoundException::new);
        if (StringUtils.isBlank(filter.getContent())) {
            throw new BadRequestException("过滤器内容为空");
        }
        return filter.getContent();
    }

    public PluginFilterConfigSchema readConfigSchema(Integer id) {
        PluginFilter filter = pluginFilterRepository.findById(id).orElseThrow(NotFoundException::new);
        return buildConfigSchema(filter.getContent(), filter.getSourceName());
    }

    private void normalizeSortOrder() {
        List<PluginFilter> filters = pluginFilterRepository.findAllByOrderBySortOrderAscIdAsc();
        int order = 1;
        for (PluginFilter filter : filters) {
            filter.setSortOrder(order++);
        }
        pluginFilterRepository.saveAll(filters);
    }

    private boolean shouldUpdateName(PluginFilter filter) {
        return StringUtils.isBlank(filter.getName()) || StringUtils.equals(filter.getName(), filter.getSourceName());
    }

    private void validateUrlUniqueness(String url, Integer currentId) {
        pluginFilterRepository.findByUrl(url).ifPresent(other -> {
            if (currentId == null || !other.getId().equals(currentId)) {
                throw new BadRequestException("过滤器地址重复");
            }
        });
    }

    private DownloadedFilter downloadFilterData(String url) {
        String body = downloadText(url, "过滤器地址不可访问");
        return new DownloadedFilter(body, deriveSourceName(url), extractFilterVersion(body));
    }

    private String downloadText(String url, String message) {
        try {
            String body = restTemplate.getForObject(URI.create(buildRemoteUrl(url)), String.class);
            if (StringUtils.isBlank(body)) {
                throw new BadRequestException(message);
            }
            return body;
        } catch (Exception e) {
            throw new BadRequestException(message, e);
        }
    }

    private String deriveSourceName(String url) {
        String raw = url.substring(url.lastIndexOf('/') + 1);
        String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        int dot = decoded.lastIndexOf('.');
        return dot > 0 ? decoded.substring(0, dot) : decoded;
    }

    private Integer extractFilterVersion(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        Matcher matcher = FILTER_VERSION.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String buildRemoteUrl(String url) {
        String remoteUrl = normalizeGithubFileUrl(url);
        if (!StringUtils.startsWith(remoteUrl, "https://github.com/")) {
            return remoteUrl;
        }
        String proxy = settingRepository.findById(GITHUB_PROXY)
                .map(e -> StringUtils.trimToEmpty(e.getValue()))
                .orElse("");
        if (proxy.isBlank()) {
            return remoteUrl;
        }
        return StringUtils.appendIfMissing(proxy, "/") + remoteUrl;
    }

    private String normalizeGithubFileUrl(String url) {
        String source = StringUtils.trimToEmpty(url);
        try {
            URI uri = URI.create(source);
            if (!"github.com".equalsIgnoreCase(uri.getHost())) {
                return source;
            }
            String[] segments = StringUtils.strip(uri.getPath(), "/").split("/");
            if (segments.length < 5 || !"blob".equals(segments[2])) {
                return source;
            }
            String path = Arrays.stream(segments, 4, segments.length).collect(Collectors.joining("/"));
            return "https://github.com/" + segments[0] + "/" + segments[1] + "/raw/refs/heads/" + segments[3] + "/" + path;
        } catch (Exception e) {
            return source;
        }
    }

    private String normalizeStages(String stages) {
        String value = Arrays.stream(StringUtils.defaultString(stages).split(","))
                .map(StringUtils::trimToEmpty)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.joining(","));
        if (StringUtils.isBlank(value)) {
            throw new BadRequestException("请选择过滤器拦截点");
        }
        return value;
    }

    private String normalizeErrorStrategy(String errorStrategy) {
        return STRICT.equals(errorStrategy) ? STRICT : "skip";
    }

    private String normalizePluginScope(String pluginScope) {
        if (StringUtils.isBlank(pluginScope) || SCOPE_ALL.equals(pluginScope)) {
            return SCOPE_ALL;
        }
        if (SCOPE_INCLUDE.equals(pluginScope) || SCOPE_EXCLUDE.equals(pluginScope)) {
            return pluginScope;
        }
        throw new BadRequestException("过滤器插件作用范围不正确");
    }

    private String normalizePluginIds(String pluginScope, String pluginIds) {
        // 全局模式不保留关联插件，避免历史配置残留影响后续判断
        if (SCOPE_ALL.equals(pluginScope)) {
            return "";
        }
        String value = Arrays.stream(StringUtils.defaultString(pluginIds).split(","))
                .map(StringUtils::trimToEmpty)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.joining(","));
        if (StringUtils.isBlank(value)) {
            throw new BadRequestException("请选择关联插件");
        }
        return value;
    }

    private void applyDownloadedFilter(PluginFilter filter, DownloadedFilter downloadedFilter, boolean updateName) {
        filter.setSourceName(downloadedFilter.sourceName());
        if (updateName) {
            filter.setName(downloadedFilter.sourceName());
        } else {
            filter.setName(StringUtils.defaultIfBlank(filter.getName(), downloadedFilter.sourceName()));
        }
        filter.setContent(downloadedFilter.body());
        filter.setVersion(downloadedFilter.version());
        applyConfigSchema(filter);
    }

    private void applyConfigSchema(PluginFilter filter) {
        filter.setConfigSchema(buildConfigSchema(filter.getContent(), filter.getSourceName()));
    }

    private PluginFilterConfigSchema buildConfigSchema(String content, String sourceName) {
        // 只读取过滤器自带声明，不再在主项目中维护任何写死字段映射。
        PluginFilterConfigSchema declared = parseDeclaredSchema(content);
        if (declared != null) {
            if (StringUtils.isBlank(declared.getDescription())) {
                declared.setDescription("来自过滤器脚本内声明");
            }
            if (StringUtils.isBlank(declared.getSource())) {
                declared.setSource("declared");
            }
            return declared;
        }
        PluginFilterConfigSchema schema = new PluginFilterConfigSchema();
        schema.setSource("none");
        schema.setDescription("过滤器未声明配置结构，可直接输入 JSON");
        return schema;
    }

    private PluginFilterConfigSchema parseDeclaredSchema(String content) {
        if (StringUtils.isBlank(content)) {
            return null;
        }
        // 先兼容旧的注释式声明，便于平滑迁移。
        Matcher jsonCommentMatcher = FILTER_CONFIG_SCHEMA_JSON.matcher(content);
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

        // 再读取当前正式使用的 Python 顶层常量声明。
        Matcher constMatcher = FILTER_CONFIG_SCHEMA_CONST.matcher(content);
        if (!constMatcher.find()) {
            return null;
        }
        String json = StringUtils.trimToEmpty(constMatcher.group(1));
        if (json.isBlank()) {
            return null;
        }
        try {
            return parseSchemaJson(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private PluginFilterConfigSchema parseSchemaJson(String json) {
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

    private PluginFilterConfigField parseFieldJson(String fieldJson) {
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

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value;
    }

    private String extractJsonString(String json, String key) {
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

    private String extractJsonArray(String json, String key) {
        return extractBracketValue(json, key, '[', ']');
    }

    private String extractJsonObject(String json, String key) {
        return extractBracketValue(json, key, '{', '}');
    }

    private String extractJsonValue(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([^,}\\n]+)").matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return StringUtils.trimToEmpty(matcher.group(1));
    }

    private String extractBracketValue(String json, String key, char open, char close) {
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

    private List<String> parseStringArray(String json) {
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

    private List<String> splitTopLevelObjects(String jsonArray) {
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

    private Object parseScalarValue(String raw) {
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
