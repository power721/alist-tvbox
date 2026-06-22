package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.Index115CheckResult;
import cn.har01d.alist_tvbox.service.Index115Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/index115")
public class Index115Controller {
    private final Index115Service index115Service;

    public Index115Controller(Index115Service index115Service) {
        this.index115Service = index115Service;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("hasAccount", index115Service.has115Account());
    }

    @GetMapping("/check")
    public Index115CheckResult check() {
        return index115Service.check();
    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/update")
    public Map<String, String> update() {
        index115Service.update();
        return Map.of("status", "accepted");
    }
}
