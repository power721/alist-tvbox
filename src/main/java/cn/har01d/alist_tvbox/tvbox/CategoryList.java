package cn.har01d.alist_tvbox.tvbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class CategoryList {
    @JsonProperty("class")
    private final List<Category> list;

    public int getTotal() {
        return list.size();
    }
}
