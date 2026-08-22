package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.MediaMetadata;
import cn.har01d.alist_tvbox.entity.MediaMetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据统一入口:provider 注册表 + 聚合搜索。
 * 详情三级供给:media_metadata 表(完结剧永久命中、在播剧按 TTL)→ provider(内存缓存)→ 外网,结果回写表。
 * 表层让重启不再清空元数据(封面/详情页零网络),也是媒体详情页分集数据的本地来源。
 */
@Slf4j
@Service
public class MetadataService {

    /** 搜索结果 + 各源失败原因(前端提示"为什么只有豆瓣有结果"用)。 */
    public record SearchResult(List<MetadataSearchItem> items, Map<String, String> errors) {
    }

    private final List<MetadataProvider> providers;
    private final MediaMetadataRepository metadataRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public MetadataService(List<MetadataProvider> providers,
                           MediaMetadataRepository metadataRepository,
                           AppProperties appProperties,
                           ObjectMapper objectMapper) {
        this.providers = providers;
        this.metadataRepository = metadataRepository;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public MetadataProvider getProvider(String name) {
        for (MetadataProvider provider : providers) {
            if (provider.getName().equalsIgnoreCase(name)) {
                return provider;
            }
        }
        return null;
    }

    /** 聚合搜索(创建对话框"全部"页签):各 provider 限 10 条,单源失败不影响其余,但记录失败原因。 */
    public SearchResult searchReport(String providerName, String keyword) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<MetadataSearchItem> items = new ArrayList<>();
        List<MetadataProvider> targets;
        if (StringUtils.isBlank(providerName)) {
            targets = providers;
        } else {
            MetadataProvider resolved = getProvider(providerName);
            if (resolved == null) {
                return new SearchResult(List.of(), Map.of(String.valueOf(providerName), "未知元数据源: " + providerName));
            }
            targets = List.of(resolved);
        }
        for (MetadataProvider provider : targets) {
            if (provider == null) {
                errors.put(String.valueOf(providerName), "未知元数据源: " + providerName);
                continue;
            }
            try {
                List<MetadataSearchItem> result = provider.search(keyword);
                items.addAll(result.size() > 10 ? result.subList(0, 10) : result);
            } catch (Exception e) {
                log.warn("metadata search {} failed: {}", provider.getName(), e.getMessage());
                errors.put(provider.getName(), StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        }
        return new SearchResult(items, errors);
    }

    public List<MetadataSearchItem> searchAll(String keyword) {
        return searchReport("", keyword).items();
    }

    public List<MetadataSearchItem> search(String providerName, String keyword) {
        return searchReport(providerName, keyword).items();
    }

    /**
     * 详情查询:持久层 → provider(内存→外网)→ 回写持久层。provider 不可用返回 null(调用方降级,不中断巡检)。
     */
    public MetadataDetails details(String providerName, String id, Integer season) {
        MetadataProvider provider = getProvider(providerName);
        if (provider == null) {
            return null;
        }
        int seasonNumber = season == null || season < 1 ? 1 : season;
        MetadataDetails persisted = readPersisted(provider.getName(), id, seasonNumber, false);
        if (persisted != null) {
            return persisted;
        }
        try {
            MetadataDetails details = provider.details(id, seasonNumber);
            persist(provider.getName(), id, seasonNumber, details);
            return details;
        } catch (Exception e) {
            log.warn("metadata details {} {} failed: {}", providerName, id, e.getMessage());
            return null;
        }
    }

    /** 只读持久层快照,不发起网络也不落库:详情页等绝不等待外网的调用方用;无快照返回 null。 */
    public MetadataDetails cachedDetails(String providerName, String id, Integer season) {
        MetadataProvider provider = getProvider(providerName);
        if (provider == null) {
            return null;
        }
        int seasonNumber = season == null || season < 1 ? 1 : season;
        return readPersisted(provider.getName(), id, seasonNumber, true);
    }

    /**
     * 强制刷新:穿透持久层与 provider 内存缓存直取外网,结果回写两层(表 + provider 缓存由其自行更新)。
     * 详情页"刷新元数据"按钮用,异步调用;同步路径别用(慢)。
     */
    public MetadataDetails refreshDetails(String providerName, String id, Integer season) {
        MetadataProvider provider = getProvider(providerName);
        if (provider == null) {
            return null;
        }
        int seasonNumber = season == null || season < 1 ? 1 : season;
        try {
            MetadataDetails details = provider.refreshDetails(id, seasonNumber);
            persist(provider.getName(), id, seasonNumber, details);
            return details;
        } catch (Exception e) {
            log.warn("metadata refresh {} {} failed: {}", providerName, id, e.getMessage());
            return null;
        }
    }

    private MetadataDetails readPersisted(String providerName, String id, int season, boolean allowStale) {
        return metadataRepository.findByProviderAndMetaIdAndSeason(providerName, id, season)
                .map(row -> {
                    try {
                        MetadataDetails details = objectMapper.readValue(row.getPayload(), MetadataDetails.class);
                        // 在播剧超 TTL 视为过期:details() 走 provider 重刷;详情页(allowStale)展示旧值等后台刷新
                        return !allowStale && isStale(details, row.getFetchTime()) ? null : details;
                    } catch (Exception e) {
                        log.debug("media metadata parse failed: {} {} {}", providerName, id, e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    private boolean isStale(MetadataDetails details, long fetchTime) {
        // 旧版/坏形态快照视为过期(完结剧也重拉一次,回写后恢复常规节奏):
        // ① 扩展字段全缺 = 分集详情扩展前写入;② genres/cast 粘连成串 = 豆瓣分隔符修复前写入
        if (isLegacySnapshot(details)) {
            return true;
        }
        if (!MetadataDetails.STATUS_RETURNING.equals(details.getStatus())) {
            return false;
        }
        long ttl = Math.max(1, appProperties.getSubscription().getAiringRefreshHours()) * 3600_000L;
        return System.currentTimeMillis() - fetchTime > ttl;
    }

    private boolean isLegacySnapshot(MetadataDetails details) {
        if (details.getOriginalName() == null && details.getGenres() == null && details.getRating() == null) {
            return true;
        }
        if (details.getRatings() == null) {
            return true; // 多源评分/外链扩展前写入
        }
        if (details.getGenres() != null && details.getGenres().stream()
                .anyMatch(g -> g.contains(",") || g.contains("，"))) {
            return true;
        }
        return details.getCast() != null && details.getCast().size() == 1
                && details.getCast().get(0).getName() != null
                && (details.getCast().get(0).getName().contains(",") || details.getCast().get(0).getName().contains("，"));
    }

    /** 网络结果回写;失败产出的空对象(name/封面/集数全空)不覆盖已有快照 —— 宁用旧值不写白板。 */
    private void persist(String providerName, String id, int season, MetadataDetails details) {
        if (details == null) {
            return;
        }
        boolean meaningful = StringUtils.isNotBlank(details.getName())
                || StringUtils.isNotBlank(details.getCover())
                || (details.getTotalEpisodes() != null && details.getTotalEpisodes() > 0);
        if (!meaningful) {
            return;
        }
        try {
            MediaMetadata row = metadataRepository.findByProviderAndMetaIdAndSeason(providerName, id, season)
                    .orElseGet(MediaMetadata::new);
            row.setProvider(providerName);
            row.setMetaId(id);
            row.setSeason(season);
            row.setStatus(StringUtils.defaultIfBlank(details.getStatus(), MetadataDetails.STATUS_UNKNOWN));
            row.setPayload(objectMapper.writeValueAsString(details));
            row.setFetchTime(System.currentTimeMillis());
            metadataRepository.save(row);
        } catch (Exception e) {
            log.debug("media metadata persist failed: {} {} {}", providerName, id, e.getMessage());
        }
    }
}
