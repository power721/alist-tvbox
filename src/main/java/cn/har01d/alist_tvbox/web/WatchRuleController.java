package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.tg.TgWatchRule;
import cn.har01d.alist_tvbox.dto.tg.TgWatchRuleRequest;
import cn.har01d.alist_tvbox.dto.tg.TgWatchRulesResponse;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.TgProviderClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
public class WatchRuleController {
    private final TgProviderClient tgProviderClient;

    public WatchRuleController(TgProviderClient tgProviderClient) {
        this.tgProviderClient = tgProviderClient;
    }

    @GetMapping("/api/watch-rules")
    public TgWatchRulesResponse list() {
        return new TgWatchRulesResponse(tgProviderClient.watchRules());
    }

    @PostMapping("/api/watch-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public TgWatchRule create(@RequestBody TgWatchRuleRequest request) {
        return tgProviderClient.createWatchRule(normalize(request, false));
    }

    @GetMapping("/api/watch-rules/{id}")
    public TgWatchRule get(@PathVariable long id) {
        return tgProviderClient.watchRule(id);
    }

    @PutMapping("/api/watch-rules/{id}")
    public TgWatchRule update(@PathVariable long id, @RequestBody TgWatchRuleRequest request) {
        return tgProviderClient.updateWatchRule(id, normalize(request, true));
    }

    @DeleteMapping("/api/watch-rules/{id}")
    public void delete(@PathVariable long id) {
        tgProviderClient.deleteWatchRule(id);
    }

    private TgWatchRuleRequest normalize(TgWatchRuleRequest request, boolean requireEnabled) {
        if (request == null) {
            throw new BadRequestException("请求不能为空");
        }
        if (request.channelId() == null || request.channelId() <= 0) {
            throw new BadRequestException("channel_id 必须是正整数");
        }
        if (requireEnabled && request.enabled() == null) {
            throw new BadRequestException("enabled 必须传入");
        }
        Boolean enabled = request.enabled() == null ? Boolean.TRUE : request.enabled();
        return new TgWatchRuleRequest(
                request.channelId(),
                enabled,
                normalizeStrings(request.includes(), "includes"),
                normalizeStrings(request.excludes(), "excludes"));
    }

    private List<String> normalizeStrings(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new BadRequestException(field + " 必须是字符串数组");
        }
        return List.copyOf(values);
    }
}
