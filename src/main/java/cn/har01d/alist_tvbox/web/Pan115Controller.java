package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/pan115")
public class Pan115Controller {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public Pan115Controller(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", Constants.USER_AGENT)
                .defaultHeader("referer", "https://115.com")
                .build();
        this.objectMapper = objectMapper;
    }

    @GetMapping("/token")
    public Object token() throws JsonProcessingException {
        String json = restTemplate.getForObject("https://qrcodeapi.115.com/api/1.0/web/1.0/token", String.class);
        log.debug("token: {}", json);
        return objectMapper.readValue(json, Map.class);
    }

    @GetMapping("/status")
    public Object status(HttpServletRequest request) throws JsonProcessingException {
        String url = "https://qrcodeapi.115.com/get/status/?" + request.getQueryString();
        log.debug("url: {}", url);
        String json =  restTemplate.getForObject(url, String.class);
        log.debug("status: {}", json);
        return objectMapper.readValue(json, Map.class);
    }

    @GetMapping("/result")
    public Object result(String app, String uid) throws JsonProcessingException {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("account", uid);
        String json =  restTemplate.postForObject("https://passportapi.115.com/app/1.0/" + app + "/1.0/login/qrcode", body, String.class);
        log.debug("result: {}", json);
        return objectMapper.readValue(json, Map.class);
    }
}
