package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
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
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.Task;
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
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern NUMBER = Pattern.compile("Season (\\d{1,2})");
    private static final Pattern NUMBER2 = Pattern.compile("SE(\\d{1,2})");
    private static final Pattern NUMBER3 = Pattern.compile("^S(\\d{1,2})$");
    private static final Pattern NUMBER1 = Pattern.compile("第(\\d{1,2})季");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\((\\d{4})\\)");
    private static final Pattern YEAR2_PATTERN = Pattern.compile("(\\d{4})");
    private static final String DB_PREFIX = "https://movie.douban.com/subject/";
    private static final String[] tokens = new String[]{"导演:", "编剧:", "主演:", "类型:", "制片国家/地区:", "语言:", "上映日期:",
            "片长:", "又名:", "IMDb链接:", "官方网站:", "官方小站:", "首播:", "季数:", "集数:", "单集片长:"};

    private final AppProperties appProperties;
    private final MetaRepository metaRepository;
    private final MovieRepository movieRepository;
    private final AliasRepository aliasRepository;
    private final SettingRepository settingRepository;
    private final SiteService siteService;
    private final TaskService taskService;

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient();

    private volatile boolean downloading;

    public DoubanService(AppProperties appProperties,
                         MetaRepository metaRepository,
                         MovieRepository movieRepository,
                         AliasRepository aliasRepository,
                         SettingRepository settingRepository,
                         SiteService siteService,
                         TaskService taskService,
                         RestTemplateBuilder builder,
                         JdbcTemplate jdbcTemplate,
                         Environment environment) {
        this.appProperties = appProperties;
        this.metaRepository = metaRepository;
        this.movieRepository = movieRepository;
        this.aliasRepository = aliasRepository;
        this.settingRepository = settingRepository;
        this.siteService = siteService;
        this.taskService = taskService;
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
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
            Path source = Path.of("/tmp/base_version");
            if (Files.exists(source)) {
                try {
                    settingRepository.save(new Setting(MOVIE_VERSION, Files.readString(source).trim()));
                } catch (Exception e) {
                    log.warn("", e);
                }

                try {
                    Files.delete(source);
                } catch (Exception e) {
                    log.warn("", e);
                }

                log.debug("reset data.sql");
                writeText("/data/atv/data.sql", "SELECT COUNT(*) FROM META;");
            }
        }

        fixMetaId();
        runCmd();
    }

    private void runCmd() {
        try {
            Path path = Paths.get("/data/atv/cmd.sql");
            if (Files.exists(path)) {
                log.info("run sql from file {}", path);
                try {
                    jdbcTemplate.execute("RUNSCRIPT FROM '/data/atv/cmd.sql'");
                } catch (Exception e) {
                    log.warn("execute sql failed: {}", e);
                }
                Files.delete(path);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void fixMetaId() {
        if (settingRepository.existsById("fix_meta_id")) {
            return;
        }
        log.info("fix meta id");
        try {
            jdbcTemplate.execute("UPDATE ID_GENERATOR SET NEXT_ID = 500000 WHERE ENTITY_NAME = 'meta'");
        } catch (Exception e) {
            jdbcTemplate.execute("INSERT INTO ID_GENERATOR VALUES ('meta', 500000)");
        }
        settingRepository.save(new Setting("fix_meta_id", "true"));
    }

    public int fixUnique() {
        log.info("fixUnique");
        Map<String, Meta> map = new HashMap<>();
        List<Meta> list = new ArrayList<>();
        for (Meta meta : metaRepository.findAll(Sort.by("id"))) {
            String path = meta.getPath();
            if (map.containsKey(path)) {
                list.add(map.get(path));
            }
            map.put(path, meta);
        }
        log.info("delete {} meta: {}", list.size(), list.stream().map(Meta::getId).toList());
        log.info("{}", list.stream().map(Meta::getPath).toList());
        metaRepository.deleteAll(list);
        return list.size();
    }

    @Scheduled(cron = "0 0 22 * * ?")
    public void update() {
        Versions versions = new Versions();
        getRemoteVersion(versions);
    }

    public String getRemoteVersion(Versions versions) {
        if (!environment.matchesProfiles("xiaoya")) {
            return "";
        }

        try {
            String remote = restTemplate.getForObject("http://data.har01d.cn/movie_version", String.class).trim();
            versions.setMovie(remote);
            String local = settingRepository.findById(MOVIE_VERSION).map(Setting::getValue).orElse("0.0").trim();
            String cached = getCachedVersion();
            versions.setCachedMovie(cached);
            if (!local.equals(remote) && !remote.equals(cached) && !downloading) {
                log.info("local: {} cached: {} remote: {}", local, cached, remote);
                executor.execute(() -> upgradeMovieData(local, remote));
            } else {
                log.debug("local: {} cached: {} remote: {}", local, cached, remote);
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
                .filter(e -> Double.compare(getVersionNumber(e), local) > 0)
                .sorted((a, b) -> Double.compare(getVersionNumber(a), getVersionNumber(b)));
    }

    private double getVersionNumber(Path path) {
        return Double.parseDouble(getVersion(path));
    }

    private String getVersion(Path path) {
        String name = path.toFile().getName();
        int index = name.lastIndexOf('.');
        return name.substring(0, index);
    }

    private void upgradeSqlFile(Path file) {
        try {
            //jdbcTemplate.execute("RUNSCRIPT FROM '" + file.toString() + "'");
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                try {
                    jdbcTemplate.execute(line);
                } catch (Exception e) {
                    log.debug("execute sql failed: {}", e);
                }
            }
            String version = getVersion(file);
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
                        .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
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
            Alias alias = aliasRepository.findById(name).orElse(null);
            if (alias != null) {
                log.debug("name: {} alias: {}", name, alias.getAlias());
                return alias.getMovie();
            }

            name = TextUtils.fixName(name);

            alias = aliasRepository.findById(name).orElse(null);
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

    public boolean updateMetaMovie(Integer id, MetaDto dto) {
        if (dto.getMovieId() == null || dto.getMovieId() < 100000) {
            throw new BadRequestException("电影ID不正确");
        }
        var meta = metaRepository.findById(id).orElse(null);
        if (meta == null) {
            return false;
        }
        Movie movie = getById(dto.getMovieId());
        if (movie != null) {
            meta.setMovie(movie);
            meta.setYear(movie.getYear());
            meta.setName(movie.getName());
            if (StringUtils.isNotBlank(movie.getDbScore())) {
                meta.setScore((int) (Double.parseDouble(movie.getDbScore()) * 10));
            }
            meta.setSiteId(dto.getSiteId());
            metaRepository.save(meta);
            return true;
        }
        return false;
    }

    public boolean scrape(Integer id, String name) {
        var meta = metaRepository.findById(id).orElse(null);
        if (meta == null) {
            return false;
        }
        Movie movie = scrape(name, getYearFromPath(meta.getPath()));
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

    private Set<String> loadFailed() {
        Path path = Paths.get("/data/atv/failed.txt");
        try {
            List<String> lines = Files.readAllLines(path).stream().filter(e -> !e.startsWith("/")).toList();
            return new HashSet<>(lines);
        } catch (IOException e) {
            log.warn("", e);
        }
        return new HashSet<>();
    }

    @Async
    public void scrape(Integer siteId, boolean force) throws IOException {
        Path path = Paths.get("/data/index", String.valueOf(siteId), "custom_index.txt");
        if (!Files.exists(path)) {
            throw new BadRequestException("索引文件不存在");
        }
        log.debug("readIndexFile: {}", path);
        List<String> lines = Files.readAllLines(path);
        log.info("get {} lines from index file {}", lines.size(), path);
        Site site = siteService.getById(siteId);
        Task task = taskService.addScrapeTask(site);
        scrapeIndexFile(task, lines, force);
    }

    public void scrapeIndexFile(Task task, List<String> lines, boolean force) {
        int count = 0;
        Set<String> failed = loadFailed();
        List<String> paths = new ArrayList<>();
        log.debug("load {} failed names", failed.size());
        taskService.startTask(task.getId());

        for (int i = 0; i < lines.size(); i++) {
            if (isCancelled(task.getId())) {
                break;
            }
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("-") || line.startsWith("+")) {
                continue;
            }

            try {
                log.debug("handle {} {}", i, line);
                taskService.updateTaskSummary(task.getId(), (i + 1) + ":" + line);
                Movie movie = handleIndexLine(i, line, force, failed);
                if (movie != null) {
                    count++;
                    taskService.updateTaskData(task.getId(), "成功刮削数量：" + count);
                } else {
                    paths.add(line.split("#")[0]);
                }
            } catch (Exception e) {
                log.warn("{}: {}", i, line, e);
            }
        }

        taskService.completeTask(task.getId());

        writeText("/data/atv/paths.txt", String.join("\n", paths));
        writeText("/data/atv/failed.txt", String.join("\n", failed));
    }

    private static void writeText(String path, String content) {
        try {
            Files.writeString(Paths.get(path), content);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private Movie handleIndexLine(int id, String path, boolean force, Set<String> failed) {
        String[] parts = path.split("#");
        path = parts[0];

        Meta meta = metaRepository.findByPath(path);
        if (meta == null) {
            meta = new Meta();
            meta.setPath(path);
        } else if (meta.getMovie() != null && !force) {
            return meta.getMovie();
        }

        String name = "";
        Movie movie = null;
        if (parts.length == 2) {
            name = TextUtils.fixName(parts[1]);
        } else if (parts.length > 2) {
            name = TextUtils.fixName(parts[1]);
            String number = parts[2];
            log.debug("{} {}", name, number);
            if (number.length() > 5) {
                try {
                    movie = getById(Integer.parseInt(number));
                } catch (Exception e) {
                    log.warn("{} {}", id + 1, path, e);
                }
                if (movie != null) {
                    name = movie.getName();
                }
            }
        }
        if (name.isBlank()) {
            name = getName(path);
        }

        if (isSpecialFolder(name)) {
            name = getParentName(path);
        }

        parts = name.split("丨");
        if (parts.length > 3) {
            name = parts[0];
        }

        if (id > 0 && id % 1000 == 0) {
            log.info("{} {} {}", id, name, path);
        }

        if (movie != null && TextUtils.isNormal(name) && TextUtils.isNormal(movie.getName())) {
            log.info("[{}] - add {} {} for path {}", id, movie.getId(), movie.getName(), path);
            return updateMeta(path, meta, movie);
        }

        if (name.startsWith("Season ")) {
            Matcher m = NUMBER.matcher(name);
            if (m.find()) {
                String text = m.group(1);
                String newNum = TextUtils.number2text(text);
                name = TextUtils.fixName(getParentName(path)) + " 第" + newNum + "季";
            }
        } else if (name.startsWith("第")) {
            Matcher m = NUMBER1.matcher(name);
            if (m.matches()) {
                String text = m.group(1);
                String newNum = TextUtils.number2text(text);
                name = TextUtils.fixName(getParentName(path)) + " 第" + newNum + "季";
            } else if (name.endsWith("季")) {
                name = TextUtils.fixName(getParentName(path)) + " " + name;
            }
        } else if (name.startsWith("SE")) {
            Matcher m = NUMBER2.matcher(name);
            if (m.find()) {
                String text = m.group(1);
                String newNum = TextUtils.number2text(text);
                name = TextUtils.fixName(getParentName(path)) + " 第" + newNum + "季";
            }
        } else if (name.startsWith("S")) {
            Matcher m = NUMBER3.matcher(name);
            if (m.matches()) {
                String text = m.group(1);
                String newNum = TextUtils.number2text(text);
                name = TextUtils.fixName(getParentName(path)) + " 第" + newNum + "季";
            }
        }

        name = TextUtils.fixName(name);
        if (failed.contains(name)) {
            return null;
        }

        movie = getByName(name);
        if (movie == null && TextUtils.isNormal(name)) {
            String newname = TextUtils.updateName(name);
            if (failed.contains(newname) || !TextUtils.isNormal(newname)) {
                log.debug("exclude {}: {}", path, newname);
                failed.add(name);
                return null;
            }

            try {
                log.info("[{}] handle name: {} - path: {}", id, newname, path);
                movie = search(newname, getYearFromPath(path));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }

            if (failed.contains(getParent(path))) {
                return null;
            }

            if (movie != null && TextUtils.isNormal(movie.getName())) {
                meta.setPath(path);
                meta.setMovie(movie);
                meta.setYear(movie.getYear());
                meta.setName(movie.getName());
                if (StringUtils.isNotBlank(movie.getDbScore())) {
                    meta.setScore((int) (Double.parseDouble(movie.getDbScore()) * 10));
                }
                metaRepository.save(meta);
                log.info("{} - add {} '{}' for path {}", id, movie.getId(), movie.getName(), path);
                return movie;
            }
        }

        if (movie != null && TextUtils.isNormal(name) && TextUtils.isNormal(movie.getName())) {
            log.info("[{}] add {} {} for path {}", id, movie.getId(), movie.getName(), path);
            return updateMeta(path, meta, movie);
        } else {
            log.debug("add failed: {}", name);
            failed.add(name);
        }

        return null;
    }

    private boolean isCancelled(Integer taskId) {
        Task task = taskService.getById(taskId);
        return task.getStatus() == TaskStatus.COMPLETED && task.getResult() == TaskResult.CANCELLED;
    }

    private Movie updateMeta(String path, Meta meta, Movie movie) {
        meta.setPath(path);
        meta.setMovie(movie);
        meta.setYear(movie.getYear());
        meta.setName(movie.getName());
        if (StringUtils.isNotBlank(movie.getDbScore())) {
            meta.setScore((int) (Double.parseDouble(movie.getDbScore()) * 10));
        }
        metaRepository.save(meta);
        return movie;
    }

    private boolean isSpecialFolder(String name) {
        if (name.toLowerCase().startsWith("4k")) {
            return true;
        }
        if (name.toLowerCase().startsWith("2160p")) {
            return true;
        }
        if (name.toLowerCase().startsWith("1080p")) {
            return true;
        }
        if (name.equals("SDR")) {
            return true;
        }
        if (name.equals("国语")) {
            return true;
        }
        if (name.equals("国语版")) {
            return true;
        }
        if (name.equals("粤语")) {
            return true;
        }
        if (name.equals("粤语版")) {
            return true;
        }
        if (name.equals("番外彩蛋")) {
            return true;
        }
        if (name.equals("彩蛋")) {
            return true;
        }
        if (name.equals("付费花絮合集")) {
            return true;
        }
        if (name.equals("大结局点映礼")) {
            return true;
        }
        if (name.equals("心动记录+彩蛋")) {
            return true;
        }
        return false;
    }

    private String getName(String path) {
        int index = path.lastIndexOf('/');
        if (index > -1) {
            return path.substring(index + 1);
        }
        return path;
    }

    private String getParentName(String path) {
        int index = path.lastIndexOf('/');
        if (index > -1) {
            path = path.substring(0, index);
        }
        return getName(path);
    }

    private String getParent(String path) {
        int index = path.lastIndexOf('/');
        if (index > 0) {
            return path.substring(0, index);
        }
        return path;
    }

    public Integer getYearFromPath(String path) {
        int max = LocalDate.now().getYear() + 3;
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            Matcher m = YEAR2_PATTERN.matcher(parts[i]);
            while (m.find()) {
                int year = Integer.parseInt(m.group(1));
                if (year > 1960 && year < max) {
                    log.debug("find year {} from path {}", year, path);
                    return year;
                }
            }
        }
        return null;
    }

    public Movie scrape(String name, Integer year) {
        try {
            log.info("刮削: {} {}", name, year);
            return search(name, year);
        } catch (IOException e) {
            return null;
        }
    }

    private Movie search(String text, Integer year) throws IOException {
        if (text.trim().isEmpty()) {
            return null;
        }
        String query;
        if (year != null) {
            query = text + " " + year;
        } else {
            query = text;
        }
        String url = "https://m.douban.com/search/?type=movie&query=" + URLEncoder.encode(query, "UTF-8");

        String html = getHtml(url);

        Document doc = Jsoup.parse(html);
        Elements elements = doc.select("ul.search_results_subjects li a");

        int distance = 9;
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
                    if (temp < distance) {
                        best = id;
                        distance = temp;
                    }
                }
            }
        }

        int target = 0;
        if (distance <= target) {
            log.info("distance: {}", distance);
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
                        if (temp < distance) {
                            best = id;
                            distance = temp;
                        }
                    }
                }
            }
        }

        if (distance <= target) {
            log.info("min distance: {}", distance);
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
            meta.setSiteId(dto.getSiteId());
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
            log.debug("parse by id: {}", id);
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
