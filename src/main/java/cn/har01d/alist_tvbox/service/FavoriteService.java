package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.entity.PlayUrlRepository;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FavoriteService {
    private final PlayUrlRepository playUrlRepository;

    public FavoriteService(PlayUrlRepository playUrlRepository) {
        this.playUrlRepository = playUrlRepository;
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
}
