package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;

/**
 * 追剧订阅的筛选条件(JSON 存 filter_config)。留空的维度继承用户默认偏好(user_preference)。
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
}
