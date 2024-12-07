package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class HuyaCategoryList {
    private List<HuyaCategory> gameList = new ArrayList<>();
}
