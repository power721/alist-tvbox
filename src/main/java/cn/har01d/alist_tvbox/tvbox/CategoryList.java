package cn.har01d.alist_tvbox.tvbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CategoryList {
    private int page = 1;
    private int pagecount = 1;
    private int limit;
    private int total;
    @JsonProperty("class")
    private List<Category> list = new ArrayList<>();
}
