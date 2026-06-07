package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccountChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TgPrivateChannelService {
    public static final String CHANNEL_IDS_KEY = "tg_private_channel_ids";

    private final TgProviderClient tgProviderClient;
    private final SettingRepository settingRepository;
    private final AppProperties appProperties;

    public TgPrivateChannelService(TgProviderClient tgProviderClient,
                                   SettingRepository settingRepository,
                                   AppProperties appProperties) {
        this.tgProviderClient = tgProviderClient;
        this.settingRepository = settingRepository;
        this.appProperties = appProperties;
    }

    public List<TgPrivateChannel> channels() {
        Set<Long> enabled = enabledChannelIds();
        return tgProviderClient.channels().stream()
                .map(channel -> TgPrivateChannel.from(channel, enabled.contains(channel.id())))
                .toList();
    }

    public List<TgPrivateChannel> saveChannels(TgPrivateChannelSelectionRequest request) {
        settingRepository.save(new Setting(CHANNEL_IDS_KEY, joinIds(normalize(request == null ? null : request.channelIds()))));
        return channels();
    }

    public TgProviderSyncResponse syncChannels(TgPrivateChannelSelectionRequest request) {
        List<Long> ids = normalize(request == null || request.channelIds() == null || request.channelIds().isEmpty()
                ? new ArrayList<>(enabledChannelIds())
                : request.channelIds());
        if (ids.isEmpty()) {
            return TgProviderSyncResponse.empty();
        }
        return tgProviderClient.syncChannels(ids);
    }

    public List<TgProviderAccountChannelSyncResponse> syncAccountChannels() {
        return tgProviderClient.accounts().stream()
                .map(account -> tgProviderClient.syncAccountChannels(account.id()))
                .toList();
    }

    public List<Message> search(String keyword, int limit) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        return collectMessages(enabledChannelIds(), limit, channelId -> tgProviderClient.searchMessages(keyword, limit, channelId));
    }

    public MovieList searchMovies(String keyword, int limit) {
        return toMovieList(search(keyword, limit));
    }

    public MovieList list(String type, int page) {
        if ("0".equals(type)) {
            return latestMovies(null, 5);
        }
        if (StringUtils.startsWith(type, "type:")) {
            return latestMovies(type.substring(5), 100);
        }
        Long channelId = parseId(type);
        if (channelId == null) {
            return new MovieList();
        }
        return latestMoviesByChannel(channelId, 100);
    }

    public MovieList latestMovies(String driverType, int limit) {
        List<Message> messages = collectMessages(enabledChannelIds(), limit, channelId -> tgProviderClient.latestMessages(limit, channelId));
        if (StringUtils.isNotBlank(driverType)) {
            messages = messages.stream().filter(message -> driverType.equals(message.getType())).toList();
        }
        return toMovieList(messages);
    }

    public MovieList latestMoviesByChannel(long channelId, int limit) {
        if (!enabledChannelIds().contains(channelId)) {
            return new MovieList();
        }
        return toMovieList(collectMessages(List.of(channelId), limit, id -> tgProviderClient.latestMessages(limit, id)));
    }

    public CategoryList category() {
        CategoryList result = new CategoryList();
        List<Category> categories = new ArrayList<>();
        for (String type : appProperties.getTgDrivers()) {
            Category category = new Category();
            category.setType_id("type:" + type);
            category.setType_name(getTypeName(type));
            category.setType_flag(0);
            categories.add(category);
        }
        for (TgPrivateChannel channel : channels()) {
            if (!channel.enabled()) {
                continue;
            }
            Category category = new Category();
            category.setType_id(String.valueOf(channel.id()));
            category.setType_name(StringUtils.defaultIfBlank(channel.title(), channel.username()));
            category.setType_flag(0);
            categories.add(category);
        }
        result.setCategories(categories);
        result.setTotal(categories.size());
        result.setLimit(categories.size());
        return result;
    }

    private List<Message> collectMessages(Collection<Long> channelIds, int limit, ChannelMessageLoader loader) {
        if (channelIds == null || channelIds.isEmpty()) {
            return List.of();
        }
        return channelIds.stream()
                .flatMap(channelId -> {
                    try {
                        return loader.load(channelId).stream();
                    } catch (RuntimeException e) {
                        log.warn("tg-provider private channel request failed: {}", channelId, e);
                        return List.<Message>of().stream();
                    }
                })
                .filter(message -> StringUtils.isNotBlank(message.getType()))
                .filter(message -> appProperties.getTgDrivers().isEmpty() || appProperties.getTgDrivers().contains(message.getType()))
                .sorted(Comparator.comparing(Message::getTime).reversed())
                .distinct()
                .limit(limit)
                .toList();
    }

    private MovieList toMovieList(List<Message> messages) {
        MovieList result = new MovieList();
        List<MovieDetail> list = messages.stream().map(this::toMovieDetail).toList();
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());
        return result;
    }

    private MovieDetail toMovieDetail(Message message) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(encodeUrl(message.getLink()));
        movieDetail.setVod_name(message.getName());
        movieDetail.setVod_pic(StringUtils.defaultIfBlank(message.getCover(), getPic(message.getType())));
        movieDetail.setVod_remarks(getTypeName(message.getType()));
        return movieDetail;
    }

    private Set<Long> enabledChannelIds() {
        return new LinkedHashSet<>(normalize(settingRepository.findById(CHANNEL_IDS_KEY)
                .map(Setting::getValue)
                .map(value -> List.of(value.split(",")))
                .orElse(List.of())
                .stream()
                .map(this::parseId)
                .toList()));
    }

    private List<Long> normalize(Collection<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .sorted()
                .toList();
    }

    private String joinIds(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private Long parseId(String value) {
        try {
            return StringUtils.isBlank(value) ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
            case "12" -> "光鸭";
            case "magnet" -> "磁力";
            case "ed2k" -> "ED2K";
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
            case "12" -> getUrl("/guangya.webp");
            default -> null;
        };
    }

    private String getUrl(String path) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http")
                .replacePath(path)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    @FunctionalInterface
    private interface ChannelMessageLoader {
        List<Message> load(Long channelId);
    }
}
