package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.entity.PlayUrlRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VideoService {
    private final PlayUrlRepository playUrlRepository;
    private final AListService aListService;
    private final SiteService siteService;

    public VideoService(PlayUrlRepository playUrlRepository, AListService aListService, SiteService siteService) {
        this.playUrlRepository = playUrlRepository;
        this.aListService = aListService;
        this.siteService = siteService;
    }

    public void rate(int id, Integer rating) {
        PlayUrl playUrl = playUrlRepository.findById(id).orElseThrow(() -> new NotFoundException("Play url not found"));
        if (rating == 0) {
            rating = null;
        }
        playUrl.setRating(rating);
        playUrlRepository.save(playUrl);
        log.debug("rate playUrl: {}", playUrl);
    }

    public Integer getRating(int id) {
        PlayUrl playUrl = playUrlRepository.findById(id).orElseThrow(() -> new NotFoundException("Play url not found"));
        return playUrl.getRating();
    }

    public PlayUrl rename(int id, String name) {
        PlayUrl playUrl = playUrlRepository.findById(id).orElseThrow(() -> new NotFoundException("Play url not found"));
        Site site = siteService.getById(playUrl.getSite());
        String path = playUrl.getPath();
        aListService.rename(site, path, name);
        int index = path.lastIndexOf("/");
        String dir = path.substring(0, index);
        playUrl.setPath(dir + "/" + name);
        return playUrlRepository.save(playUrl);
    }

    public void remove(int id) {
        PlayUrl playUrl = playUrlRepository.findById(id).orElseThrow(() -> new NotFoundException("Play url not found"));
        Site site = siteService.getById(playUrl.getSite());
        aListService.remove(site, playUrl.getPath());
        playUrlRepository.delete(playUrl);
    }
}
