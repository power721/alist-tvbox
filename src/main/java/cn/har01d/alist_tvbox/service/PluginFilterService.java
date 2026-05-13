package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.PluginFilter;
import cn.har01d.alist_tvbox.entity.PluginFilterRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PluginFilterService {
    private static final Pattern FILTER_VERSION = Pattern.compile("(?m)^\\s*//@version:(\\d+)\\s*$");
    private static final String GITHUB_PROXY = "github_proxy";
    private static final String STRICT = "strict";

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
        return pluginFilterRepository.findAllByOrderBySortOrderAscIdAsc();
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
        filter.setLastCheckedAt(OffsetDateTime.now());
        filter.setLastError("");
        return pluginFilterRepository.save(filter);
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
        return pluginFilterRepository.save(filter);
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
        return pluginFilterRepository.save(filter);
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

    private void applyDownloadedFilter(PluginFilter filter, DownloadedFilter downloadedFilter, boolean updateName) {
        filter.setSourceName(downloadedFilter.sourceName());
        if (updateName) {
            filter.setName(downloadedFilter.sourceName());
        } else {
            filter.setName(StringUtils.defaultIfBlank(filter.getName(), downloadedFilter.sourceName()));
        }
        filter.setContent(downloadedFilter.body());
        filter.setVersion(downloadedFilter.version());
    }
}
