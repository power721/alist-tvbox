package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderAccount(long id,
                                String username,
                                @JsonProperty("first_name") String firstName,
                                @JsonProperty("last_name") String lastName,
                                String phone) {
}
