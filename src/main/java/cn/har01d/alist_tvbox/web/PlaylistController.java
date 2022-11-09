package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.PlaylistService;
import cn.har01d.alist_tvbox.tvbox.GenerateRequest;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/playlist")
public class PlaylistController {
    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @GetMapping
    public byte[] generate(String site, String path, boolean includeSub, HttpServletResponse response) {
        if (site == null || site.isEmpty()) {
            throw new IllegalArgumentException("The parameter site is required.");
        }
        if (path == null || path.isEmpty() || path.equals("/")) {
            throw new IllegalArgumentException("The parameter path is required.");
        }

        response.setContentType("text/plain");
        response.setHeader("Content-Disposition", "attachment; filename=\"playlist.txt\"");
        return playlistService.generate(site, path, includeSub).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public byte[] generate(@RequestBody GenerateRequest request, HttpServletResponse response) {
        return generate(request.getSite(), request.getPath(), request.isIncludeSub(), response);
    }
}
