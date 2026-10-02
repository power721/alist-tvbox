package cn.har01d.alist_tvbox.dto.bili;

/**
 * atv-player 发送弹幕请求体(POST /bilibili/{token}/danmaku-post):
 * id=视频条目 id(aid-cid/BV/aid,cid 缺失时后端解析首个分P),message=弹幕内容(≤100 字),
 * progress=弹幕出现时间(毫秒),mode 1=滚动/4=底部/5=顶部,color/fontsize 同上游参数。
 */
public record BiliDanmakuPostRequest(String id, String message, Long progress, Integer mode, Integer color, Integer fontsize) {
}
