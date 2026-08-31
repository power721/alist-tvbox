package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.dto.MetaDto;
import cn.har01d.alist_tvbox.dto.Versions;
import cn.har01d.alist_tvbox.dto.MovieDiffPayload;
import cn.har01d.alist_tvbox.entity.Alias;
import cn.har01d.alist_tvbox.entity.TmdbRepository;
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
import cn.har01d.alist_tvbox.util.H2SqlConverter;
import cn.har01d.alist_tvbox.util.TextUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.time.Instant;
import java.io.IOException;
import java.util.TreeSet;
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
    private static final int BATCH_SIZE = 1000;
    /** diff 文件失败重试上限(整个文件重放,DELETE+INSERT 幂等)。 */
    private static final int MAX_DIFF_ATTEMPTS = 3;
    private static final String DIFF_SUCCESS = "SUCCESS";
    private static final String DIFF_FAILED = "FAILED";
    private static final Pattern NUMBER = Pattern.compile("Season (\\d{1,2})");
    private static final Pattern NUMBER2 = Pattern.compile("SE(\\d{1,2})");
    private static final Pattern NUMBER3 = Pattern.compile("^S(\\d{1,2})$");
    private static final Pattern NUMBER1 = Pattern.compile("第(\\d{1,2})季");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\((\\d{4})\\)");
    private static final Pattern YEAR2_PATTERN = Pattern.compile("(\\d{4})");
    // matches a whole string that is only a season marker (第一季, 第3季, Season 1, S01)
    private static final Pattern SEASON_ONLY = Pattern.compile("^第[0-9一二三四五六七八九十百零两]+季$|^Season\\s+\\d{1,2}$|^S\\d{1,2}$|^SE\\d{1,2}$");
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
    private final FileDownloader fileDownloader;
    private final TmdbRepository tmdbRepository;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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
                         FileDownloader fileDownloader,
                         TmdbRepository tmdbRepository,
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
        this.fileDownloader = fileDownloader;
        this.tmdbRepository = tmdbRepository;
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
            // 仅首次启动(Setting 缺失)才从 movie_version 文件播种版本 —— 文件在下载 diff.zip 时
            // 就被 zip 内的 movie_version 覆盖(先于 apply),无条件回写会把「已下载未应用」永久跳过:
            // apply 失败/中断/库回滚后,版本号被文件顶到高位,缺的 sql 文件永不补放(线上 1317-1339 整批丢失实证)。
            if (settingRepository.findById(MOVIE_VERSION).isEmpty()) {
                Path path = Utils.getDataPath("atv", "movie_version");
                if (Files.exists(path)) {
                    List<String> lines = Files.readAllLines(path);
                    if (!lines.isEmpty()) {
                        settingRepository.save(new Setting(MOVIE_VERSION, lines.get(0).trim()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        // init-xiaoya.sh 在基线升级时解包 data.zip 重灌基线并把镜像的 base_version 拷到 /data/atv/:
        // 标记存在=基线刚被重灌 → 版本号回到基线、中和 data.sql、清空 movie_diff 让全部 diff 重放。
        // (曾误读 /tmp/base_version,无人写该路径,分支从不触发 —— 存量部署的破坏性 data.sql 因此每启重放。)
        if (metaRepository.count() > 10000) {
            Path source = Utils.getDataPath("atv", "base_version");
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

                log.info("movie base restored, reset data.sql and clear movie_diff for full replay");
                writeText("data.sql", "SELECT 1;");
                try {
                    jdbcTemplate.update("DELETE FROM movie_diff");
                } catch (Exception e) {
                    log.warn("clear movie_diff after base restore failed", e);
                }
            }
        }

        neutralizeBaseDataScript();

        fixMetaId();
        runCmd();
        // 开机自检:补放 movie_diff 无记录(历史缺口/新文件)或 FAILED 未达重试上限的 diff 文件。
        // 表启用前放过的文件也没有记录 → 首次开机会整体重放一遍(DELETE+INSERT 幂等),顺带修复历史缺口。
        if (Files.exists(Utils.getDataPath("atv", "sql"))) {
            executor.execute(this::applyPendingDiffFiles);
        }
    }

    /**
     * 中和 xiaoya 基础数据脚本:spring.sql.init 每次启动都执行 data.sql,而它是
     * DROP+CREATE 全量还原 —— 每次重启把 MOVIE/META 打回基线,diff 行全灭
     * (线上「diff 应用成功后行消失」「重启后元数据丢失」两个形态的共同根因)。
     * 基线已载入(库里有数据)就把 data.sql 改写为空操作,后续重启不再破坏;
     * 本次已被抹掉的行,由清空 movie_diff 让全部 diff 重放来恢复(它们本就是基线之上的增量)。
     * 要重新灌基线:放回全量 data.sql 并清空 movie_diff。
     */
    private void neutralizeBaseDataScript() {
        try {
            Path dataSql = Utils.getDataPath("atv", "data.sql");
            if (!Files.exists(dataSql) || movieRepository.count() < 10000) {
                return;
            }
            String firstLine;
            try (var reader = Files.newBufferedReader(dataSql)) {
                firstLine = reader.readLine();
            }
            if (firstLine == null || !firstLine.startsWith("DROP TABLE")) {
                return;
            }
            writeText("data.sql", "SELECT 1;");
            jdbcTemplate.update("DELETE FROM movie_diff");
            log.info("neutralized base data.sql (was a destructive full restore) and cleared movie_diff for full replay");
        } catch (Exception e) {
            log.warn("neutralize base data.sql failed", e);
        }
    }

    private void runCmd() {
        try {
            Path path = Utils.getDataPath("atv", "cmd.sql");
            if (Files.exists(path)) {
                log.info("run sql from file {}", path);
                try {
                    jdbcTemplate.execute("RUNSCRIPT FROM '" + path + "'");
                } catch (Exception e) {
                    log.warn("execute sql file failed", e);
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
        String table = "id_generator";
        try {
            jdbcTemplate.execute("UPDATE " + table + " SET NEXT_ID = 500000 WHERE ENTITY_NAME = 'meta'");
        } catch (Exception e) {
            jdbcTemplate.execute("INSERT INTO " + table + " VALUES ('meta', 500000)");
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

    public void update() {
        getRemoteVersion(new Versions());
    }

    public String getRemoteVersion(Versions versions) {
        if (!environment.matchesProfiles("xiaoya")) {
            return "";
        }

        try {
            String remote = restTemplate.getForObject("https://d.har01d.cn/movie_version", String.class).trim();
            versions.setMovie(remote);
            String local = settingRepository.findById(MOVIE_VERSION).map(Setting::getValue).orElse("0.0").trim();
            String cached = getCachedVersion();
            versions.setCachedMovie(cached);
            // 不再要求 remote != cached:cached 文件只代表「已下载」,local(Setting)才代表「已应用」。
            // apply 失败后 local<remote 而 cached==remote,若按 cached 跳过会永久漏放(重复下载仅 48KB,可接受)。
            if (!local.equals(remote) && !downloading) {
                log.info("local: {} cached: {} remote: {}", local, cached, remote);
                executor.execute(() -> upgradeMovieData(local, remote));
            } else {
                log.debug("local: {} cached: {} remote: {}", local, cached, remote);
            }
            return remote;
        } catch (Exception e) {
            log.debug("", e);
        }
        return "";
    }

    private String getCachedVersion() {
        try {
            Path file = Utils.getDataPath("atv", "movie_version");
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
            Task task = fileDownloader.runTask("movie", remote);
            if (taskService.waitTaskFinish(task.getId(), 60)) {
                log.info("movie data downloaded");
                applyPendingDiffFiles();
            } else {
                log.warn("download movie data failed");
            }
        } catch (Exception e) {
            log.warn("", e);
        } finally {
            downloading = false;
        }
    }

    /** 应用待执行的 diff 文件:movie_diff 表为「已应用」事实来源 —— 无记录(新文件或历史缺口)、
     *  或 FAILED 且尝试不足 {@link #MAX_DIFF_ATTEMPTS} 才执行,SUCCESS 跳过。同一版本 json/sql 并存时
     *  JSON 优先(方言无关、JPA 落库);按版本升序,成功推进版本号。 */
    private void applyPendingDiffFiles() {
        Map<Double, Path> jsonFiles = diffFiles("json", ".json");
        Map<Double, Path> sqlFiles = diffFiles("sql", ".sql");
        TreeSet<Double> versions = new TreeSet<>(jsonFiles.keySet());
        versions.addAll(sqlFiles.keySet());
        for (Double version : versions) {
            String name = String.valueOf(version);
            if (!needsApply(name)) {
                continue;
            }
            applyDiff(name, jsonFiles.get(version), sqlFiles.get(version));
        }
    }

    /** atv/{dir} 下 {version}{suffix} 文件 → 版本号映射;目录缺失返回空。 */
    private Map<Double, Path> diffFiles(String dir, String suffix) {
        Map<Double, Path> result = new HashMap<>();
        try (Stream<Path> files = Files.list(Utils.getDataPath("atv", dir))) {
            files.filter(Files::isRegularFile)
                    .filter(e -> e.getFileName().toString().endsWith(suffix))
                    .forEach(e -> {
                        try {
                            result.put(getVersionNumber(e), e);
                        } catch (NumberFormatException ignored) {
                            log.debug("ignore non-versioned diff file: {}", e);
                        }
                    });
        } catch (Exception e) {
            log.debug("list atv/{} failed: {}", dir, e.getMessage());
        }
        return result;
    }

    /** 无记录 → 执行(新文件 + 表启用前已放过的历史文件,自动补放缺口);FAILED 且尝试<上限 → 重试;SUCCESS → 跳过。 */
    private boolean needsApply(String version) {
        try {
            Integer attempts = diffAttempts(version);
            if (attempts == null) {
                return true;
            }
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM movie_diff WHERE version = ?", String.class, version);
            return !DIFF_SUCCESS.equals(status) && attempts < MAX_DIFF_ATTEMPTS;
        } catch (Exception e) {
            log.debug("query movie_diff for {} failed, treat as pending: {}", version, e.getMessage());
            return true;
        }
    }

    /** 文件当前已尝试次数;无记录返回 null。 */
    private Integer diffAttempts(String version) {
        List<Integer> attempts = jdbcTemplate.queryForList(
                "SELECT attempts FROM movie_diff WHERE version = ?", Integer.class, version);
        return attempts.isEmpty() ? null : attempts.get(0);
    }

    private void recordDiff(String version, boolean success, int statements, int failed, int attempts) {
        try {
            jdbcTemplate.update("DELETE FROM movie_diff WHERE version = ?", version);
            jdbcTemplate.update("INSERT INTO movie_diff (version, status, statements, failed, attempts, updated_time) "
                    + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)", version,
                    success ? DIFF_SUCCESS : DIFF_FAILED, statements, failed, attempts);
        } catch (Exception e) {
            log.warn("record movie_diff {} failed", version, e);
        }
    }

    private double getVersionNumber(Path path) {
        return Double.parseDouble(getVersion(path));
    }

    private String getVersion(Path path) {
        String name = path.toFile().getName();
        int index = name.lastIndexOf('.');
        return name.substring(0, index);
    }

    private void applyDiff(String version, Path json, Path sql) {
        Integer previous = diffAttempts(version);
        int attempts = previous == null ? 0 : previous;
        int applied = 0;
        int failed = 0;
        // 失败重试:整个文件重放(upsert 幂等),累计尝试不超过上限
        while (attempts < MAX_DIFF_ATTEMPTS) {
            attempts++;
            try {
                int[] result = json != null ? applyJsonDiff(json) : executeSqlFile(sql);
                applied = result[0];
                failed = result[1];
            } catch (Exception e) {
                applied = 0;
                failed = 1;
                log.warn("apply diff {} (attempt {}/{}) failed", version, attempts, MAX_DIFF_ATTEMPTS, e);
            }
            if (failed == 0) {
                break;
            }
            log.warn("movie data attempt {}/{} for {} failed: {} statements failed, {} ok",
                    attempts, MAX_DIFF_ATTEMPTS, version, failed, applied);
        }
        boolean success = failed == 0;
        recordDiff(version, success, applied, failed, attempts);
        if (success) {
            settingRepository.save(new Setting(MOVIE_VERSION, version));
            log.info("movie data upgraded: {} ({} rows, {} attempts, {})", version, applied, attempts,
                    json != null ? "json" : "sql");
        } else {
            log.warn("movie data {} still failed after {} attempts ({} ok, {} failed), version not stamped",
                    version, attempts, applied, failed);
        }
    }

    /** JSON diff(json/{version}.json)应用:MOVIE/META 行经 JPA upsert(saveAll)+ 按 id 删除,
     *  方言无关;任何异常上抛由重试循环按整文件重放。返回 [行数, 0]。 */
    private int[] applyJsonDiff(Path file) throws IOException {
        MovieDiffPayload payload =
                objectMapper.readValue(file.toFile(), cn.har01d.alist_tvbox.dto.MovieDiffPayload.class);
        int rows = 0;
        if (payload.movieDeletes() != null && !payload.movieDeletes().isEmpty()) {
            movieRepository.deleteAllById(payload.movieDeletes());
            rows += payload.movieDeletes().size();
        }
        if (payload.metaDeletes() != null && !payload.metaDeletes().isEmpty()) {
            metaRepository.deleteAllById(payload.metaDeletes());
            rows += payload.metaDeletes().size();
        }
        if (payload.movieUpserts() != null && !payload.movieUpserts().isEmpty()) {
            movieRepository.saveAll(payload.movieUpserts());
            rows += payload.movieUpserts().size();
        }
        if (payload.metaUpserts() != null && !payload.metaUpserts().isEmpty()) {
            metaRepository.saveAll(payload.metaUpserts().stream().map(this::toMeta).toList());
            rows += payload.metaUpserts().size();
        }
        return new int[]{rows, 0};
    }

    /** META DTO → 实体:movieId/tmdbId 换实体引用(getReferenceById 不发 SQL,关联缺失时落库报错走重试)。 */
    private Meta toMeta(MovieDiffPayload.MetaPayload row) {
        Meta meta = new Meta();
        meta.setId(row.id());
        meta.setPath(row.path());
        meta.setName(row.name());
        meta.setYear(row.year());
        meta.setScore(row.score());
        meta.setMovie(row.movieId() == null ? null : movieRepository.getReferenceById(row.movieId()));
        meta.setType(row.type());
        meta.setTid(row.tid());
        meta.setTmId(row.tmId());
        meta.setTmdb(row.tmdbId() == null || tmdbRepository == null ? null
                : tmdbRepository.getReferenceById(row.tmdbId()));
        meta.setSiteId(row.siteId());
        meta.setDisabled(Boolean.TRUE.equals(row.disabled()));
        meta.setTime(row.time() == null ? Instant.now() : Instant.ofEpochMilli(row.time()));
        return meta;
    }

    /** 执行单个 SQL diff 文件(旧格式回落),返回 [ok, failed];逐条失败降级记录,不中断其它语句。 */
    private int[] executeSqlFile(Path file) {
        int applied = 0;
        int failed = 0;
        try {
            H2SqlConverter.Dialect dialect = H2SqlConverter.detect(environment);
            List<String> lines = Files.readAllLines(file);
            if (dialect == H2SqlConverter.Dialect.H2) {
                for (String line : lines) {
                    try {
                        jdbcTemplate.execute(line);
                        applied++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("execute sql failed: {}", line.length() > 120 ? line.substring(0, 120) + "..." : line, e);
                    }
                }
            } else {
                // diff files are H2 dialect (U& escapes, "PUBLIC" identifiers) — convert
                // each statement to the target dialect and apply in batches, falling back
                // to per-statement execution so one bad line never aborts the whole file.
                List<String> batch = new ArrayList<>(BATCH_SIZE);
                for (String line : lines) {
                    String sql = H2SqlConverter.convert(line, dialect);
                    if (sql == null) {
                        continue;
                    }
                    batch.add(sql);
                    if (batch.size() >= BATCH_SIZE) {
                        int size = batch.size();
                        int batchFailed = executeBatch(batch);
                        applied += size - batchFailed;
                        failed += batchFailed;
                    }
                }
                int size = batch.size();
                int batchFailed = executeBatch(batch);
                applied += size - batchFailed;
                failed += batchFailed;
            }
        } catch (Exception e) {
            log.warn("execute sql file failed: {}", file, e);
        }
        return new int[]{applied, failed};
    }

    /** 批量执行,失败降级逐条;返回失败条数。 */
    private int executeBatch(List<String> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            jdbcTemplate.batchUpdate(batch.toArray(new String[0]));
            batch.clear();
            return 0;
        } catch (Exception e) {
            log.debug("batch update failed, falling back to per-statement execution", e);
            int failed = 0;
            for (String sql : batch) {
                try {
                    jdbcTemplate.execute(sql);
                } catch (Exception ex) {
                    failed++;
                    log.warn("execute sql failed: {}", sql.length() > 120 ? sql.substring(0, 120) + "..." : sql, ex);
                }
            }
            batch.clear();
            return failed;
        }
    }

    public String getAppRemoteVersion() {
        try {
            return restTemplate.getForObject("https://d.har01d.cn/app.version.txt", String.class);
        } catch (Exception e) {
            log.warn("", e);
        }
        return "";
    }

    public String getAListRemoteVersion() {
        if (environment.matchesProfiles("standalone")) {
            try {
                return restTemplate.getForObject("https://d.har01d.cn/alist.version.txt", String.class);
            } catch (Exception e) {
                log.warn("", e);
            }
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
        return getByName(name, null);
    }

    public Movie getByName(String name, Integer year) {
        try {
            // [dbid-x] 目录标记(追剧转存目录自带豆瓣 id):直读精确命中;未命中也剥离标记,防污染名称匹配
            String dbid = TextUtils.parseMetaIdTag(name, "dbid");
            if (dbid != null) {
                Movie tagged = movieRepository.findById(Integer.valueOf(dbid)).orElse(null);
                if (tagged != null) {
                    log.debug("match by dbid tag: {}", dbid);
                    return tagged;
                }
            }
            name = TextUtils.stripMetaIdTags(name);

            Alias alias = aliasRepository.findById(name).orElse(null);
            if (alias != null) {
                log.debug("name: {} alias: {}", name, alias.getAlias());
                return alias.getMovie();
            }

            year = year == null ? getYearFromText(name) : year;
            name = TextUtils.cleanMediaTitle(name);
            name = TextUtils.collapseCjkSpaces(TextUtils.fixName(name));
            if (name.isEmpty()) {
                return null;
            }

            alias = aliasRepository.findById(name).orElse(null);
            if (alias != null) {
                log.debug("name: {} alias: {}", name, alias.getAlias());
                return alias.getMovie();
            }

            List<Movie> candidates = movieRepository.getByName(name);
            log.debug("search local Douban movie: name='{}', year={}, matches={}",
                    name, year, candidates == null ? 0 : candidates.size());
            Movie movie = pickBest(candidates, year);
            if (movie != null) {
                return movie;
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

                candidates = movieRepository.getByName(name);
                log.debug("search local Douban movie: name='{}', year={}, matches={}",
                        name, year, candidates == null ? 0 : candidates.size());
                movie = pickBest(candidates, year);
                if (movie != null) {
                    return movie;
                }
            }

            // no exact-name match: fall back to name-contains scoped by the extracted
            // year, then pick the best-matching name (exact > shortest > first).
            // Skip for a bare season token (第一季/Season 1/S01): it is not a title and
            // the LIKE would match every season-N show of that year (wrong title).
            if (year != null && !isSeasonOnly(name)) {
                List<Movie> matches = movieRepository.findByYearAndNameContains(year, name, Pageable.ofSize(10)).getContent();
                log.debug("search local Douban movie by year/name: name='{}', year={}, matches={}",
                        name, year, matches.size());
                return pickBestName(matches, name);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        return null;
    }

    // among same-name candidates, pick the one whose year is closest to the target
    // (null-year candidates are skipped; ties prefer the smaller year). With no
    // target year or a single candidate, keep the previous first-match behavior.
    static Movie pickBest(List<Movie> movies, Integer year) {
        if (movies == null || movies.isEmpty()) {
            return null;
        }
        if (year == null || movies.size() == 1) {
            return movies.get(0);
        }
        Movie best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (Movie m : movies) {
            Integer y = m.getYear();
            if (y == null) {
                continue;
            }
            int delta = Math.abs(y - year);
            if (best == null || delta < bestDelta || (delta == bestDelta && y < best.getYear())) {
                bestDelta = delta;
                best = m;
            }
        }
        return best != null ? best : movies.get(0);
    }

    // true when the (already fixName'd) search key is only a season marker, i.e. not a
    // discriminative title. Used to skip the year-scoped name-contains fallback.
    static boolean isSeasonOnly(String name) {
        return name != null && SEASON_ONLY.matcher(name).matches();
    }

    // among name-contains candidates, prefer an exact name, else the shortest name
    static Movie pickBestName(List<Movie> movies, String name) {
        if (movies == null || movies.isEmpty()) {
            return null;
        }
        Movie best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Movie m : movies) {
            String n = m.getName();
            if (name.equals(n)) {
                return m;
            }
            int len = n == null ? Integer.MAX_VALUE : n.length();
            if (best == null || len < bestLen) {
                bestLen = len;
                best = m;
            }
        }
        return best;
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
        Path path = Utils.getDataPath("atv", "failed.txt");
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
        Path path = Utils.getIndexPath(String.valueOf(siteId), "custom_index.txt");
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

        writeText("paths.txt", String.join("\n", paths));
        writeText("failed.txt", String.join("\n", failed));
    }

    private static void writeText(String name, String content) {
        try {
            Files.writeString(Utils.getDataPath("atv", name), content);
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

    // Extract a release year to disambiguate same-name titles. An explicit title
    // belongs to the current search result and therefore takes precedence over the
    // mounted path, which may be an opaque share token or contain unrelated digits.
    public Integer getYear(String name, String path) {
        Integer year = getYearFromText(name);
        if (year != null) {
            return year;
        }
        return getYearFromPath(path);
    }

    static Integer getYearFromText(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        int max = LocalDate.now().getYear() + 3;
        Matcher m = YEAR_PATTERN.matcher(text);
        while (m.find()) {
            int year = Integer.parseInt(m.group(1));
            if (year > 1960 && year < max) {
                return year;
            }
        }
        m = YEAR2_PATTERN.matcher(text);
        while (m.find()) {
            int year = Integer.parseInt(m.group(1));
            if (year > 1960 && year < max) {
                return year;
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

    public Page<Movie> localSearch(String text, Integer year) {
        if (year == null) {
            return movieRepository.findByNameContains(text, Pageable.ofSize(10));
        }
        return movieRepository.findByYearAndNameContains(year, text, Pageable.ofSize(10));
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

    public Movie getDetail(Integer id) {
        Movie movie = movieRepository.findById(id).orElse(null);
        log.debug("{} {}", id, movie);
        return movie;
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
                .addHeader("User-Agent", appProperties.getUserAgent())
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

    public void deleteAll() {
        List<Meta> list = metaRepository.findByMovieNull();
        log.info("delete {} meta", list.size());
        metaRepository.deleteAll(list);
    }
}
