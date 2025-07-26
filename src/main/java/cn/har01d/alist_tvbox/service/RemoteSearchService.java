package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.pansou.PansouSearchResponse;
import cn.har01d.alist_tvbox.dto.pansou.SearchRequest;
import cn.har01d.alist_tvbox.entity.TelegramChannel;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class RemoteSearchService {
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final TelegramChannelRepository telegramChannelRepository;
    private final ShareService shareService;
    private final TvBoxService tvBoxService;

    public RemoteSearchService(AppProperties appProperties,
                               RestTemplateBuilder restTemplateBuilder,
                               TelegramChannelRepository telegramChannelRepository,
                               ShareService shareService,
                               TvBoxService tvBoxService) {
        this.appProperties = appProperties;
        this.restTemplate = restTemplateBuilder.build();
        this.telegramChannelRepository = telegramChannelRepository;
        this.shareService = shareService;
        this.tvBoxService = tvBoxService;
    }

    public MovieList pansou(String keyword) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        List<String> channels = telegramChannelRepository.findByEnabledTrue(Sort.by("order")).stream()
                .filter(TelegramChannel::isValid)
                .map(TelegramChannel::getUsername)
                .toList();

        var request = new SearchRequest(keyword, channels, appProperties.getPanSouSource());
        var response = restTemplate.postForObject(appProperties.getPanSouUrl() + "/api/search", request, PansouSearchResponse.class);
        for (var message : response.getData().getResults()) {
            for (var link : message.getLinks()) {
                String type = getTypeName(link.getType());
                if (type == null) {
                    continue;
                }
                MovieDetail movieDetail = new MovieDetail();
                movieDetail.setVod_id(encodeUrl(link.getUrl()));
                movieDetail.setVod_name(message.getTitle());
                movieDetail.setVod_pic(getPic(link.getType()));
                movieDetail.setVod_remarks(type);
                list.add(movieDetail);
            }
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        log.info("Search {} get {} results from PanSou.", keyword, result.getTotal());
        return result;
    }

    public MovieList detail(String tid) {
        ShareLink share = new ShareLink();
        share.setLink(tid);
        String path = shareService.add(share);

        return tvBoxService.getDetail("", "1$" + path + "/~playlist");
    }

    private String encodeUrl(String url) {
        return URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String getTypeName(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "aliyun" -> "阿里";
            case "pikpak" -> "PikPak";
            case "xunlei" -> "迅雷";
            case "123" -> "123";
            case "quark" -> "夸克";
            case "mobile" -> "移动";
            case "uc" -> "UC";
            case "115" -> "115";
            case "tianyi" -> "天翼";
            case "baidu" -> "百度";
            default -> null;
        };
    }

    private String getPic(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "aliyun" -> getUrl("/ali.jpg");
            case "pikpak" -> getUrl("/pikpak.jpg");
            case "xunlei" -> getUrl("/thunder.png");
            case "123" -> getUrl("/123.png");
            case "quark" -> getUrl("/quark.png");
            case "uc" -> getUrl("/uc.png");
            case "115" -> getUrl("/115.jpg");
            case "tianyi" -> getUrl("/189.png");
            case "mobile" -> getUrl("/139.jpg");
            case "baidu" -> getUrl("/baidu.jpg");
            default -> null;
        };
    }

    private String getUrl(String path) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath(path)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

}
