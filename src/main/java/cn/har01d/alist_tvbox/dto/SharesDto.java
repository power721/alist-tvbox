package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class SharesDto {
    private String type;
    private String content;
    private int delay = 0;  // delay in milliseconds after each import

    public void setType(String type) {
        this.type = type;
    }

    public void setType(int type) {
        this.type = String.valueOf(type);
    }
}
