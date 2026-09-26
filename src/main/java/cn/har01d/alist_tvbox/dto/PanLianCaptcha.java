package cn.har01d.alist_tvbox.dto;

/**
 * 盘链图形验证码(2026-09-26 起站点登录强制):匿名 {@code GET /api/auth/captcha}
 * 的透传,image 为 data URI(base64 PNG),前端直接渲染;id 配套登录表单 captcha_id。
 */
public record PanLianCaptcha(
        /** 验证码会话 id,登录时作 captcha_id 提交(单次有效) */
        String captchaId,
        /** data:image/png;base64,... 直接可渲染 */
        String image,
        /** 有效秒数(站点实测 300) */
        int ttlSeconds) {
}
