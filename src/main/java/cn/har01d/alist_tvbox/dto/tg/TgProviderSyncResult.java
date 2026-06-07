package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgProviderSyncResult(int messages, int links) {
}
