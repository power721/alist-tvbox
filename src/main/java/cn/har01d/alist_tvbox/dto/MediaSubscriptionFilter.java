package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 追剧订阅的筛选条件(JSON 存 filter_config)。留空的维度继承用户默认偏好(user_preference)。
 * <p>
 * 硬过滤与软偏好的分界(Q14):盘类型/关键词/体积上下限是<b>硬过滤或结构化偏好</b>,保持固定字段;
 * 打分维度是<b>排序偏好</b>,一律走 {@link #weights} 可调权重表 —— 13 维筛选 DSL 的教训是
 * "大量条件硬编码会把池筛空",而权重调到 0 只是不再优先,不会丢召回。
 */
@Data
public class MediaSubscriptionFilter {
    /** 盘类型偏好顺序(分享类型码:0=阿里 1=PikPak 2=迅雷 3=123 5=夸克 6=移动 7=UC 8=115 9=天翼 10=百度 12=光鸭) */
    private List<Integer> driveTypes;
    /** 清晰度关键词(4K/1080P/720P),命中的候选加分 */
    private List<String> qualities;
    private List<String> includeKeywords;
    private List<String> excludeKeywords;
    /** 单集体积下限(MB),过滤预告/花絮 */
    private Integer minEpisodeSizeMb;
    /** 单集体积上限(MB),0/空 = 不限(过滤捆绑包/花絮合集里的异常大文件) */
    private Integer maxEpisodeSizeMb;
    /** 打分权重表(维度 key → 加减分);缺省维度用内置默认值。key 见 CheckService.WEIGHT_DEFAULTS */
    private Map<String, Integer> weights;
}
