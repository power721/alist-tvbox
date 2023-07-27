package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.util.Constants;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
    public byte[] getImage(String url, HttpServletResponse response) {
        log.debug("get image by url: {}", url);
        if (url.endsWith(".webp")) {
            response.setContentType("image/webp");
        } else if (url.endsWith(".svg")) {
            response.setContentType("image/svg+xml");
        } else if (url.endsWith(".png")) {
            response.setContentType("image/png");
        } else {
            response.setContentType("image/jpeg");
        }
        return restTemplate.getForObject(url, byte[].class);
    }

}
