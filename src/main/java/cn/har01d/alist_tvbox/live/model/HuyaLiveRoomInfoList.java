package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class HuyaLiveRoomInfoList {
    private List<HuyaLiveRoomInfo> datas = new ArrayList<>();
    private int totalPage;
}
