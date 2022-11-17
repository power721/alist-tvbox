package cn.har01d.alist_tvbox.model;

import lombok.Data;

import java.util.List;

@Data
public class Filter {
    private final String key;
    private final String name;
    private final List<FilterValue> value;
}
