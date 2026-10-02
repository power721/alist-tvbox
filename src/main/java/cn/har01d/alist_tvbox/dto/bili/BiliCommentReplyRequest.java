package cn.har01d.alist_tvbox.dto.bili;

/**
 * atv-player 评论回复请求体(POST /bilibili/{token}/comment-reply):
 * id=视频条目 id(aid-cid/BV/aid),root=根评论 rpid(回复一级评论时=该评论),
 * parent=被回复评论 rpid(楼中楼内互答时=被回复行,否则=root),message=内容(≤1000 字)。
 */
public record BiliCommentReplyRequest(String id, String root, String parent, String message) {
}
