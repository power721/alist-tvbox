package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.bili.Sub;
import cn.har01d.alist_tvbox.dto.emby.EmbyInfo;
import cn.har01d.alist_tvbox.dto.emby.EmbyItem;
import cn.har01d.alist_tvbox.dto.emby.EmbyItems;
import cn.har01d.alist_tvbox.dto.emby.EmbyMediaSources;
import cn.har01d.alist_tvbox.entity.Jellyfin;
import cn.har01d.alist_tvbox.entity.JellyfinRepository;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static cn.har01d.alist_tvbox.util.Constants.FOLDER;

@Slf4j
@Service
public class JellyfinService {

    private static final String PLAY = """
            {
              "UserId": "USER_ID",
              "StartTimeTicks": 0,
              "IsPlayback": true,
              "AutoOpenLiveStream": true,
              "AudioStreamIndex": "1",
              "SubtitleStreamIndex": "-1",
              "MediaSourceId": "MEDIA_ID",
              "MaxStreamingBitrate": 2147483647,
              "AlwaysBurnInSubtitleWhenTranscoding": false,
              "DeviceProfile": {
                "MaxStreamingBitrate": 120000000,
                "MaxStaticBitrate": 100000000,
                "MusicStreamingTranscodingBitrate": 384000,
                "DirectPlayProfiles": [
                  {
                    "Container": "webm",
                    "Type": "Video",
                    "VideoCodec": "vp8,vp9,av1",
                    "AudioCodec": "vorbis,opus"
                  },
                  {
                    "Container": "mp4,m4v",
                    "Type": "Video",
                    "VideoCodec": "h264,vp9,av1",
                    "AudioCodec": "aac,mp3,mp2,opus,flac,vorbis"
                  },
                  {
                    "Container": "mov",
                    "Type": "Video",
                    "VideoCodec": "h264",
                    "AudioCodec": "aac,mp3,mp2,opus,flac,vorbis"
                  },
                  {
                    "Container": "opus",
                    "Type": "Audio"
                  },
                  {
                    "Container": "webm",
                    "AudioCodec": "opus",
                    "Type": "Audio"
                  },
                  {
                    "Container": "ts",
                    "AudioCodec": "mp3",
                    "Type": "Audio"
                  },
                  {
                    "Container": "mp3",
                    "Type": "Audio"
                  },
                  {
                    "Container": "aac",
                    "Type": "Audio"
                  },
                  {
                    "Container": "m4a",
                    "AudioCodec": "aac",
                    "Type": "Audio"
                  },
                  {
                    "Container": "m4b",
                    "AudioCodec": "aac",
                    "Type": "Audio"
                  },
                  {
                    "Container": "flac",
                    "Type": "Audio"
                  },
                  {
                    "Container": "webma",
                    "Type": "Audio"
                  },
                  {
                    "Container": "webm",
                    "AudioCodec": "webma",
                    "Type": "Audio"
                  },
                  {
                    "Container": "wav",
                    "Type": "Audio"
                  },
                  {
                    "Container": "ogg",
                    "Type": "Audio"
                  },
                  {
                    "Container": "hls",
                    "Type": "Video",
                    "VideoCodec": "av1,h264,vp9",
                    "AudioCodec": "aac,mp2,opus,flac"
                  },
                  {
                    "Container": "hls",
                    "Type": "Video",
                    "VideoCodec": "h264",
                    "AudioCodec": "aac,mp3,mp2"
                  }
                ],
                "TranscodingProfiles": [
                  {
                    "Container": "mp4",
                    "Type": "Audio",
                    "AudioCodec": "aac",
                    "Context": "Streaming",
                    "Protocol": "hls",
                    "MaxAudioChannels": "2",
                    "MinSegments": "1",
                    "BreakOnNonKeyFrames": true,
                    "EnableAudioVbrEncoding": true
                  },
                  {
                    "Container": "aac",
                    "Type": "Audio",
                    "AudioCodec": "aac",
                    "Context": "Streaming",
                    "Protocol": "http",
                    "MaxAudioChannels": "2"
                  },
                  {
                    "Container": "mp3",
                    "Type": "Audio",
                    "AudioCodec": "mp3",
                    "Context": "Streaming",
                    "Protocol": "http",
                    "MaxAudioChannels": "2"
                  },
                  {
                    "Container": "opus",
                    "Type": "Audio",
                    "AudioCodec": "opus",
                    "Context": "Streaming",
                    "Protocol": "http",
                    "MaxAudioChannels": "2"
                  },
                  {
                    "Container": "wav",
                    "Type": "Audio",
                    "AudioCodec": "wav",
                    "Context": "Streaming",
                    "Protocol": "http",
                    "MaxAudioChannels": "2"
                  },
                  {
                    "Container": "opus",
                    "Type": "Audio",
                    "AudioCodec": "opus",
                    "Context": "Static",
                    "Protocol": "http",
                    "MaxAudioChannels": "2"
                  },
                  {
                    "Container": "mp3",
                    "Type": "Audio",
                    "AudioCodec": "mp3",
                    "Context": "Static",
                    "Protocol": "http",
                    "MaxAudioChannels": "2"
                  },
                  {
                    "Container": "aac",
                    "Type": "Audio",
                    "AudioCodec": "aac",
                    "Context": "Static",
                    "Protocol": "http",
                    "MaxAudioChannels": "2"
                  },
                  {
                    "Container": "wav",
                    "Type": "Audio",
                    "AudioCodec": "wav",
                    "Context": "Static",
                    "Protocol": "http",
                    "MaxAudioChannels": "2"
                  },
                  {
                    "Container": "mp4",
                    "Type": "Video",
                    "AudioCodec": "aac,mp2,opus,flac",
                    "VideoCodec": "av1,h264,vp9",
                    "Context": "Streaming",
                    "Protocol": "hls",
                    "MaxAudioChannels": "2",
                    "MinSegments": "1",
                    "BreakOnNonKeyFrames": true
                  },
                  {
                    "Container": "ts",
                    "Type": "Video",
                    "AudioCodec": "aac,mp3,mp2",
                    "VideoCodec": "h264",
                    "Context": "Streaming",
                    "Protocol": "hls",
                    "MaxAudioChannels": "2",
                    "MinSegments": "1",
                    "BreakOnNonKeyFrames": true
                  }
                ],
                "ContainerProfiles": [],
                "CodecProfiles": [
                  {
                    "Type": "VideoAudio",
                    "Codec": "aac",
                    "Conditions": [
                      {
                        "Condition": "Equals",
                        "Property": "IsSecondaryAudio",
                        "Value": "false",
                        "IsRequired": false
                      }
                    ]
                  },
                  {
                    "Type": "VideoAudio",
                    "Conditions": [
                      {
                        "Condition": "Equals",
                        "Property": "IsSecondaryAudio",
                        "Value": "false",
                        "IsRequired": false
                      }
                    ]
                  },
                  {
                    "Type": "Video",
                    "Codec": "h264",
                    "Conditions": [
                      {
                        "Condition": "NotEquals",
                        "Property": "IsAnamorphic",
                        "Value": "true",
                        "IsRequired": false
                      },
                      {
                        "Condition": "EqualsAny",
                        "Property": "VideoProfile",
                        "Value": "high|main|baseline|constrained baseline|high 10",
                        "IsRequired": false
                      },
                      {
                        "Condition": "EqualsAny",
                        "Property": "VideoRangeType",
                        "Value": "SDR",
                        "IsRequired": false
                      },
                      {
                        "Condition": "LessThanEqual",
                        "Property": "VideoLevel",
                        "Value": "52",
                        "IsRequired": false
                      },
                      {
                        "Condition": "NotEquals",
                        "Property": "IsInterlaced",
                        "Value": "true",
                        "IsRequired": false
                      }
                    ]
                  },
                  {
                    "Type": "Video",
                    "Codec": "hevc",
                    "Conditions": [
                      {
                        "Condition": "NotEquals",
                        "Property": "IsAnamorphic",
                        "Value": "true",
                        "IsRequired": false
                      },
                      {
                        "Condition": "EqualsAny",
                        "Property": "VideoProfile",
                        "Value": "main",
                        "IsRequired": false
                      },
                      {
                        "Condition": "EqualsAny",
                        "Property": "VideoRangeType",
                        "Value": "SDR|HDR10|HLG",
                        "IsRequired": false
                      },
                      {
                        "Condition": "LessThanEqual",
                        "Property": "VideoLevel",
                        "Value": "120",
                        "IsRequired": false
                      },
                      {
                        "Condition": "NotEquals",
                        "Property": "IsInterlaced",
                        "Value": "true",
                        "IsRequired": false
                      }
                    ]
                  },
                  {
                    "Type": "Video",
                    "Codec": "vp9",
                    "Conditions": [
                      {
                        "Condition": "EqualsAny",
                        "Property": "VideoRangeType",
                        "Value": "SDR|HDR10|HLG",
                        "IsRequired": false
                      }
                    ]
                  },
                  {
                    "Type": "Video",
                    "Codec": "av1",
                    "Conditions": [
                      {
                        "Condition": "NotEquals",
                        "Property": "IsAnamorphic",
                        "Value": "true",
                        "IsRequired": false
                      },
                      {
                        "Condition": "EqualsAny",
                        "Property": "VideoProfile",
                        "Value": "main",
                        "IsRequired": false
                      },
                      {
                        "Condition": "EqualsAny",
                        "Property": "VideoRangeType",
                        "Value": "SDR|HDR10|HLG",
                        "IsRequired": false
                      },
                      {
                        "Condition": "LessThanEqual",
                        "Property": "VideoLevel",
                        "Value": "19",
                        "IsRequired": false
                      }
                    ]
                  }
                ],
                "SubtitleProfiles": [
                  {
                    "Format": "vtt",
                    "Method": "External"
                  },
                  {
                    "Format": "ass",
                    "Method": "External"
                  },
                  {
                    "Format": "ssa",
                    "Method": "External"
                  }
                ],
                "ResponseProfiles": [
                  {
                    "Type": "Video",
                    "Container": "m4v",
                    "MimeType": "video/mp4"
                  }
                ]
              }
            }
            """;

