package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;

import java.util.List;

/**
 * 元数据平台统一抽象(§4.8):条目匹配 + 最新集数/状态/播出日程查询。
 * 实现需容忍外部接口失败:抛异常由调用方降级,不影响巡检主流程。
 */
public interface MetadataProvider {
    String getName();

    List<MetadataSearchItem> search(String keyword);

    /** @param season 目标季(可空,空按第 1 季处理) */
    MetadataDetails details(String id, Integer season);
}
