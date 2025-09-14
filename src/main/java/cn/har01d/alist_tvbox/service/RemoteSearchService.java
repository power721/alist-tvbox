package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.pansou.PanSouSearchResponse;
import cn.har01d.alist_tvbox.dto.pansou.SearchRequest;
import cn.har01d.alist_tvbox.dto.pansou.SearchResult;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.TelegramChannel;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.ObjectNode;

@Slf4j
@Service
public class RemoteSearchService {
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TelegramChannelRepository telegramChannelRepository;
    private final ShareService shareService;
    private final TvBoxService tvBoxService;

    public RemoteSearchService(AppProperties appProperties,
                               RestTemplateBuilder restTemplateBuilder,
                               ObjectMapper objectMapper,
                               TelegramChannelRepository telegramChannelRepository,
                               ShareService shareService,
                               TvBoxService tvBoxService) {
        this.appProperties = appProperties;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = objectMapper;
        this.telegramChannelRepository = telegramChannelRepository;
        this.shareService = shareService;
        this.tvBoxService = tvBoxService;
    }

    public ObjectNode getPanSouInfo() {
        return restTemplate.getForObject(appProperties.getPanSouUrl() + "/api/health", ObjectNode.class);
    }

    public MovieList pansou(String keyword) {
        var result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        List<String> channels = telegramChannelRepository.findByEnabledTrue(Sort.by("order")).stream()
                .filter(TelegramChannel::isValid)
                .map(TelegramChannel::getUsername)
                .toList();

        var messages = search(keyword, channels);
        for (var message : messages) {
            var movieDetail = new MovieDetail();
            movieDetail.setVod_id(encodeUrl(message.getLink()));
            movieDetail.setVod_name(message.getName());
            if (StringUtils.isBlank(message.getCover())) {
                movieDetail.setVod_pic(getPic(message.getType()));
            } else {
                movieDetail.setVod_pic(message.getCover());
            }
            movieDetail.setVod_remarks(getTypeName(message.getType()));
            list.add(movieDetail);
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        log.info("Search {} get {} results from PanSou.", keyword, result.getTotal());
        return result;
    }

    public MovieList detail(String tid) {
        var share = new ShareLink();
        share.setLink(tid);
        String path = shareService.add(share);

        return tvBoxService.getDetail("", "1$" + path + "/~playlist");
    }

    public List<Message> search(String keyword, List<String> channels) {
        var request = new SearchRequest(keyword, channels, appProperties.getPanSouSource());
        if (!CollectionUtils.isEmpty(appProperties.getPanSouPlugins())) {
            request.setPlugins(appProperties.getPanSouPlugins());
        }
        String url = appProperties.getPanSouUrl() + "/api/search";
        log.debug("search request: {} {}", url, request);
        try {
            var json = restTemplate.postForObject(url, request, String.class);
            var response = objectMapper.readValue(json, PanSouSearchResponse.class);
            List<SearchResult> results = response.getData().getResults();
            List<Message> messages = new ArrayList<>();
            if (results == null) {
                return messages;
            }
            List<String> tgDrivers = appProperties.getTgDrivers();
            for (var result : results) {
                if (result.getLinks() == null) {
                    continue;
                }
                for (var link : result.getLinks()) {
                    String type = getTypeName(link.getType());
                    if (type == null) {
                        continue;
                    }
                    var message = new Message(result, link);
                    if (tgDrivers.isEmpty() || tgDrivers.contains(message.getType())) {
                        messages.add(message);
                    }
                }
            }
            return messages.stream().sorted(comparator()).distinct().toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Comparator<Message> comparator() {
        Comparator<Message> type = Comparator.comparing(a -> appProperties.getTgDriverOrder().indexOf(a.getType()));
        return switch (appProperties.getTgSortField()) {
            case "type" -> type.thenComparing(Comparator.comparing(Message::getTime).reversed());
            case "name" -> Comparator.comparing(Message::getName);
            case "channel" ->
                    Comparator.comparing(Message::getChannel).thenComparing(Comparator.comparing(Message::getTime).reversed());
            default -> Comparator.comparing(Message::getTime).reversed();
        };
    }

    public String searchPg(String keyword, String username, String encode) {
        List<String> channels = Arrays.stream(username.split(",")).map(e -> e.split("\\|")[0]).toList();
        return searchPg(keyword, channels, encode);
    }

    public String searchPg(String keyword, List<String> channels, String encode) {
        log.info("[PanSou] search {} from channels {}", keyword, channels);

        var result = search(keyword, channels);

        log.info("[PanSou] get {} results", result.size());
        return result.stream()
                .map(Message::toPgString)
                .map(e -> {
                    if ("1".equals(encode)) {
                        return Base64.getEncoder().encodeToString(e.getBytes());
                    }
                    return e;
                })
                .collect(Collectors.joining("\n"));
    }

    private String encodeUrl(String url) {
        return URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String getTypeName(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> "阿里";
            case "1" -> "PikPak";
            case "2" -> "迅雷";
            case "3" -> "123";
            case "5" -> "夸克";
            case "6" -> "移动";
            case "7" -> "UC";
            case "8" -> "115";
            case "9" -> "天翼";
            case "10" -> "百度";
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
            case "0" -> getUrl("/ali.jpg");
            case "1" -> getUrl("/pikpak.jpg");
            case "2" -> getUrl("/thunder.png");
            case "3" -> getUrl("/123.png");
            case "5" -> getUrl("/quark.png");
            case "7" -> getUrl("/uc.png");
            case "8" -> getUrl("/115.jpg");
            case "9" -> getUrl("/189.png");
            case "6" -> getUrl("/139.jpg");
            case "10" -> getUrl("/baidu.jpg");
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
