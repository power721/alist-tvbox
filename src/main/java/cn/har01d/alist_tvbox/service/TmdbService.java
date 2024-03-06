package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.dto.IdName;
import cn.har01d.alist_tvbox.dto.MetaDto;
import cn.har01d.alist_tvbox.dto.TmdbDto;
import cn.har01d.alist_tvbox.dto.TmdbList;
import cn.har01d.alist_tvbox.entity.Meta;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.entity.Tmdb;
import cn.har01d.alist_tvbox.entity.TmdbMeta;
import cn.har01d.alist_tvbox.entity.TmdbMetaRepository;
import cn.har01d.alist_tvbox.entity.TmdbRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.TextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.TMDB_API_KEY;

@Slf4j
@Service
public class TmdbService {
    private static final Pattern YEAR2_PATTERN = Pattern.compile("(\\d{4})");
    private final TmdbRepository tmdbRepository;
    private final TmdbMetaRepository tmdbMetaRepository;
    private final MetaRepository metaRepository;
    private final SettingRepository settingRepository;
    private final SiteService siteService;
    private final TaskService taskService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final int rateLimit = 2000;
    private Map<String, String> countryNames = new HashMap<>();

    private String apiKey;
    private long lastRequestTime;
    private int siteId = 1;  // TODO: move to context

    public TmdbService(TmdbRepository tmdbRepository,
                       TmdbMetaRepository tmdbMetaRepository,
                       MetaRepository metaRepository,
                       SettingRepository settingRepository,
                       SiteService siteService,
                       TaskService taskService,
                       RestTemplateBuilder builder,
                       ObjectMapper objectMapper) {
        this.tmdbRepository = tmdbRepository;
        this.tmdbMetaRepository = tmdbMetaRepository;
        this.metaRepository = metaRepository;
        this.settingRepository = settingRepository;
        this.siteService = siteService;
        this.taskService = taskService;
        restTemplate = builder.build();
        this.objectMapper = objectMapper;
    }

