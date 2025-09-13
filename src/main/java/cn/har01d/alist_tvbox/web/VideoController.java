package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.Video;
import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.service.VideoService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos")
public class VideoController {
    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostMapping("/{id}/rate")
    public void rate(@PathVariable Integer id, @RequestBody Video request) {
        videoService.rate(id, request.getRating());
    }

    @GetMapping("/{id}/rate")
    public Integer getRating(@PathVariable Integer id) {
        return videoService.getRating(id);
    }

    @PostMapping("/{id}/rename")
    public PlayUrl rename(@PathVariable Integer id, @RequestBody Video request) {
        return videoService.rename(id, request.getName());
    }

    @DeleteMapping("/{id}")
    public void remove(@PathVariable Integer id) {
        videoService.remove(id);
    }
}
