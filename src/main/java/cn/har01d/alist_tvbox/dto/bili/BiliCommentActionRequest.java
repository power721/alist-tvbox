package cn.har01d.alist_tvbox.dto.bili;

/**
 * atv-player 评论动作请求体(POST /bilibili/{token}/comment-action):
 * id=视频条目 id(aid-cid/BV/aid),rpid=目标评论,action=1 点赞/0 取消点赞。
 */
public record BiliCommentActionRequest(String id, String rpid, Integer action) {
}
