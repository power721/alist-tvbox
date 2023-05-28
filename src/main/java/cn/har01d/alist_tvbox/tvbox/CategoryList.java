package cn.har01d.alist_tvbox.tvbox;

import cn.har01d.alist_tvbox.model.Filter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class CategoryList {
    private int page = 1;
    private int pagecount = 1;
    private int limit;
    private int total;
    @JsonProperty("class")
    private List<Category> categories = new ArrayList<>();
    private List<MovieDetail> list = new ArrayList<>();
    private Map<String, Filter> filters = new HashMap<>();
}
