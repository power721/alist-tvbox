package cn.har01d.alist_tvbox.dto.qqmusic;

/**
 * status: waiting / scanned / success / expired / failed；
 * success 时 extend 为可直接写入订阅源的凭据 JSON。
 */
public record QqMusicLoginStatus(String status, String message, String extend) {
}
