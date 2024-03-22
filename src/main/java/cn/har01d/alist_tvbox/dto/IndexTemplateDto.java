package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class IndexTemplateDto {
    private Integer siteId = 1;
    private String name = "custom_index";
    private String data;
    private boolean scheduled;
    private boolean scrape;
    private String scheduleTime = "";
    private int sleep = 2000;
}
