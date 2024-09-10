package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.bili.Sub;
import cn.har01d.alist_tvbox.dto.emby.EmbyInfo;
import cn.har01d.alist_tvbox.dto.emby.EmbyItem;
import cn.har01d.alist_tvbox.dto.emby.EmbyItems;
import cn.har01d.alist_tvbox.dto.emby.EmbyMediaSources;
import cn.har01d.alist_tvbox.entity.Emby;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URL;
import java.util.*;

@Slf4j
@Service
public class EmbyService {
    private final EmbyRepository embyRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SettingRepository settingRepository;
    private final Cache<Integer, EmbyInfo> cache = Caffeine.newBuilder().build();

    private final List<FilterValue> filters = Arrays.asList(
            new FilterValue("评分⬆️", "CommunityRating,SortName:Ascending"),
            new FilterValue("评分⬇️", "CommunityRating,SortName:Descending"),
            new FilterValue("发行日期⬆️", "PremiereDate,ProductionYear,SortName:Ascending"),
            new FilterValue("发行日期⬇️", "PremiereDate,ProductionYear,SortName:Descending"),
            new FilterValue("加入日期⬆️", "DateCreated,SortName:Ascending"),
            new FilterValue("加入日期⬇️", "DateCreated,SortName:Descending"),
            new FilterValue("名字⬆️", "SortName:Ascending"),
            new FilterValue("名字⬇️", "SortName:Descending"),
            new FilterValue("时长⬆️", "Runtime,SortName:Ascending"),
            new FilterValue("时长⬇️", "Runtime,SortName:Descending")
    );

    public EmbyService(EmbyRepository embyRepository, RestTemplateBuilder builder, ObjectMapper objectMapper, SettingRepository settingRepository) {
        this.embyRepository = embyRepository;
        restTemplate = builder
                .defaultHeader("User-Agent", Constants.USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
        this.settingRepository = settingRepository;
    }

    @PostConstruct
    public void init() {
        if (settingRepository.existsByName("fix_emby_order")) {
            return;
        }
        log.info("Fix Emby order.");
        int i = 1;
        List<Emby> list = embyRepository.findAll();
        for (Emby emby : list) {
            emby.setOrder(i++);
        }
        embyRepository.saveAll(list);
        settingRepository.save(new Setting("fix_emby_order", "true"));
    }

    public List<Emby> findAll() {
        List<Emby> list = new ArrayList<>(embyRepository.findAll());
        list.sort(Comparator.comparing(Emby::getOrder));
        return list;
    }

    public Emby getById(Integer id) {
        return embyRepository.findById(id).orElseThrow(() -> new NotFoundException("站点不存在"));
    }

    public Emby create(Emby dto) {
        validate(dto);

        if (embyRepository.existsByName(dto.getName())) {
            throw new BadRequestException("站点名字重复");
        }
        dto.setId(null);
        return embyRepository.save(dto);
    }

    public Emby update(int id, Emby dto) {
        validate(dto);
        Emby emby = embyRepository.findById(id).orElseThrow(() -> new NotFoundException("站点不存在"));
        Optional<Emby> other = embyRepository.findByName(dto.getName());
        if (other.isPresent() && other.get().getId() != id) {
            throw new BadRequestException("站点名字重复");
        }

        emby.setName(dto.getName());
        emby.setUrl(dto.getUrl());
        emby.setUsername(dto.getUsername());
        emby.setPassword(dto.getPassword());
        emby.setOrder(dto.getOrder());

        return embyRepository.save(emby);
    }

    public void delete(int id) {
        embyRepository.deleteById(id);
    }

    private void validate(Emby dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("站点名称不能为空");
        }

        if (StringUtils.isBlank(dto.getUrl())) {
            throw new BadRequestException("站点Url不能为空");
        }

        if (StringUtils.isBlank(dto.getUsername())) {
            throw new BadRequestException("用户名不能为空");
        }

        if (StringUtils.isBlank(dto.getPassword())) {
            throw new BadRequestException("密码不能为空");
        }

        try {
            new URL(dto.getUrl());
        } catch (Exception e) {
            throw new BadRequestException("站点地址不正确", e);
        }

        if (dto.getUrl().endsWith("/")) {
            dto.setUrl(dto.getUrl().substring(0, dto.getUrl().length() - 1));
        }
    }

