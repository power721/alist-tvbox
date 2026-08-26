package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/** 元数据平台条目搜索结果(创建/编辑订阅时跨 provider 选择)。 */
@Data
public class MetadataSearchItem {
    private String provider;
    private String id;
    private String name;
    private String year;
    private String cover;
    private String score;
    private String description;
}
