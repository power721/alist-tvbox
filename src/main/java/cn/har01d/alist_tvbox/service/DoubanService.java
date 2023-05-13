package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.DoubanData;
import cn.har01d.alist_tvbox.dto.DoubanDto;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DoubanService {

    private static final Pattern DB_URL = Pattern.compile("https://movie.douban.com/subject/(\\d+)");

    private final RestTemplate restTemplate;
    LoadingCache<String, DoubanData> cache = Caffeine.newBuilder()
            .maximumSize(100)
            .build(this::load);

    public DoubanService(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    public DoubanData getDataFromUrl(String url) {
        if (StringUtils.isNotEmpty(url)) {
            Matcher matcher = DB_URL.matcher(url);
            if (matcher.find()) {
                String id = matcher.group(1);
                try {
                    return getById(id);
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        }

        return null;
    }

    public DoubanData getById(String id) {
        return cache.get(id);
    }

    public DoubanData load(String id) {
        String url = "https://api.wmdb.tv/movie/api?id=" + id;
        log.info("get douban info from {}", url);
        DoubanDto dto = restTemplate.getForObject(url, DoubanDto.class);
        if (dto == null || dto.getData() == null || dto.getData().isEmpty()) {
            return null;
        }

        DoubanData data = dto.getData().get(0);
        data.setEpisodes(dto.getEpisodes());
        data.setYear(dto.getYear());
        log.info("douban info: {}", data);
        return data;
    }
}
