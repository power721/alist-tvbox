package cn.har01d.alist_tvbox.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class PluginFilterConfigSchema {
    // schema 来源：declared=过滤器自声明，none=未声明。
    private String source = "none";

    // 整体配置说明，会展示在配置弹窗头部。
    private String description = "";

    // 是否允许填写 schema 之外的额外字段。
    private boolean allowAdditional = true;

    // 如果过滤器历史上支持“直接填一个字符串”，这里指定映射到哪个主字段。
    private String singleValueKey = "";

    // 示例 JSON，便于前端后续扩展展示或快速填充。
    private String example = "";

    // 顶层字段定义列表。
    private List<PluginFilterConfigField> fields = new ArrayList<>();
}
