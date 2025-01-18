package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

@Data
public class Category {
    private String type_id;
    private String type_name;
    private String cover;
    private int type_flag = 1;
    private Integer land;
    private Double ratio;
}
