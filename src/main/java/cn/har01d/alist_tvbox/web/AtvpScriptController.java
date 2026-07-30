package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.AtvpScriptService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class AtvpScriptController {
    private final AtvpScriptService atvpScriptService;
    private final SubscriptionService subscriptionService;

    public AtvpScriptController(AtvpScriptService atvpScriptService, SubscriptionService subscriptionService) {
        this.atvpScriptService = atvpScriptService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping(value = "/Atvp/{token}/{version}.py", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> atvp(@PathVariable String token, @PathVariable String version) throws IOException {
        subscriptionService.checkToken(token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(atvpScriptService.render());
    }
}
