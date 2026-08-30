package cn.har01d.alist_tvbox.telegram;

/** Bot API 调用失败(description 为 Telegram 返回的中文/英文错误描述,不含 token)。 */
public class TelegramApiException extends RuntimeException {
    public TelegramApiException(String message) {
        super(message);
    }

    public TelegramApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
