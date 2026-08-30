package cn.har01d.alist_tvbox.dto.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Telegram Bot API 的 user 对象。id 是唯一身份标识,username/firstName 仅展示用、可变。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BotUser {
    private long id;
    @JsonProperty("first_name")
    private String firstName;
    private String username;
}
