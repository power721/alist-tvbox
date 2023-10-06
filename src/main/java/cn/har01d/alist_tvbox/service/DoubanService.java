package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MetaDto;
import cn.har01d.alist_tvbox.dto.Versions;
import cn.har01d.alist_tvbox.entity.Alias;
import cn.har01d.alist_tvbox.entity.AliasRepository;
import cn.har01d.alist_tvbox.entity.Meta;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.TextUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static cn.har01d.alist_tvbox.util.Constants.MOVIE_VERSION;
import static cn.har01d.alist_tvbox.util.Constants.USER_AGENT;

@Slf4j
@Service
public class DoubanService {
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\((\\d{4})\\)");
    private static final String DB_PREFIX = "https://movie.douban.com/subject/";
    private static final String[] tokens = new String[]{"导演:", "编剧:", "主演:", "类型:", "制片国家/地区:", "语言:", "上映日期:",
            "片长:", "又名:", "IMDb链接:", "官方网站:", "官方小站:", "首播:", "季数:", "集数:", "单集片长:"};

    private final AppProperties appProperties;
    private final MetaRepository metaRepository;
    private final MovieRepository movieRepository;
    private final AliasRepository aliasRepository;
    private final SettingRepository settingRepository;

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient();

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
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
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
            Path source = Path.of("/tmp/data/base_version");
            if (Files.exists(source)) {
                try {
                    Utils.execute("mv /tmp/data/base_version /data/atv/base_version");
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

        fixMetaId();
    }

    private void fixMetaId() {
        if (settingRepository.existsById("fix_meta_id")) {
            return;
        }
        log.info("fix meta id");
        jdbcTemplate.execute("INSERT INTO ID_GENERATOR VALUES ('meta', 500000)");
        settingRepository.save(new Setting("fix_meta_id", "true"));
    }

    @Scheduled(cron = "0 0 22 * * ?")
    public void update() {
        Versions versions = new Versions();
        getRemoteVersion(versions);
    }

    public String getRemoteVersion(Versions versions) {
        try {
            String remote = restTemplate.getForObject("http://data.har01d.cn/movie_version", String.class).trim();
            versions.setMovie(remote);
            String local = settingRepository.findById(MOVIE_VERSION).map(Setting::getValue).orElse("0.0").trim();
            String cached = getCachedVersion();
            versions.setCachedMovie(cached);
            if (!local.equals(remote) && !remote.equals(cached) && !downloading) {
                log.info("local: {} cached: {} remote: {}", local, cached, remote);
                executor.execute(() -> upgradeMovieData(local, remote));
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

    private void upgradeMovieData(String local, String remote) {
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
                getSqlFiles(local).forEach(this::upgradeSqlFile);
            } else {
                log.warn("download movie data failed: {}", code);
            }
        } catch (Exception e) {
            log.warn("", e);
        } finally {
            downloading = false;
        }
    }

    private Stream<Path> getSqlFiles(String version) throws IOException {
        double local = Double.parseDouble(version);
        return Files.list(Path.of("/data/atv/sql"))
                .filter(e -> Double.compare(getSqlVersion(e), local) > 0)
                .sorted((a, b) -> Double.compare(getSqlVersion(a), getSqlVersion(b)));
    }

    private double getSqlVersion(Path path) {
        String name = path.toFile().getName();
        int index = name.lastIndexOf('.');
        return Double.parseDouble(name.substring(0, index));
    }

    private void upgradeSqlFile(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                try {
                    jdbcTemplate.execute(line);
                } catch (Exception e) {
                    log.debug("execute sql failed: {}", e);
                }
            }
            String version = String.valueOf(getSqlVersion(file));
            settingRepository.save(new Setting(MOVIE_VERSION, version));
            log.info("movie data upgraded: {}", version);
        } catch (Exception e) {
            log.warn("upgrade SQL file failed: {}", file, e);
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

    public boolean updateMetaMovie(@PathVariable Integer id, Integer movieId) {
        if (movieId == null || movieId < 100000) {
            throw new BadRequestException("电影ID不正确");
        }
        var meta = metaRepository.findById(id).orElse(null);
        if (meta == null) {
            return false;
        }
        Movie movie = getById(movieId);
        if (movie != null) {
            meta.setMovie(movie);
            meta.setYear(movie.getYear());
            meta.setName(movie.getName());
            if (StringUtils.isNotBlank(movie.getDbScore())) {
                meta.setScore((int) (Double.parseDouble(movie.getDbScore()) * 10));
            }
            metaRepository.save(meta);
            return true;
        }
        return false;
    }

    public boolean scrape(@PathVariable Integer id, String name) {
        var meta = metaRepository.findById(id).orElse(null);
        if (meta == null) {
            return false;
        }
        Movie movie = scrape(name);
        if (movie != null) {
            meta.setMovie(movie);
            meta.setYear(movie.getYear());
            meta.setName(movie.getName());
            if (StringUtils.isNotBlank(movie.getDbScore())) {
                meta.setScore((int) (Double.parseDouble(movie.getDbScore()) * 10));
            }
            metaRepository.save(meta);
            return true;
        }
        return false;
    }

    public Movie scrape(String name) {
        try {
            log.info("刮削: {}", name);
            return search(name);
        } catch (IOException e) {
            return null;
        }
    }

    private Movie search(String text) throws IOException {
        if (text.trim().isEmpty()) {
            return null;
        }
        String url = "https://m.douban.com/search/?type=movie&query=" + URLEncoder.encode(text, "UTF-8");

        String html = getHtml(url);

        Document doc = Jsoup.parse(html);
        Elements elements = doc.select("ul.search_results_subjects li a");

        int similarity = 999;
        Integer best = null;

        for (Element element : elements) {
            String dbUrl = element.attr("href");
            if (dbUrl.startsWith("/movie/subject/")) {
                String name = TextUtils.fixName(element.select(".subject-title").text());
                log.info("{} {}", dbUrl, name);
                Integer id = Integer.parseInt(dbUrl.substring("/movie/subject/".length()).replace("/", ""));
                if (text.equals(name)) {
                    return getById(id);
                } else {
                    int temp = TextUtils.minDistance(text, name);
                    if (temp < similarity) {
                        best = id;
                        similarity = temp;
                    }
                }
            }
        }

        double target = 0.99;
        if (similarity > target) {
            log.info("similarity: {}", similarity);
            return getById(best);
        }

        if (TextUtils.isNormal(text) && !(text.contains("第") && text.contains("季"))) {
            text = text + " 第一季";
            for (Element element : elements) {
                String dbUrl = element.attr("href");
                if (dbUrl.startsWith("/movie/subject/")) {
                    String name = TextUtils.fixName(element.select(".subject-title").text());
                    log.info("{} {}", dbUrl, name);
                    Integer id = Integer.parseInt(dbUrl.substring("/movie/subject/".length()).replace("/", ""));
                    if (text.equals(name)) {
                        return getById(id);
                    } else {
                        int temp = TextUtils.minDistance(text, name);
                        if (temp < similarity) {
                            best = id;
                            similarity = temp;
                        }
                    }
                }
            }
        }

        if (similarity > target) {
            log.info("similarity: {}", similarity);
            return getById(best);
        }

        log.warn("找不到: {}", text);
        return null;
    }

    public Movie getById(Integer id) {
        return movieRepository.findById(id).orElseGet(() -> parse(id));
    }

    public boolean addMeta(MetaDto dto) {
        if (dto.getMovieId() == null || dto.getMovieId() < 100000) {
            throw new BadRequestException("电影ID不正确");
        }
        String path = dto.getPath();
        if (StringUtils.isBlank(path)) {
            throw new BadRequestException("路径不正确");
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        Meta meta = metaRepository.findByPath(path);
        if (meta == null) {
            meta = new Meta();
            meta.setPath(path);
        }
        Movie movie = getById(dto.getMovieId());
        if (movie != null) {
            meta.setMovie(movie);
            meta.setYear(movie.getYear());
            meta.setName(movie.getName());
            if (StringUtils.isNotBlank(movie.getDbScore())) {
                meta.setScore((int) (Double.parseDouble(movie.getDbScore()) * 10));
            }
            metaRepository.save(meta);
            return true;
        }
        return false;
    }

    private Movie parse(Integer id) {
        try {
            String url = DB_PREFIX + id + "/";
            String html = getHtml(url);

            Movie movie;

            try {
                movie = parseHtml(id, html);
            } catch (Exception e) {
                throw new BadRequestException(e);
            }

            return movieRepository.save(movie);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    private String getHtml(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                //.addHeader("Accept-Encoding", "gzip, deflate")  // cannot set this header!!!
                .addHeader("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://movie.douban.com/")
                .build();

        Call call = client.newCall(request);
        Response response = call.execute();
        String html = response.body().string();
        response.close();

        if (html.contains("页面不存在")) {
            throw new NotFoundException("页面不存在: " + url);
        }
        if (html.contains("有异常请求从你的 IP 发出") || html.contains("https://sec.douban.com/")) {
            throw new BadRequestException("被禁止访问: " + url);
        }

        return html;
    }

    private Movie parseHtml(Integer id, String html) {
        log.info("parse {}", id);
        Document doc = Jsoup.parse(html);
        Element content = doc.select("#content").first();
        Element header = content.select("h1").first();
        String name = doc.select("title").text().replace(" (豆瓣)", "").trim();
        log.info("parse {} {} - {}", DB_PREFIX + id, name, header.text());
        Element subject = content.select(".subject").first();
        String thumb = subject.select("#mainpic img").attr("src");
        Element info = subject.select("#info").first();
        Element synopsis = content.select(".related-info #link-report-intra").first();
        String dbScore = content.select(".rating_num").text();

        Movie movie = new Movie();
        movie.setId(id);
        movie.setName(fixTitle(name));
        movie.setCover(getCover(thumb));
        movie.setDbScore(dbScore);
        movie.setDescription(TextUtils.truncate(fixSynopsis(findSynopsis(synopsis)), 200));

        Matcher m = YEAR_PATTERN.matcher(header.text());
        if (m.find()) {
            movie.setYear(Integer.parseInt(m.group(1)));
        }

        String[] lines = handleTokens(info.text());
        for (String line : lines) {
            getMetadata(line, movie);
        }

        return movie;
    }

    private String fixTitle(String text) {
        return TextUtils.truncate(text, 250);
    }

    private String getCover(String url) {
        return url.replace("movie_poster_cover/lpst", "photo/photo");
    }

    private String findSynopsis(Element synopsis) {
        if (synopsis == null) {
            return "";
        }

        for (Element element : synopsis.children()) {
            String text = element.text();
            if (!text.contains("(展开全部)") && !text.contains("©豆瓣") && text.length() > 10) {
                return text;
            }
        }
        return synopsis.text();
    }

    private String fixSynopsis(String text) {
        text = text.replace("“", "")
                .replace("”", "")
                .replaceAll("-{2,}", " ");
        if (text.startsWith("　　")) {
            return text.substring("　　".length());
        }
        return text;
    }

    private String[] handleTokens(String text) {
        for (String token : tokens) {
            text = text.replace(" " + token, "\n" + token);
        }
        return text.split("\n");
    }

    private void getMetadata(String text, Movie movie) {
        String values;
        if ((values = getValues(text, "导演:")) != null) {
            movie.setDirectors(values);
            return;
        }

        if ((values = getValues(text, "编剧:")) != null) {
            movie.setEditors(values);
            return;
        }

        if ((values = getValues(text, "主演:")) != null) {
            movie.setActors(values);
            return;
        }

        if ((values = getValues(text, "类型:")) != null) {
            movie.setGenre(values);
            return;
        }

        if ((values = getValues(text, "制片国家/地区:")) != null) {
            movie.setCountry(values);
            return;
        }

        if ((values = getValues(text, "语言:")) != null) {
            movie.setLanguage(values);
        }
    }

    private String getValues(String text, String prefix) {
        if (!text.trim().startsWith(prefix)) {
            return null;
        }

        List<String> values = new ArrayList<>();
        String value = text.substring(prefix.length());
        String regex = " / ";
        String[] vals = value.split(regex);
        if (vals.length == 1 && value.contains("/")) {
            vals = value.split("/");
        }

        for (String val : vals) {
            values.add(val.trim());
            if (values.size() >= 3) {
                break;
            }
        }

        return String.join(",", values);
    }

}
