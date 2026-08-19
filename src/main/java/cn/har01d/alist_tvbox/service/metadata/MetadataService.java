package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据统一入口:provider 注册表 + 聚合搜索。缓存由各 provider 自持(条目 24h/集数日程 6h 量级)。
 */
@Slf4j
@Service
public class MetadataService {

    /** 搜索结果 + 各源失败原因(前端提示"为什么只有豆瓣有结果"用)。 */
    public record SearchResult(List<MetadataSearchItem> items, Map<String, String> errors) {
    }

    private final List<MetadataProvider> providers;

    public MetadataService(List<MetadataProvider> providers) {
        this.providers = providers;
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
        List<MetadataProvider> targets = StringUtils.isBlank(providerName)
                ? providers
                : List.of(getProvider(providerName));
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

    /** 详情查询;provider 不可用返回 null(调用方降级,不中断巡检)。 */
    public MetadataDetails details(String providerName, String id, Integer season) {
        MetadataProvider provider = getProvider(providerName);
        if (provider == null) {
            return null;
        }
        try {
            return provider.details(id, season);
        } catch (Exception e) {
            log.warn("metadata details {} {} failed: {}", providerName, id, e.getMessage());
            return null;
        }
    }
}
