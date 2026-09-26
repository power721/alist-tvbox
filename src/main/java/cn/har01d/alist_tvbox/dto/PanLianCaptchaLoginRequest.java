package cn.har01d.alist_tvbox.dto;

/**
 * 盘链验证码登录请求:站点登录已强制图形验证码,账号密码自动重登不再可行,
 * 由用户在网页端看着验证码图人工输入完成登录(凭证与 /api/settings 同暴露面)。
 */
public record PanLianCaptchaLoginRequest(
        String username,
        String password,
        /** GET /api/auth/captcha 返回的 id */
        String captchaId,
        /** 用户输入的图形验证码 */
        String captchaCode) {
}
