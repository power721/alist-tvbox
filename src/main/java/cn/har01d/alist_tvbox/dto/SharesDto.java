package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class SharesDto {
    private int type;
    private String content;
    private int delay = 0;  // delay in milliseconds after each import
}
