package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class FilterDto {
    private String category = "";
    private String type = "";
    private String status = "";
    private String sort = "";
    private String duration = "";
    private String year = "";
}