    private final JellyfinRepository jellyfinRepository;
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

    public JellyfinService(JellyfinRepository jellyfinRepository, RestTemplateBuilder builder, ObjectMapper objectMapper, SettingRepository settingRepository) {
        this.jellyfinRepository = jellyfinRepository;
        restTemplate = builder
                .defaultHeader("User-Agent", Constants.USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
        this.settingRepository = settingRepository;
    }

    public List<Jellyfin> findAll() {
        List<Jellyfin> list = new ArrayList<>(jellyfinRepository.findAll());
        list.sort(Comparator.comparing(Jellyfin::getOrder));
        return list;
    }

    public Jellyfin getById(Integer id) {
        return jellyfinRepository.findById(id).orElseThrow(() -> new NotFoundException("站点不存在"));
    }

    public Jellyfin create(Jellyfin dto) {
        validate(dto);

        if (jellyfinRepository.existsByName(dto.getName())) {
            throw new BadRequestException("站点名字重复");
        }
        dto.setId(null);
        return jellyfinRepository.save(dto);
    }

    public Jellyfin update(int id, Jellyfin dto) {
        validate(dto);
        Jellyfin jellyfin = jellyfinRepository.findById(id).orElseThrow(() -> new NotFoundException("站点不存在"));
        Optional<Jellyfin> other = jellyfinRepository.findByName(dto.getName());
        if (other.isPresent() && other.get().getId() != id) {
            throw new BadRequestException("站点名字重复");
        }

        jellyfin.setName(dto.getName());
        jellyfin.setUrl(dto.getUrl());
        jellyfin.setUserAgent(dto.getUserAgent());
        jellyfin.setUsername(dto.getUsername());
        jellyfin.setPassword(dto.getPassword());
        jellyfin.setOrder(dto.getOrder());

        return jellyfinRepository.save(jellyfin);
    }

    public void delete(int id) {
        jellyfinRepository.deleteById(id);
    }

    private void validate(Jellyfin dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("站点名称不能为空");
        }

        if (StringUtils.isBlank(dto.getUrl())) {
            throw new BadRequestException("站点Url不能为空");
        }

        if (StringUtils.isBlank(dto.getUsername())) {
            throw new BadRequestException("用户名不能为空");
        }

        if (dto.getPassword() == null) {
            dto.setPassword("");
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
        for (Jellyfin jellyfin : findAll()) {
            var info = getJellyfinInfo(jellyfin);
            if (info == null) {
                continue;
            }
            List<MovieDetail> list = new ArrayList<>();
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", getAuthorizationHeader(info));
            if (StringUtils.isNotBlank(jellyfin.getUserAgent())) {
                headers.add("User-Agent", jellyfin.getUserAgent());
            }
            HttpEntity<Object> entity = new HttpEntity<>(null, headers);
            String url = jellyfin.getUrl() + "/Users/" + info.getUser().getId() + "/Items/Resume?Limit=12&Recursive=true&Fields=PrimaryImageAspectRatio,BasicSyncInfo,ProductionYear,CommunityRating&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb&EnableTotalRecordCount=false&MediaTypes=Video";
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();

            for (var item : response.getItems()) {
                var movie = getMovieDetail(item, jellyfin);
                list.add(movie);
            }

            for (var parent : info.getViews()) {
                url = jellyfin.getUrl() + "/Users/" + info.getUser().getId() + "/Items/Latest?Limit=12&Fields=PrimaryImageAspectRatio,BasicSyncInfo,ProductionYear,CommunityRating&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb&ParentId=" + parent.getId();
                var items = restTemplate.exchange(url, HttpMethod.GET, entity, new ParameterizedTypeReference<List<EmbyItem>>() {
                }).getBody();
                for (var item : items) {
                    var movie = getMovieDetail(item, jellyfin);
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

    private static MovieDetail getMovieDetail(EmbyItem item, Jellyfin jellyfin) {
        var movie = new MovieDetail();
        movie.setVod_id(jellyfin.getId() + "-" + item.getId());
        if ("Episode".equals(item.getType())) {
            movie.setVod_name(item.getSeriesName());
        } else {
            movie.setVod_name(item.getName());
        }

        if (item.getImageTags() != null && item.getImageTags().getPrimary() != null) {
            movie.setVod_pic(jellyfin.getUrl() + "/Items/" + item.getId() + "/Images/Primary?maxWidth=400&tag=" + item.getImageTags().getPrimary() + "&quality=90");
        }
        movie.setVod_director(jellyfin.getName());
        movie.setVod_remarks(Objects.toString(item.getRating(), null));
        movie.setVod_year(Objects.toString(item.getYear(), null));
        return movie;
    }

    private static String getAuthorizationHeader(EmbyInfo info) {
        return "MediaBrowser Client=\"" + info.getSessionInfo().getClient() + "\", Device=\"" + info.getSessionInfo().getDeviceName() + "\", DeviceId=\"" + info.getSessionInfo().getDeviceId() + "\", Version=\"" + info.getSessionInfo().getVersion() + "\", Token=\"" + info.getAccessToken() + "\"";
    }

    public MovieList detail(String tid) throws JsonProcessingException {
        String[] parts = tid.split("-");
        Jellyfin jellyfin = jellyfinRepository.findById(Integer.parseInt(parts[0])).orElseThrow(() -> new NotFoundException("站点不存在"));
        var info = getJellyfinInfo(jellyfin);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getAuthorizationHeader(info));
        if (StringUtils.isNotBlank(jellyfin.getUserAgent())) {
            headers.add("User-Agent", jellyfin.getUserAgent());
        }
        HttpEntity<Object> entity = new HttpEntity<>(null, headers);
        String url = jellyfin.getUrl() + "/Users/" + info.getUser().getId() + "/Items/" + parts[1];
        var item = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItem.class).getBody();

        MovieList result = new MovieList();
        MovieDetail movie = getMovieDetail(item, jellyfin);
        movie.setVod_content(item.getOverview());
        movie.setVod_play_from(jellyfin.getName());
        movie.setVod_play_url(movie.getVod_id());
        if ("Episode".equals(item.getType()) || "Series".equals(item.getType())) {
            List<EmbyItem> list = getAll(jellyfin, info, item.getSeriesId() == null ? item.getId() : item.getSeriesId());
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
                if (video.getName().equals("第 " + video.getIndexNumber() + " 集") || video.getName().equals("Episode " + video.getIndexNumber())) {
                    urls.add(video.getName() + "$" + jellyfin.getId() + "-" + video.getId());
                } else {
                    urls.add((video.getIndexNumber() == null ? "" : video.getIndexNumber() + ".") + video.getName() + "$" + jellyfin.getId() + "-" + video.getId());
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

    private List<EmbyItem> getAll(Jellyfin jellyfin, EmbyInfo info, String sid) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getAuthorizationHeader(info));
        if (StringUtils.isNotBlank(jellyfin.getUserAgent())) {
            headers.add("User-Agent", jellyfin.getUserAgent());
        }
        HttpEntity<Object> entity = new HttpEntity<>(null, headers);
        String url = jellyfin.getUrl() + "/Users/" + info.getUser().getId() + "/Items?ParentId=" + sid + "&Filters=IsNotFolder&Recursive=true&Limit=2000&Fields=Chapters,ProductionYear,PremiereDate&ExcludeLocationTypes=Virtual&EnableTotalRecordCount=false&CollapseBoxSetItems=false";
        var items = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();
        return items.getItems();
    }

    public MovieList search(String wd) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        for (Jellyfin jellyfin : findAll()) {
            var info = getJellyfinInfo(jellyfin);
            if (info == null) {
                continue;
            }
            list.addAll(search(jellyfin, info, wd, "Movie"));
            list.addAll(search(jellyfin, info, wd, "Series"));
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        return result;
    }

    private List<MovieDetail> search(Jellyfin jellyfin, EmbyInfo info, String wd, String type) {
        List<MovieDetail> list = new ArrayList<>();
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getAuthorizationHeader(info));
        if (StringUtils.isNotBlank(jellyfin.getUserAgent())) {
            headers.add("User-Agent", jellyfin.getUserAgent());
        }
        HttpEntity<Object> entity = new HttpEntity<>(null, headers);
        String url = jellyfin.getUrl() + "/Users/" + info.getUser().getId() + "/Items?IncludePeople=false&IncludeMedia=true&IncludeGenres=false&IncludeStudios=false&IncludeArtists=false&IncludeItemTypes=" + type + "&Limit=30&Fields=PrimaryImageAspectRatio,BasicSyncInfo,ProductionYear,CommunityRating&Recursive=true&EnableTotalRecordCount=false&ImageTypeLimit=1&searchTerm=" + wd;
        var response = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();
        for (var item : response.getItems()) {
            var movie = getSearchDetail(item, jellyfin);
            list.add(movie);
        }
        return list;
    }

    private static MovieDetail getSearchDetail(EmbyItem item, Jellyfin jellyfin) {
        var movie = new MovieDetail();
        movie.setVod_id(jellyfin.getId() + "-" + item.getId());
        if ("Episode".equals(item.getType())) {
            movie.setVod_name(item.getSeriesName());
        } else {
            movie.setVod_name(item.getName());
        }

        if (item.getImageTags() != null && item.getImageTags().getPrimary() != null) {
            movie.setVod_pic(jellyfin.getUrl() + "/Items/" + item.getId() + "/Images/Primary?maxWidth=400&tag=" + item.getImageTags().getPrimary() + "&quality=90");
        }
        movie.setVod_remarks(jellyfin.getName() + " " + Objects.toString(item.getRating(), ""));
        movie.setVod_year(Objects.toString(item.getYear(), null));
        return movie;
    }

    public MovieList list(String id, String sort, Integer pg) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        if (id.contains("-")) {
            String[] parts = id.split("-");
            if (sort == null) {
                sort = "DateCreated,SortName:Descending";
            }
            String[] sorts = sort.split(":");
            Jellyfin jellyfin = jellyfinRepository.findById(Integer.parseInt(parts[0])).orElseThrow(() -> new NotFoundException("站点不存在"));
            var info = getJellyfinInfo(jellyfin);
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
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", getAuthorizationHeader(info));
            if (StringUtils.isNotBlank(jellyfin.getUserAgent())) {
                headers.add("User-Agent", jellyfin.getUserAgent());
            }
            HttpEntity<Object> entity = new HttpEntity<>(null, headers);
            String url = jellyfin.getUrl() + "/Users/" + info.getUser().getId() + "/Items?SortBy=" + sorts[0] + "&SortOrder=" + sorts[1] + "&IncludeItemTypes=" + type + "&Recursive=true&Fields=BasicSyncInfo,PrimaryImageAspectRatio,ProductionYear,CommunityRating&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb&StartIndex=" + start + "&Limit=" + size + "&ParentId=" + view.getId();
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();
            for (var item : response.getItems()) {
                var movie = getMovieDetail(item, jellyfin);
                list.add(movie);
            }

            result.setPagecount(response.getTotal() / size + 1);
        } else {
            Jellyfin jellyfin = jellyfinRepository.findById(Integer.parseInt(id)).orElseThrow(() -> new NotFoundException("站点不存在"));
            var info = getJellyfinInfo(jellyfin);
            if (info == null) {
                return result;
            }
            int i = 0;
            for (EmbyItem item : info.getViews()) {
                var movie = new MovieDetail();
                movie.setVod_id(jellyfin.getId() + "-" + i++);
                movie.setVod_name(item.getName());
                if (item.getImageTags() != null && item.getImageTags().getPrimary() != null) {
                    movie.setVod_pic(jellyfin.getUrl() + "/Items/" + item.getId() + "/Images/Primary?maxWidth=400&tag=" + item.getImageTags().getPrimary() + "&quality=90");
                }
                movie.setVod_tag(FOLDER);
                list.add(movie);
            }
        }

        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());

        log.debug("list result: {}", result);
        return result;
    }

    public CategoryList category() {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        List<Jellyfin> sites = findAll();
        if (sites.size() > 1) {
            for (Jellyfin jellyfin : sites) {
                var category = new Category();
                category.setType_id(String.valueOf(jellyfin.getId()));
                category.setType_name(jellyfin.getName());
                category.setType_flag(0);

                result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
                list.add(category);
            }
        } else {
            for (Jellyfin jellyfin : sites) {
                var info = getJellyfinInfo(jellyfin);
                if (info == null) {
                    continue;
                }
                int i = 0;
                for (EmbyItem item : info.getViews()) {
                    var category = new Category();
                    category.setType_id(jellyfin.getId() + "-" + i++);
                    category.setType_name(jellyfin.getName() + ":" + item.getName());
                    category.setType_flag(0);

                    result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
                    list.add(category);
                }
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
        Jellyfin jellyfin = jellyfinRepository.findById(Integer.parseInt(parts[0])).orElseThrow(() -> new NotFoundException("站点不存在"));
        var info = getJellyfinInfo(jellyfin);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getAuthorizationHeader(info));
        if (StringUtils.isNotBlank(jellyfin.getUserAgent())) {
            headers.add("User-Agent", jellyfin.getUserAgent());
        }
        String body = PLAY.replace("USER_ID", info.getUser().getId()).replace("MEDIA_ID", parts[1]);
        HttpEntity<Object> entity = new HttpEntity<>(objectMapper.readTree(body), headers);
        String url = jellyfin.getUrl() + "/Items/" + parts[1] + "/PlaybackInfo";
        var media = restTemplate.exchange(url, HttpMethod.POST, entity, EmbyMediaSources.class).getBody();

        List<String> urls = new ArrayList<>();
        for (var source : media.getItems()) {
            urls.add(source.getName());
            urls.add(jellyfin.getUrl() + "/Videos/" + parts[1] + "/stream.mp4?Static=true&mediaSourceId=" + parts[1] + "&deviceId=" + info.getSessionInfo().getDeviceId() + "&api_key=" + info.getAccessToken() + "&Tag=" + source.getEtag());
        }
        String ua = Constants.USER_AGENT;
        if (StringUtils.isNotBlank(jellyfin.getUserAgent())) {
            ua = jellyfin.getUserAgent();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("url", urls);
        result.put("subs", getSubtitles(jellyfin, media.getItems().get(0)));
        result.put("header", "{\"User-Agent\": \"" + ua + "\"}");
        result.put("parse", 0);
        log.debug("{}", result);
        return result;
    }

    private List<Sub> getSubtitles(Jellyfin jellyfin, EmbyMediaSources.MediaSources mediaSources) {
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
                sub.setUrl(jellyfin.getUrl() + stream.getUrl());
                list.add(sub);
            }
        }
        return list;
    }

    private EmbyInfo getJellyfinInfo(Jellyfin jellyfin) {
        EmbyInfo result = cache.getIfPresent(jellyfin.getId());
        if (result != null) {
            return result;
        }
        try {
            Map<String, String> body = new HashMap<>();
            body.put("Username", jellyfin.getUsername());
            body.put("Pw", jellyfin.getPassword());
            log.debug("get Jellyfin info: {} {} {} {}", jellyfin.getId(), jellyfin.getName(), jellyfin.getUrl(), jellyfin.getUsername());
            HttpHeaders headers = new HttpHeaders();
            if (StringUtils.isNotBlank(jellyfin.getUserAgent())) {
                headers.set("User-Agent", jellyfin.getUserAgent());
            } else {
                headers.set("User-Agent", Constants.USER_AGENT);
            }
            headers.set("Authorization", "MediaBrowser Client=\"Jellyfin Web\", Device=\"Chrome\", DeviceId=\"1\", Version=\"10.10.3\"");
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            EmbyInfo info = restTemplate.exchange(jellyfin.getUrl() + "/Users/authenticatebyname", HttpMethod.POST, entity, EmbyInfo.class).getBody();
            cache.put(jellyfin.getId(), info);

            headers.set("Authorization", getAuthorizationHeader(info));
            entity = new HttpEntity<>(null, headers);
            String url = jellyfin.getUrl() + "/UserViews?userId=" + info.getUser().getId();
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, EmbyItems.class).getBody();
            info.setViews(new ArrayList<>(new LinkedHashSet<>(response.getItems())));

            return info;
        } catch (Exception e) {
            log.error("Get Jellyfin info failed.", e);
        }
        return null;
    }
}
