package cn.har01d.alist_tvbox.dto.tg;

import java.util.List;

public record TgWatchRulesResponse(List<TgWatchRule> items) {
    public TgWatchRulesResponse {
        items = items == null ? List.of() : items;
    }
}
