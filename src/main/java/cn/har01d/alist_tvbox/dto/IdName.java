package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class IdName {
    private Integer id;
    private String name;
    @JsonProperty("known_for_department")
    private String department;
}
