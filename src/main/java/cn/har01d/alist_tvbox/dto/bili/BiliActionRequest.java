package cn.har01d.alist_tvbox.dto.bili;

/** atv-player 详情动作请求体(POST /bilibili/{token}/action):id=视频条目 id(aid-cid/BV/aid),action=动作 id。 */
public record BiliActionRequest(String id, String action) {
}
