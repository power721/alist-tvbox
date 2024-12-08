package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

import java.util.List;

@Data
public class DouyuRoomList {
    private List<DouyuRoomInfo> list;
    private int pageCount;
}
