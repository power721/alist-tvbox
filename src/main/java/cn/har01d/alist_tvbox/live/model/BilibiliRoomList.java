package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

import java.util.List;

@Data
public class BilibiliRoomList {
    private List<BilibiliRoomInfo> list;
    // 直播首页推荐流(index/getList)的房间字段
    private List<BilibiliRoomInfo> room_list;
    private List<BilibiliRoomInfo> recommend_room_list;
}
