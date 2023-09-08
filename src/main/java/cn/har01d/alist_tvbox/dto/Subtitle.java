package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class Subtitle {
    private String name = "英文";
    private String lang = "eng";
    private String format = "application/x-subrip";
    private String ext;
    private String url;
}
