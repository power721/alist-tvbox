package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@RestController
public class WallpaperController {

    private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");
    private final Random random = new Random();
    private final SubscriptionService subscriptionService;

    public WallpaperController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/wallpaper/{token}")
    public void wallpaper(@PathVariable String token, HttpServletResponse response) throws IOException {
        subscriptionService.checkToken(token);

        Path wallpaperDir = Utils.getWebPath("static", "wallpapers");
        Files.createDirectories(wallpaperDir);

        List<Path> images;
        try (Stream<Path> stream = Files.list(wallpaperDir)) {
            images = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isImage)
                    .toList();
        }

        if (images.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "没有壁纸图片");
            return;
        }

        Path selected = images.get(random.nextInt(images.size()));
        String contentType = Files.probeContentType(selected);
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(0, TimeUnit.SECONDS).noCache().getHeaderValue());
        response.setContentLengthLong(Files.size(selected));
        Files.copy(selected, response.getOutputStream());
    }

    private boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return IMAGE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
