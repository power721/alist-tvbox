package cn.har01d.alist_tvbox.telegram;

/** inline keyboard 按钮:text 展示、callbackData 携带路由载荷(须 &lt;64 字节)。 */
public record TelegramButton(String text, String callbackData) {
}
