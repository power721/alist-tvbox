package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.util.Constants;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;


@Slf4j
@RestController
@RequestMapping("/images")
public class ImageController {

    private final RestTemplate restTemplate;

    public ImageController(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .defaultHeader("Referer", "https://movie.douban.com/")
                .defaultHeader("User-Agent", Constants.USER_AGENT)
                .build();
    }

    @GetMapping(value = "", produces = "image/webp")
    public ResponseEntity<byte[]> getImage(String url, HttpServletResponse response) {
        if (url.contains(".webp")) {
            response.setContentType("image/webp");
        } else if (url.contains(".svg")) {
            response.setContentType("image/svg+xml");
        } else if (url.contains(".png")) {
            response.setContentType("image/png");
        } else {
            response.setContentType("image/jpeg");
        }
        HttpHeaders headers = new HttpHeaders();
        if (url.contains("ytimg.com")) {
            headers.set(HttpHeaders.REFERER, "https://www.youtube.com/");
        } else if (url.contains("netease.com")) {
            headers.set(HttpHeaders.REFERER, "https://cc.163.com/");
        }
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        return restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
    }

}
