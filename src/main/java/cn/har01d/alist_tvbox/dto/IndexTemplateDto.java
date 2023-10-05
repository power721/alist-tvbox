package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class IndexTemplateDto {
    private Integer siteId;
    private String name;
    private String data;
    private boolean scheduled;
    private String scheduleTime;
    private int sleep = 2000;
}
