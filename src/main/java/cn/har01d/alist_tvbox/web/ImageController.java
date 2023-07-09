package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;

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
        response.setContentType("image/webp");
        return restTemplate.getForObject(url, byte[].class);
    }

}
