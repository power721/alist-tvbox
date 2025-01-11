package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

import java.util.List;

@Data
public class BilibiliCategoryList {
    private int id;
    private String name;
    private List<BilibiliCategory> list;
}