    public MovieList home() {
        MovieList result = new MovieList();
        for (Emby emby : findAll()) {
            var info = getEmbyInfo(emby);
            if (info == null) {
                continue;
            }
            List<MovieDetail> list = new ArrayList<>();
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", getAuthorizationHeader(info));
            HttpEntity<Object> entity = new HttpEntity<>(null, headers);
            String url = emby.getUrl() + "/emby/Users/" + info.getUser().getId() + "/Items/Resume?Limit=12&Recursive=true&Fields=PrimaryImageAspectRatio,BasicSyncInfo,ProductionYear,CommunityRating&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb&EnableTotalRecordCount=false&MediaTypes=Video";
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();

            for (var item : response.getItems()) {
                var movie = getMovieDetail(item, emby);
                list.add(movie);
            }

            for (var parent : info.getViews()) {
                url = emby.getUrl() + "/emby/Users/" + info.getUser().getId() + "/Items/Latest?Limit=12&Fields=PrimaryImageAspectRatio,BasicSyncInfo,ProductionYear,CommunityRating&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb&ParentId=" + parent.getId();
                var items = restTemplate.exchange(url, HttpMethod.GET, entity, new ParameterizedTypeReference<List<EmbyItem>>() {
                }).getBody();
                for (var item : items) {
                    var movie = getMovieDetail(item, emby);
                    list.add(movie);
                }
            }

            result.setList(list);
            result.setTotal(list.size());
            result.setLimit(list.size());

            log.debug("home result: {}", result);
            return result;
        }
        return result;
    }

    private static MovieDetail getMovieDetail(EmbyItem item, Emby emby) {
        var movie = new MovieDetail();
        movie.setVod_id(emby.getId() + "-" + item.getId());
        if ("Episode".equals(item.getType())) {
            movie.setVod_name(item.getSeriesName());
        } else {
            movie.setVod_name(item.getName());
        }

        if (item.getImageTags() != null && item.getImageTags().getPrimary() != null) {
            movie.setVod_pic(emby.getUrl() + "/emby/Items/" + item.getId() + "/Images/Primary?maxWidth=400&tag=" + item.getImageTags().getPrimary() + "&quality=90");
        }
        movie.setVod_director(emby.getName());
        movie.setVod_remarks(Objects.toString(item.getRating(), null));
        movie.setVod_year(Objects.toString(item.getYear(), null));
        return movie;
    }

    private static String getAuthorizationHeader(EmbyInfo info) {
        return "Emby UserId=\"" + info.getUser().getId() + "\", Client=\"Emby Web\", Device=\"Chrome\", DeviceId=\"4310d84d-66a2-4f91-8d11-6627110be71c\", Version=\"4.7.5.0\", Token=\"" + info.getAccessToken() + "\"";
    }

