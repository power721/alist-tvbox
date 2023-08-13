package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.Versions;
import cn.har01d.alist_tvbox.entity.Alias;
import cn.har01d.alist_tvbox.entity.AliasRepository;
import cn.har01d.alist_tvbox.entity.Meta;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.TextUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static cn.har01d.alist_tvbox.util.Constants.MOVIE_VERSION;

@Slf4j
@Service
public class DoubanService {

    private final AppProperties appProperties;
    private final MetaRepository metaRepository;
    private final MovieRepository movieRepository;
    private final AliasRepository aliasRepository;
    private final SettingRepository settingRepository;

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean downloading;

    public DoubanService(AppProperties appProperties,
                         MetaRepository metaRepository,
                         MovieRepository movieRepository,
                         AliasRepository aliasRepository,
                         SettingRepository settingRepository,
                         RestTemplateBuilder builder,
                         JdbcTemplate jdbcTemplate) {
        this.appProperties = appProperties;
        this.metaRepository = metaRepository;
        this.movieRepository = movieRepository;
        this.aliasRepository = aliasRepository;
        this.settingRepository = settingRepository;
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .build();
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void setup() {
        try {
            Path path = Paths.get("/data/atv/movie_version");
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    settingRepository.save(new Setting(MOVIE_VERSION, lines.get(0).trim()));
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        if (metaRepository.count() > 10000) {
            Path file = Path.of("/tmp/data/base_version");
            if (Files.exists(file)) {
                try {
                    Files.move(file, Paths.get("/data/atv/base_version"));
                } catch (Exception e) {
                    log.warn("", e);
                }

                try {
                    Files.writeString(Paths.get("/data/atv/data.sql"), "SELECT COUNT(*) FROM META;");
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        }
    }

    public String getRemoteVersion(Versions versions) {
        try {
            String remote = restTemplate.getForObject("http://d.har01d.cn/movie_version", String.class).trim();
            versions.setMovie(remote);
            if (appProperties.isXiaoya()) {
                String local = settingRepository.findById(MOVIE_VERSION).map(Setting::getValue).orElse("").trim();
                String cached = getCachedVersion();
                versions.setCachedMovie(cached);
                if (!local.equals(remote) && !remote.equals(cached) && !downloading) {
                    log.info("local: {} cached: {} remote: {}", local, cached, remote);
                    executor.execute(() -> downloadMovieData(remote));
                }
            }
            return remote;
        } catch (Exception e) {
            log.warn("", e);
        }
        return "";
    }

    private String getCachedVersion() {
        try {
            Path file = Paths.get("/data/atv/movie_version");
            if (Files.exists(file)) {
                return Files.readString(file).trim();
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        return "0.0";
    }

    private void downloadMovieData(String remote) {
        try {
            downloading = true;
            log.info("download movie data");
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("sh", "-c", "/movie.sh", remote);
            builder.inheritIO();
            builder.directory(new File("/opt/atv/data/"));
            Process process = builder.start();
            int code = process.waitFor();
            if (code == 0) {
                log.info("movie data downloaded");
                Path file = Paths.get("/data/atv/diff.sql");
                if (Files.exists(file)) {
                    List<String> lines = Files.readAllLines(file);
                    for (String line : lines) {
                        try {
                            jdbcTemplate.execute(line);
                        } catch (Exception e) {
                            log.debug("{}", e);
                        }
                    }
                    settingRepository.save(new Setting(MOVIE_VERSION, remote));
                    Files.delete(file);
                }
            } else {
                log.warn("download movie data failed: {}", code);
            }
        } catch (Exception e) {
            log.warn("", e);
        } finally {
            downloading = false;
        }
    }

    public String getAppRemoteVersion() {
        try {
            return restTemplate.getForObject("http://d.har01d.cn/app_version", String.class);
        } catch (Exception e) {
            log.warn("", e);
        }
        return "";
    }

    public Movie getByPath(String path) {
        try {
            Meta meta = metaRepository.findByPath(path);
            if (meta != null) {
                return meta.getMovie();
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        return null;
    }

    public List<MovieDetail> getHotRank() {
        List<MovieDetail> list = new ArrayList<>();
        Map<String, Object> request = new HashMap<>();
        request.put("pageNum", 0);
        request.put("pageSize", 100);
        try {
            JsonNode response = restTemplate.postForObject("https://pbaccess.video.qq.com/trpc.videosearch.hot_rank.HotRankServantHttp/HotRankHttp", request, JsonNode.class);
            ArrayNode arrayNode = (ArrayNode) response.path("data").path("navItemList").path(0).path("hotRankResult").path("rankItemList");
            for (JsonNode node : arrayNode) {
                MovieDetail detail = new MovieDetail();
                detail.setVod_name(node.get("title").asText());
                detail.setVod_id("msearch:" + detail.getVod_name());
                detail.setVod_pic("https://avatars.githubusercontent.com/u/97389433?s=120&v=4");

                setDoubanInfo(detail);

                list.add(detail);
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        return list;
    }

    private void setDoubanInfo(MovieDetail detail) {
        Movie movie = getByName(detail.getVod_name());
        if (movie != null) {
            if (movie.getCover() != null && !movie.getCover().isEmpty()) {
                String cover = ServletUriComponentsBuilder.fromCurrentRequest()
                        .scheme(appProperties.isEnableHttps() ? "https" : "http")
                        .replacePath("/images")
                        .query("url=" + movie.getCover())
                        .build()
                        .toUriString();
                log.debug("cover url: {}", cover);
                movie.setCover(cover);
            }
            detail.setVod_name(movie.getName());
            detail.setVod_pic(movie.getCover());
            detail.setVod_year(String.valueOf(movie.getYear()));
            detail.setVod_remarks(movie.getDbScore());
        }
    }

    public Movie getByName(String name) {
        try {
            name = TextUtils.fixName(name);

            Alias alias = aliasRepository.findById(name).orElse(null);
            if (alias != null) {
                log.debug("name: {} alias: {}", name, alias.getAlias());
                return alias.getMovie();
            }

            List<Movie> movies = movieRepository.getByName(name);
            if (movies != null && !movies.isEmpty()) {
                return movies.get(0);
            }

            String newName = TextUtils.updateName(name);
            if (!newName.equals(name)) {
                name = newName;
                log.debug("search by name: {}", name);

                alias = aliasRepository.findById(name).orElse(null);
                if (alias != null) {
                    log.debug("name: {} alias: {}", name, alias.getAlias());
                    return alias.getMovie();
                }

                movies = movieRepository.getByName(name);
                if (movies != null && !movies.isEmpty()) {
                    return movies.get(0);
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        return null;
    }

}
