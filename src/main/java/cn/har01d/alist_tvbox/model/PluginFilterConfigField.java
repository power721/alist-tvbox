package cn.har01d.alist_tvbox.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class PluginFilterConfigField {
    // 配置项键名，会直接写入过滤器 extend JSON。
    private String key;

    // 面向用户展示的中文名称。
    private String label = "";

    // 字段类型，目前前端支持 string / number / boolean / object。
    private String type = "string";

    // 是否必填。前端保存前会据此做校验。
    private boolean required;

    // 面向用户展示的说明文字。
    private String description = "";

    // 默认值，仅用于前端提示和初始化展示，不会强制写回。
    private Object defaultValue;

    // 输入框占位提示。
    private String placeholder = "";

    // 兼容旧配置时可接受的别名列表，例如 snake_case / camelCase。
    private List<String> aliases = new ArrayList<>();

    // 当 type=object 时，children 描述其嵌套字段结构。
    private List<PluginFilterConfigField> children = new ArrayList<>();
}