    public MovieList detail(String tid) throws JsonProcessingException {
        String[] parts = tid.split("-");
        Emby emby = embyRepository.findById(Integer.parseInt(parts[0])).orElseThrow(() -> new NotFoundException("站点不存在"));
        var info = getEmbyInfo(emby);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getAuthorizationHeader(info));
        HttpEntity<Object> entity = new HttpEntity<>(null, headers);
        String url = emby.getUrl() + "/emby/Users/" + info.getUser().getId() + "/Items/" + parts[1];
        var item = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItem.class).getBody();

        MovieList result = new MovieList();
        MovieDetail movie = getMovieDetail(item, emby);
        movie.setVod_content(item.getOverview());
        movie.setVod_play_from(emby.getName());
        movie.setVod_play_url(movie.getVod_id());
        if ("Episode".equals(item.getType()) || "Series".equals(item.getType())) {
            List<EmbyItem> list = getAll(emby, info, item.getSeriesId() == null ? item.getId() : item.getSeriesId());
            List<String> names = new ArrayList<>();
            List<String> playUrl = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            String name = "";
            for (EmbyItem video : list) {
                String sname = video.getSeasonName().replace("未知季", "剧集");
                if (!name.equals(sname)) {
                    if (!urls.isEmpty()) {
                        names.add(name);
                        playUrl.add(String.join("#", urls));
                    }
                    name = sname;
                    urls = new ArrayList<>();
                }
                if (video.getName().equals("第 " + video.getIndexNumber() + " 集")) {
                    urls.add(video.getName() + "$" + emby.getId() + "-" + video.getId());
                } else {
                    urls.add(video.getIndexNumber() + "." + video.getName() + "$" + emby.getId() + "-" + video.getId());
                }
            }
            if (!urls.isEmpty()) {
                names.add(name);
                playUrl.add(String.join("#", urls));
            }
            movie.setVod_play_from(String.join("$$$", names));
            movie.setVod_play_url(String.join("$$$", playUrl));
        }
        result.getList().add(movie);

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private List<EmbyItem> getAll(Emby emby, EmbyInfo info, String sid) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getAuthorizationHeader(info));
        HttpEntity<Object> entity = new HttpEntity<>(null, headers);
        String url = emby.getUrl() + "/emby/Users/" + info.getUser().getId() + "/Items?ParentId=" + sid + "&Filters=IsNotFolder&Recursive=true&Limit=2000&Fields=Chapters,ProductionYear,PremiereDate&ExcludeLocationTypes=Virtual&EnableTotalRecordCount=false&CollapseBoxSetItems=false";
        var items = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();
        return items.getItems();
    }

    public MovieList search(String wd) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        for (Emby emby : findAll()) {
            var info = getEmbyInfo(emby);
            if (info == null) {
                continue;
            }
            list.addAll(search(emby, info, wd, "Movie"));
            list.addAll(search(emby, info, wd, "Series"));
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        return result;
    }

    private List<MovieDetail> search(Emby emby, EmbyInfo info, String wd, String type) {
        List<MovieDetail> list = new ArrayList<>();
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getAuthorizationHeader(info));
        HttpEntity<Object> entity = new HttpEntity<>(null, headers);
        String url = emby.getUrl() + "/emby/Users/" + info.getUser().getId() + "/Items?IncludePeople=false&IncludeMedia=true&IncludeGenres=false&IncludeStudios=false&IncludeArtists=false&IncludeItemTypes=" + type + "&Limit=30&Fields=PrimaryImageAspectRatio,BasicSyncInfo,ProductionYear,CommunityRating&Recursive=true&EnableTotalRecordCount=false&ImageTypeLimit=1&searchTerm=" + wd;
        var response = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();
        for (var item : response.getItems()) {
            var movie = getSearchDetail(item, emby);
            list.add(movie);
        }
        return list;
    }

    private static MovieDetail getSearchDetail(EmbyItem item, Emby emby) {
        var movie = new MovieDetail();
        movie.setVod_id(emby.getId() + "-" + item.getId());
        if ("Episode".equals(item.getType())) {
            movie.setVod_name(item.getSeriesName());
        } else {
            movie.setVod_name(item.getName());
        }

        if (item.getImageTags() != null && item.getImageTags().getPrimary() != null) {
            movie.setVod_pic(emby.getUrl() + "/emby/Items/" + item.getId() + "/Images/Primary?maxWidth=400&tag=" + item.getImageTags().getPrimary() + "&quality=90");
        }
        movie.setVod_remarks(emby.getName() + " " + Objects.toString(item.getRating(), ""));
        movie.setVod_year(Objects.toString(item.getYear(), null));
        return movie;
    }

    public MovieList list(String id, String sort, Integer pg) {
        String[] parts = id.split("-");
        if (sort == null) {
            sort = "DateCreated,SortName:Descending";
        }
        String[] sorts = sort.split(":");
        Emby emby = embyRepository.findById(Integer.parseInt(parts[0])).orElseThrow(() -> new NotFoundException("站点不存在"));
        var info = getEmbyInfo(emby);
        var view = info.getViews().get(Integer.parseInt(parts[1]));
        String type = "";
        if (view.getCollectionType().equals("movies")) {
            type = "Movie";
        } else if (view.getCollectionType().equals("tvshows")) {
            type = "Series";
        }

        int start = 0;
        int size = 60;
        if (pg != null) {
            start = (pg - 1) * size;
        }
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getAuthorizationHeader(info));
        HttpEntity<Object> entity = new HttpEntity<>(null, headers);
        String url = emby.getUrl() + "/emby/Users/" + info.getUser().getId() + "/Items?SortBy=" + sorts[0] + "&SortOrder=" + sorts[1] + "&IncludeItemTypes=" + type + "&Recursive=true&Fields=BasicSyncInfo,PrimaryImageAspectRatio,ProductionYear,CommunityRating&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb&StartIndex=" + start + "&Limit=" + size + "&ParentId=" + view.getId();
        var response = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();
        for (var item : response.getItems()) {
            var movie = getMovieDetail(item, emby);
            list.add(movie);
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        result.setPagecount(response.getTotal() / size + 1);

        log.debug("list result: {}", result);
        return result;
    }

    public CategoryList category() {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        for (Emby emby : findAll()) {
            var info = getEmbyInfo(emby);
            if (info == null) {
                continue;
            }
            int i = 0;
            for (EmbyItem item : info.getViews()) {
                var category = new Category();
                category.setType_id(emby.getId() + "-" + i++);
                category.setType_name(emby.getName() + ":" + item.getName());
                category.setType_flag(0);

                result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
                list.add(category);
            }
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    public Object play(String id) throws JsonProcessingException {
        String[] parts = id.split("-");
        Emby emby = embyRepository.findById(Integer.parseInt(parts[0])).orElseThrow(() -> new NotFoundException("站点不存在"));
        var info = getEmbyInfo(emby);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getAuthorizationHeader(info));
        String body = "{\"DeviceProfile\":{\"SubtitleProfiles\":[{\"Method\":\"Embed\",\"Format\":\"ass\"},{\"Format\":\"ssa\",\"Method\":\"Embed\"},{\"Format\":\"subrip\",\"Method\":\"Embed\"},{\"Format\":\"sub\",\"Method\":\"Embed\"},{\"Method\":\"Embed\",\"Format\":\"pgssub\"},{\"Format\":\"subrip\",\"Method\":\"External\"},{\"Method\":\"External\",\"Format\":\"sub\"},{\"Method\":\"External\",\"Format\":\"ass\"},{\"Format\":\"ssa\",\"Method\":\"External\"},{\"Method\":\"External\",\"Format\":\"vtt\"},{\"Method\":\"External\",\"Format\":\"ass\"},{\"Format\":\"ssa\",\"Method\":\"External\"}],\"CodecProfiles\":[{\"Codec\":\"h264\",\"Type\":\"Video\",\"ApplyConditions\":[{\"Property\":\"IsAnamorphic\",\"Value\":\"true\",\"Condition\":\"NotEquals\",\"IsRequired\":false},{\"IsRequired\":false,\"Value\":\"high|main|baseline|constrained baseline\",\"Condition\":\"EqualsAny\",\"Property\":\"VideoProfile\"},{\"IsRequired\":false,\"Value\":\"80\",\"Condition\":\"LessThanEqual\",\"Property\":\"VideoLevel\"},{\"IsRequired\":false,\"Value\":\"true\",\"Condition\":\"NotEquals\",\"Property\":\"IsInterlaced\"}]},{\"Codec\":\"hevc\",\"ApplyConditions\":[{\"Property\":\"IsAnamorphic\",\"Value\":\"true\",\"Condition\":\"NotEquals\",\"IsRequired\":false},{\"IsRequired\":false,\"Value\":\"high|main|main 10\",\"Condition\":\"EqualsAny\",\"Property\":\"VideoProfile\"},{\"Property\":\"VideoLevel\",\"Value\":\"175\",\"Condition\":\"LessThanEqual\",\"IsRequired\":false},{\"IsRequired\":false,\"Value\":\"true\",\"Condition\":\"NotEquals\",\"Property\":\"IsInterlaced\"}],\"Type\":\"Video\"}],\"MaxStreamingBitrate\":40000000,\"TranscodingProfiles\":[{\"Container\":\"ts\",\"AudioCodec\":\"aac,mp3,wav,ac3,eac3,flac,opus\",\"VideoCodec\":\"hevc,h264,mpeg4\",\"BreakOnNonKeyFrames\":true,\"Type\":\"Video\",\"MaxAudioChannels\":\"6\",\"Protocol\":\"hls\",\"Context\":\"Streaming\",\"MinSegments\":2}],\"DirectPlayProfiles\":[{\"Container\":\"mov,mp4,mkv,hls,webm\",\"Type\":\"Video\",\"VideoCodec\":\"h264,hevc,dvhe,dvh1,h264,hevc,hev1,mpeg4,vp9\",\"AudioCodec\":\"aac,mp3,wav,ac3,eac3,flac,truehd,dts,dca,opus,pcm,pcm_s24le\"}],\"ResponseProfiles\":[{\"MimeType\":\"video/mp4\",\"Type\":\"Video\",\"Container\":\"m4v\"}],\"ContainerProfiles\":[],\"MusicStreamingTranscodingBitrate\":40000000,\"MaxStaticBitrate\":40000000}}";
        HttpEntity<Object> entity = new HttpEntity<>(objectMapper.readTree(body), headers);
        String url = emby.getUrl() + "/emby/Items/" + parts[1] + "/PlaybackInfo?IsPlayback=false&AutoOpenLiveStream=false&StartTimeTicks=0&MaxStreamingBitrate=2147483647&UserId=" + info.getUser().getId();
        var media = restTemplate.exchange(url, HttpMethod.POST, entity, EmbyMediaSources.class).getBody();

        List<String> urls = new ArrayList<>();
        for (var source : media.getItems()) {
            urls.add(source.getName());
            urls.add(emby.getUrl() + source.getUrl());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("url", urls);
        result.put("subs", getSubtitles(emby, media.getItems().get(0)));
        result.put("header", "{\"User-Agent\": \"" + Constants.USER_AGENT + "\"}");
        result.put("parse", 0);
        log.debug("{}", result);
        return result;
    }

    private List<Sub> getSubtitles(Emby emby, EmbyMediaSources.MediaSources mediaSources) {
        List<Sub> list = new ArrayList<>();
        for (EmbyMediaSources.MediaStreams stream : mediaSources.getMediaStreams()) {
            if ("Subtitle".equals(stream.getType()) && stream.getUrl() != null) {
                Sub sub = new Sub();
                sub.setName(stream.getTitle());
                sub.setLang(stream.getLanguage());
                if ("ass".equals(stream.getCodec())) {
                    sub.setFormat("text/x-ssa");
                } else {
                    sub.setFormat("application/x-subrip");
                }
                sub.setUrl(emby.getUrl() + stream.getUrl());
                list.add(sub);
            }
        }
        return list;
    }

    private EmbyInfo getEmbyInfo(Emby emby) {
        EmbyInfo result = cache.getIfPresent(emby.getId());
        if (result != null) {
            return result;
        }
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("Username", emby.getUsername());
            body.add("Pw", emby.getPassword());
            log.debug("get Emby info: {} {} {} {}", emby.getId(), emby.getName(), emby.getUrl(), emby.getUsername());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            EmbyInfo info = restTemplate.exchange(emby.getUrl() + "/emby/Users/AuthenticateByName?X-Emby-Client=Emby%20Web&X-Emby-Device-Name=Google%20Chrome%20Linux&X-Emby-Device-Id=4310d84d-66a2-4f91-8d11-6627110be71c&X-Emby-Client-Version=4.7.5.0", HttpMethod.POST, entity, EmbyInfo.class).getBody();
            cache.put(emby.getId(), info);

            headers.add("Authorization", getAuthorizationHeader(info));
            entity = new HttpEntity<>(null, headers);
            String url = emby.getUrl() + "/emby/Users/" + info.getUser().getId() + "/Views";
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();
            info.setViews(response.getItems());

            return info;
        } catch (Exception e) {
            log.error("Get Emby info failed.", e);
        }
        return null;
    }
}
