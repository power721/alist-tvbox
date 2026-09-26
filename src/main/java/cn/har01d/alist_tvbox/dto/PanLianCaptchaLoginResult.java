package cn.har01d.alist_tvbox.dto;

/**
 * 盘链验证码登录结果:success=false 时 message 为用户可读原因
 * (验证码错误/账号密码被拒/限流/需邮箱确认等),前端据此提示并刷新验证码。
 */
public record PanLianCaptchaLoginResult(
        boolean success,
        String message,
        String userId) {

    public static PanLianCaptchaLoginResult ok(String userId) {
        return new PanLianCaptchaLoginResult(true, "登录成功", userId);
    }

    public static PanLianCaptchaLoginResult fail(String message) {
        return new PanLianCaptchaLoginResult(false, message, null);
    }
}
