package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.service.ProxyService;
import cn.har01d.alist_tvbox.util.Constants;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;


@Slf4j
@RestController
@RequestMapping("/images")
public class ImageController {

    private final RestTemplate restTemplate;
    private final ProxyService proxyService;

    public ImageController(RestTemplateBuilder builder, ProxyService proxyService) {
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.REFERER, "https://movie.douban.com/")
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .build();
        this.proxyService = proxyService;
    }

    @GetMapping(value = "", produces = "image/webp")
    public ResponseEntity<byte[]> getImage(String url, String referer, HttpServletResponse response) {
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
        } else if (url.contains("/Images/Primary")) {
            headers.set(HttpHeaders.REFERER, referer);
            headers.set(HttpHeaders.USER_AGENT, Constants.EMBY_USER_AGENT);
        }
        HttpEntity<Void> entity = new HttpEntity<>(null, headers);
        return restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
    }

    @GetMapping(value = "/{id}", produces = "image/webp")
    public ResponseEntity<byte[]> getImage(@PathVariable int id, HttpServletResponse response) {
        PlayUrl url = proxyService.getPlayUrl(id);
        log.debug("original image url: {}", url.getPath());
        return getImage(url.getPath(), url.getReferer(), response);
    }
}
