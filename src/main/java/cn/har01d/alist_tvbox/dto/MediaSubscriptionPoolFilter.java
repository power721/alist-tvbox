package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 追剧订阅的全局资源筛选(Setting 表 msub_pool_filter 单行 JSON,web-ui「追剧设置-资源筛选」编辑)。
 * 运行时全局层:所有订阅入池/候选复筛即读即用,订阅级显式配置优先、排除词与订阅级取并集。
 * <p>
 * 与订阅级 {@link MediaSubscriptionFilter} 的分界:订阅级 includeKeywords 仅加分(软偏好),
 * 全局 includeKeywords 是硬门禁(标题须至少含其一);清晰度同为标题级判定 —— 只拒<b>明确标注</b>
 * 低于门槛的资源,未标注的放行(挂载前无从判断,避免误杀召回)。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MediaSubscriptionPoolFilter {
    /** 硬门禁:非空时标题须至少包含其一才入池;空 = 不限。过严会把候选池筛空,UI 有提示 */
    private List<String> includeKeywords;
    /** 硬拒绝:标题含任一即不入池;与订阅级 excludeKeywords 取并集 */
    private List<String> excludeKeywords;
    /** 清晰度门槛:""(不限)/ "hd"(720P)/ "fhd"(1080P)/ "uhd"(4K);仅拒标题明确标注低于门槛的 */
    private String minQuality = "";
    /** 单集体积下限(MB),0/空 = 沿用部署默认底线(垃圾/样片防护)。硬底线,过严会丢小体积正片 */
    private Integer minEpisodeSizeMb;
    /** 单集体积上限(MB),0/空 = 不限;订阅级显式配置优先 */
    private Integer maxEpisodeSizeMb;

    /** 归一化非法取值,解析与更新时都调用 */
    public void normalize() {
        includeKeywords = normalizeKeywords(includeKeywords);
        excludeKeywords = normalizeKeywords(excludeKeywords);
        minQuality = normalizeQuality(minQuality);
        if (minEpisodeSizeMb != null && minEpisodeSizeMb < 0) {
            minEpisodeSizeMb = 0;
        }
        if (maxEpisodeSizeMb != null && maxEpisodeSizeMb < 0) {
            maxEpisodeSizeMb = 0;
        }
        if (minEpisodeSizeMb != null && minEpisodeSizeMb > 0
                && maxEpisodeSizeMb != null && maxEpisodeSizeMb > 0
                && minEpisodeSizeMb > maxEpisodeSizeMb) {
            maxEpisodeSizeMb = 0; // 上下限矛盾:视为不限,交给用户改
        }
    }

    private static List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()) {
                set.add(keyword.trim());
            }
        }
        return set.isEmpty() ? null : new ArrayList<>(set);
    }

    public static String normalizeQuality(String quality) {
        if (quality == null) {
            return "";
        }
        return switch (quality.trim().toLowerCase()) {
            case "hd", "720" -> "hd";
            case "fhd", "1080" -> "fhd";
            case "uhd", "4k", "2160" -> "uhd";
            default -> "";
        };
    }
}