    public void setApiKey(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            this.apiKey = TMDB_API_KEY;
        } else {
            this.apiKey = apiKey;
        }
    }

    @PostConstruct
    public void init() {
        setApiKey(settingRepository.findById("tmdb_api_key").map(Setting::getValue).orElse(""));

        try {
            sync();
        } catch (Exception e) {
            log.warn("", e);
        }

        loadCountries();
    }

    private void loadCountries() {
        try {
            var resource = new FileSystemResource("/countries.json");
            String json = resource.getContentAsString(StandardCharsets.UTF_8);
            countryNames = objectMapper.readValue(json, Map.class);
            log.debug("load {} countries", countryNames.size());
        } catch (Exception e) {
            log.warn("", e);
        }

        countryNames.put("China", "中国");
        countryNames.put("Hong Kong", "中国香港");
        countryNames.put("Taiwan", "中国台湾");
    }

    @Async
    public void syncMeta() {
        var list = tmdbMetaRepository.findAll();
        if (list.isEmpty()) {
            return;
        }
        var task = taskService.addSyncMeta();
        taskService.startTask(task.getId());
        int count = list.size();
        log.info("sync {} meta", count);
        int start = 0;
        while (start < count) {
            int end = start + 1000;
            if (end > count) {
                end = count;
            }
            List<Meta> result = new ArrayList<>();
            for (var meta : list.subList(start, end)) {
                result.add(syncMeta(meta));
            }
            metaRepository.saveAll(result);
            taskService.updateTaskSummary(task.getId(), "已经同步" + end + "/" + count);
            start = end;
        }

        taskService.completeTask(task.getId());
        log.info("sync meta completed");
    }

    public void sync() {
        var page = metaRepository.findAll(PageRequest.of(1, 1, Sort.Direction.DESC, "id"));
        if (page.hasContent() && page.getContent().get(0).getId() < 500000) {
            new Thread(this::syncMeta).start();
        }
    }

    public boolean addMeta(MetaDto dto) {
        if (dto.getTmId() == null || dto.getTmId() < 1) {
            throw new BadRequestException("TMDB ID不正确");
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
        TmdbMeta meta = tmdbMetaRepository.findByPath(path);
        if (meta == null) {
            meta = new TmdbMeta();
            meta.setPath(path);
            meta.setType(dto.getType());
            meta.setSiteId(dto.getSiteId());
        }
        Tmdb movie = getById(dto.getType(), dto.getTmId());
        if (movie != null) {
            meta.setTmdb(movie);
            meta.setTmId(movie.getTmdbId());
            meta.setYear(movie.getYear());
            meta.setName(movie.getName());
            if (StringUtils.isNotBlank(movie.getScore())) {
                meta.setScore((int) (Double.parseDouble(movie.getScore()) * 10));
            }
            saveMeta(meta);
            return true;
        }
        return false;
    }

    public Tmdb getByPath(String path) {
        try {
            TmdbMeta meta = tmdbMetaRepository.findByPath(path);
            if (meta != null) {
                return meta.getTmdb();
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        return null;
    }

    private void saveMeta(TmdbMeta tmdbMeta) {
        tmdbMetaRepository.save(tmdbMeta);
        metaRepository.save(syncMeta(tmdbMeta));
        log.debug("saveMeta: {}", tmdbMeta);
    }

    private Meta syncMeta(TmdbMeta tmdbMeta) {
        Meta meta = metaRepository.findByPath(tmdbMeta.getPath());
        if (meta == null) {
            meta = new Meta();
        }
        meta.setPath(tmdbMeta.getPath());
        if (tmdbMeta.getYear() != null) {
            meta.setYear(tmdbMeta.getYear());
        }
        meta.setName(tmdbMeta.getName());
        if (tmdbMeta.getScore() != null) {
            meta.setScore(tmdbMeta.getScore());
        }
        meta.setSiteId(tmdbMeta.getSiteId());
        if (meta.getSiteId() == null) {
            meta.setSiteId(1);
        }
        meta.setType(tmdbMeta.getType());
        meta.setTmId(tmdbMeta.getTmId());
        meta.setTmdb(tmdbMeta.getTmdb());
        meta.setTime(tmdbMeta.getTime());
        return meta;
    }

    public void delete(Integer id) {
        var meta = tmdbMetaRepository.findById(id).orElseThrow();
        delete(meta);
    }

    public void delete(TmdbMeta meta) {
        var list = metaRepository.findByTmdb(meta.getTmdb());
        for (var db : list) {
            var movie = db.getMovie();
            if (movie == null) {
                metaRepository.delete(db);
                log.info("delete douban meta {}", db.getId());
            } else {
                db.setTmdb(null);
                db.setYear(movie.getYear());
                db.setName(movie.getName());
                if (StringUtils.isNotBlank(movie.getDbScore())) {
                    db.setScore((int) (Double.parseDouble(movie.getDbScore()) * 10));
                }
                metaRepository.save(db);
                log.info("update douban meta {}", db.getId());
            }
        }
        log.info("delete {} {}", meta.getId(), meta.getPath());
        tmdbMetaRepository.delete(meta);
    }

    public void batchDelete(List<Integer> ids) {
        tmdbMetaRepository.findAllById(ids).forEach(meta -> {
            try {
                delete(meta);
            } catch (Exception e) {
                log.warn("delete {} {} failed", meta.getId(), meta.getPath(), e);
            }
        });
    }

    public boolean updateMetaMovie(Integer id, MetaDto dto) {
        if (dto.getTmId() == null || dto.getTmId() < 1) {
            throw new BadRequestException("TMDB ID不正确");
        }
        var meta = tmdbMetaRepository.findById(id).orElse(null);
        if (meta == null) {
            return false;
        }
        Tmdb movie = getDetails(dto.getType(), dto.getTmId());
        if (movie != null) {
            meta.setType(dto.getType());
            meta.setSiteId(dto.getSiteId());
            meta.setTmdb(movie);
            meta.setTmId(movie.getTmdbId());
            meta.setYear(movie.getYear());
            meta.setName(movie.getName());
            if (StringUtils.isNotBlank(movie.getScore())) {
                meta.setScore((int) (Double.parseDouble(movie.getScore()) * 10));
            }

            saveMeta(meta);
            return true;
        }
        return false;
    }

    @Async
    public void scrape(Integer siteId, String indexName, boolean force) throws IOException {
        Path path = Paths.get("/data/index", String.valueOf(siteId), indexName + ".txt");
        if (!Files.exists(path)) {
            throw new BadRequestException("索引文件不存在");
        }
        log.debug("readIndexFile: {}", path);
        List<String> lines = Files.readAllLines(path);
        log.info("get {} lines from index file {}", lines.size(), path);
        Site site = siteService.getById(siteId);
        Task task = taskService.addScrapeTask(site);
        this.siteId = siteId;
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
                log.debug("ignore line {}", line);
                continue;
            }

            String path = line.split("#")[0];
            if (metaRepository.existsByPath(path)) {
                log.debug("ignore path {}", path);
                continue;
            }

            try {
                log.debug("handle {} {}", i, line);
                taskService.updateTaskSummary(task.getId(), (i + 1) + ":" + line);
                String type = guessType(line);
                Tmdb movie = handleIndexLine(i, line, type == null ? "tv" : type, force, failed);
                if (movie == null && type == null) {
                    handleIndexLine(i, line, "movie", force, failed);
                }
                if (movie != null) {
                    count++;
                    taskService.updateTaskData(task.getId(), "成功刮削数量：" + count);
                } else {
                    log.warn("刮削失败：{}", line.split("#")[0]);
                    paths.add(line.split("#")[0]);
                }

                if (i > 0 && i % 10 == 0) {
                    writeText("/data/atv/tmdb_paths.txt", String.join("\n", paths));
                    writeText("/data/atv/tmdb_failed.txt", String.join("\n", failed));
                }
            } catch (Exception e) {
                log.warn("{}: {}", i, line, e);
            }
        }

        taskService.completeTask(task.getId());

        writeText("/data/atv/tmdb_paths.txt", String.join("\n", paths));
        writeText("/data/atv/tmdb_failed.txt", String.join("\n", failed));
    }

    private String guessType(String path) {
        if (path.contains("电影") || path.toLowerCase().contains("movie")) {
            return "movie";
        }
        if (path.contains("电视剧") || path.contains("连续剧") || path.contains("剧集") || path.contains("短剧")
                || path.contains("国产剧") || path.contains("港台剧") || path.contains("港剧") || path.contains("台剧")
                || path.contains("美剧") || path.contains("日剧") || path.contains("韩剧")
                || path.toLowerCase().contains("season")) {
            return "tv";
        }
        return null;
    }

    private static void writeText(String path, String content) {
        try {
            Files.writeString(Paths.get(path), content);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private Set<String> loadFailed() {
        Path path = Paths.get("/data/atv/tmdb_failed.txt");
        try {
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path).stream().filter(e -> !e.startsWith("/")).toList();
                return new HashSet<>(lines);
            }
        } catch (IOException e) {
            log.warn("", e);
        }
        return new HashSet<>();
    }

    private Tmdb handleIndexLine(int id, String line, String type, boolean force, Set<String> failed) {
        String[] parts = line.split("#");
        String path = parts[0];

        TmdbMeta meta = tmdbMetaRepository.findByPath(path);
        if (meta == null) {
            meta = new TmdbMeta();
            meta.setPath(path);
            meta.setSiteId(siteId);
        } else if (meta.getTmdb() != null && !force) {
            return meta.getTmdb();
        }

        Integer year = getYearFromPath(path);
        String name = "";
        Tmdb movie = null;
        if (parts.length == 2) {
            name = TextUtils.fixName(parts[1]);
        } else if (parts.length > 2) {
            name = TextUtils.fixName(parts[1]);
            String number = parts[2];
            log.debug("{} {}", name, number);
            if (number.length() > 5) {
                try {
                    movie = getById(type, Integer.parseInt(number));
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

        String original = name;
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
                movie = search(type, newname, year);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            if (movie == null) {
                parts = original.split("[ \t\r\n\\[\\]、【】()（）《》.丨_-]+");
                if (parts.length > 1) {
                    int count = 0;
                    for (int i = 0; i < parts.length && count < 3; i++) {
                        String n = TextUtils.fixName(parts[i]);
                        if (n.length() > 1 && !failed.contains(n)) {
                            log.info("[{}] - handle name: {} - path: {}", id, n, path);
                            count++;
                            movie = search(type, n, year, true);
                            if (movie != null) {
                                break;
                            }
                            failed.add(n);
                        }
                    }
                }
            }

            if (failed.contains(getParent(path))) {
                return null;
            }

            if (movie != null && TextUtils.isNormal(movie.getName())) {
                meta.setPath(path);
                meta.setTmdb(movie);
                meta.setTmId(movie.getTmdbId());
                meta.setYear(movie.getYear());
                meta.setName(movie.getName());
                if (StringUtils.isNotBlank(movie.getScore())) {
                    meta.setScore((int) (Double.parseDouble(movie.getScore()) * 10));
                }
                saveMeta(meta);
                log.info("{} - add {} '{}' for path {}", id, movie.getId(), movie.getName(), path);
                return movie;
            }
        }

        if (movie != null && TextUtils.isNormal(name) && TextUtils.isNormal(movie.getName())) {
            log.info("[{}] add {} {} for path {}", id, movie.getId(), movie.getName(), path);
            return updateMeta(path, meta, movie);
        } else {
            log.warn("add failed: {} {}", name, path);
            failed.add(name);
        }

        return null;
    }

    public Tmdb getByName(String name) {
        try {
            name = TextUtils.fixName(name);

            List<Tmdb> movies = tmdbRepository.getByName(name);
            if (movies != null && !movies.isEmpty()) {
                return movies.get(0);
            }

            String newName = TextUtils.updateName(name);
            if (!newName.equals(name)) {
                name = newName;
                log.debug("search by name: {}", name);

                movies = tmdbRepository.getByName(name);
                if (movies != null && !movies.isEmpty()) {
                    return movies.get(0);
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        return null;
    }

    private boolean isCancelled(Integer taskId) {
        Task task = taskService.getById(taskId);
        return task.getStatus() == TaskStatus.COMPLETED && task.getResult() == TaskResult.CANCELLED;
    }

    private Tmdb updateMeta(String path, TmdbMeta meta, Tmdb movie) {
        meta.setPath(path);
        meta.setTmdb(movie);
        meta.setTmId(movie.getTmdbId());
        meta.setYear(movie.getYear());
        meta.setName(movie.getName());
        if (StringUtils.isNotBlank(movie.getScore())) {
            meta.setScore((int) (Double.parseDouble(movie.getScore()) * 10));
        }
        saveMeta(meta);
        return movie;
    }

    private boolean isSpecialFolder(String name) {
        if (name.matches("Season \\d+")) {
            return true;
        }
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

    public boolean scrape(Integer id, String type, String name) {
        var meta = tmdbMetaRepository.findById(id).orElse(null);
        if (meta == null) {
            return false;
        }
        Tmdb movie = scrape(type, name, meta);
        if (movie != null) {
            meta.setTmdb(movie);
            meta.setTmId(movie.getTmdbId());
            meta.setYear(movie.getYear());
            meta.setName(movie.getName());
            if (StringUtils.isNotBlank(movie.getScore())) {
                meta.setScore((int) (Double.parseDouble(movie.getScore()) * 10));
            }
            saveMeta(meta);
            return true;
        }
        return false;
    }

    public Tmdb scrape(String type, String name, TmdbMeta meta) {
        if (StringUtils.isBlank(name)) {
            name = TextUtils.fixName(getNameFromPath(meta.getPath()));
        }
        Integer year = getYearFromPath(meta.getPath());
        Tmdb movie = search(type, name, year);
        if (movie != null) {
            meta.setTmdb(movie);
            meta.setTmId(movie.getTmdbId());
            meta.setYear(movie.getYear());
            meta.setName(movie.getName());
            if (StringUtils.isNotBlank(movie.getScore())) {
                meta.setScore((int) (Double.parseDouble(movie.getScore()) * 10));
            }
            saveMeta(meta);
        } else {
            movie = search("tv", name, year);
            if (movie != null) {
                meta.setTmdb(movie);
                meta.setTmId(movie.getTmdbId());
                meta.setYear(movie.getYear());
                meta.setName(movie.getName());
                if (StringUtils.isNotBlank(movie.getScore())) {
                    meta.setScore((int) (Double.parseDouble(movie.getScore()) * 10));
                }
                saveMeta(meta);
            }
        }
        return movie;
    }

    public Tmdb search(String type, String name, Integer year) {
        return search(type, name, year, false);
    }

    public Tmdb search(String type, String name, Integer year, boolean match) {
        return search(type, name, Objects.toString(year, ""), match);
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public Tmdb search(String type, String name, String year, boolean match) {
        String url = "https://api.themoviedb.org/3/search/" + type + "?query=" + name + "&api_key=" + apiKey + "&language=zh-CN&year=" + year;
        long now = System.currentTimeMillis();
        if (!log.isDebugEnabled() && TMDB_API_KEY.equals(apiKey) && now - lastRequestTime < rateLimit) {
            sleep(lastRequestTime + rateLimit - now);
        }
        log.debug("search: {}", url);
        TmdbList list = restTemplate.getForObject(url, TmdbList.class);
        lastRequestTime = now;
        if (list != null && list.getResults() != null) {
            log.debug("get {} reasults", list.getResults().size());
            for (TmdbDto dto : list.getResults()) {
                log.debug("{} - {} {}", name, dto.getId(), StringUtils.isBlank(dto.getName()) ? dto.getTitle() : dto.getName());
                if (name.equals(dto.getTitle()) || name.equals(dto.getName())) {
                    return getById(type, dto.getId());
                }
            }
            if (match) {
                return null;
            }
            if (!list.getResults().isEmpty()) {
                return getById(type, list.getResults().get(0).getId());
            }
        }
        return null;
    }

    public Tmdb getById(String type, Integer id) {
        return tmdbRepository.findByTypeAndTmdbId(type, id).orElseGet(() -> getDetails(type, id));
    }

    public Tmdb getDetails(String type, Integer id) {
        String url = "https://api.themoviedb.org/3/" + type + "/" + id + "?language=zh-CN&append_to_response=credits&api_key=" + apiKey;
        long now = System.currentTimeMillis();
        if (!log.isDebugEnabled() && TMDB_API_KEY.equals(apiKey) && now - lastRequestTime < rateLimit) {
            sleep(lastRequestTime + rateLimit - now);
        }
        log.debug("getDetails: {}", url);
        TmdbDto dto = restTemplate.getForObject(url, TmdbDto.class);
        lastRequestTime = now;
        log.debug("getDetails: {} {} {}", type, id, dto);
        Tmdb tmdb = new Tmdb();
        tmdb.setType(type);
        tmdb.setTmdbId(dto.getId());
        tmdb.setName(StringUtils.isBlank(dto.getName()) ? dto.getTitle() : dto.getName());
        tmdb.setDescription(TextUtils.truncate(dto.getOverview(), 200));
        if (dto.getDate() != null) {
            tmdb.setYear(dto.getDate().getYear());
        }
        if (dto.getFirstDate() != null) {
            tmdb.setYear(dto.getFirstDate().getYear());
        }
        if (tmdb.getYear() == null) {
            tmdb.setYear(getYearFromPath(tmdb.getName()));
        }
        if (!"0.0".equals(dto.getScore())) {
            tmdb.setScore(dto.getScore());
        }
        if (dto.getScore() != null && dto.getScore().length() > 3) {
            tmdb.setScore(dto.getScore().substring(0, 3));
        }

        tmdb.setCover("https://media.themoviedb.org/t/p/w300_and_h450_bestv2" + dto.getCover());
        tmdb.setGenre(dto.getGenres().stream().map(IdName::getName).map(this::fixGenre).collect(Collectors.joining(",")));
        tmdb.setLanguage(dto.getLanguage().stream().map(IdName::getName).map(this::fixLanguage).collect(Collectors.joining(",")));
        tmdb.setCountry(dto.getCountry().stream().map(IdName::getName).map(this::fixCountry).collect(Collectors.joining(",")));

        if (dto.getCredits() != null) {
            if (dto.getCredits().getCast() != null) {
                tmdb.setActors(dto.getCredits().getCast().stream().map(IdName::getName).limit(3).collect(Collectors.joining(",")));
            }
            if (dto.getCredits().getCrew() != null) {
                tmdb.setDirectors(dto.getCredits().getCrew().stream().filter(e -> "Directing".equals(e.getDepartment())).map(IdName::getName).limit(3).collect(Collectors.joining(",")));
                tmdb.setEditors(dto.getCredits().getCrew().stream().filter(e -> "Writing".equals(e.getDepartment())).map(IdName::getName).limit(3).collect(Collectors.joining(",")));
            }
        }

        log.debug("{}", tmdb);
        return tmdbRepository.save(tmdb);
    }

    private String fixGenre(String name) {
        if ("Sci-Fi & Fantasy".equals(name)) {
            return "科幻,幻想";
        }
        return name;
    }

    private String fixLanguage(String name) {
        if ("English".equals(name)) {
            return "英语";
        }
        if ("日本語".equals(name)) {
            return "日语";
        }
        if ("한국어/조선말".equals(name)) {
            return "韩语";
        }
        return name;
    }

    private String fixCountry(String name) {
        return countryNames.getOrDefault(name, name);
    }

    public static Integer getYearFromPath(String path) {
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

    private static String getNameFromPath(String path) {
        String[] parts = path.split("#");
        if (parts.length > 1) {
            return parts[1];
        }
        int index = path.lastIndexOf('/');
        if (index > 0) {
            return path.substring(index + 1);
        }
        return path;
    }

}
