package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.Subtitle;
import cn.har01d.alist_tvbox.entity.AListAlias;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.Meta;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.Tmdb;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.model.FileNameInfo;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.model.FsDetail;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.TextUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.ALIST_PIC;
import static cn.har01d.alist_tvbox.util.Constants.FILE;
import static cn.har01d.alist_tvbox.util.Constants.FOLDER;
import static cn.har01d.alist_tvbox.util.Constants.PLAYLIST;
import static cn.har01d.alist_tvbox.util.Constants.USER_AGENT;

@Slf4j
@Service
public class TvBoxService {
    private static final Pattern NUMBER = Pattern.compile("^Season (\\d{1,2}).*");
    private static final Pattern NUMBER1 = Pattern.compile("^SE(\\d{1,2}).*");
    private static final Pattern NUMBER2 = Pattern.compile("^S(\\d{1,2}).*");
    private static final Pattern NUMBER3 = Pattern.compile("^第(.{1,2})季.*");

    private final AccountRepository accountRepository;
    private final AListAliasRepository aliasRepository;
    private final ShareRepository shareRepository;
    private final MetaRepository metaRepository;

    private final AListService aListService;
    private final IndexService indexService;
    private final SiteService siteService;
    private final AppProperties appProperties;
    private final DoubanService doubanService;
    private final TmdbService tmdbService;
    private final SubscriptionService subscriptionService;
    private final ConfigFileService configFileService;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final Cache<Integer, List<String>> cache = Caffeine.newBuilder()
            .maximumSize(10)
            .build();

    private final List<FilterValue> filters = Arrays.asList(
            new FilterValue("原始顺序", ""),
            new FilterValue("时间⬆️", "time,asc"),
            new FilterValue("时间⬇️", "time,desc"),
            new FilterValue("名字⬆️", "name,asc"),
            new FilterValue("名字⬇️", "name,desc"),
            new FilterValue("大小⬆️", "size,asc"),
            new FilterValue("大小⬇️", "size,desc")
    );
    private final List<FilterValue> filters2 = Arrays.asList(
            new FilterValue("原始顺序", ""),
            new FilterValue("评分⬇️", "score,desc;year,desc"),
            new FilterValue("评分⬆️", "score,asc;year,desc"),
            new FilterValue("年份⬇️", "year,desc;score,desc"),
            new FilterValue("年份⬆️", "year,asc;score,desc"),
            new FilterValue("名字⬇️", "name,desc;year,desc;score,desc"),
            new FilterValue("名字⬆️", "name,asc;year,desc;score,desc"),
            new FilterValue("时间⬇️", "time,desc"),
            new FilterValue("时间⬆️", "time,asc"),
            new FilterValue("ID⬇️", "movie_id,desc"),
            new FilterValue("ID⬆️", "movie_id,asc")
    );
    private final List<FilterValue> filters3 = Arrays.asList(
            new FilterValue("高分", "high"),
            new FilterValue("普通", "normal"),
            new FilterValue("全部", ""),
            new FilterValue("无分", "no"),
            new FilterValue("低分", "low")
    );
    private List<Site> sites = new ArrayList<>();

    public TvBoxService(AccountRepository accountRepository,
                        AListAliasRepository aliasRepository,
                        ShareRepository shareRepository,
                        MetaRepository metaRepository,
                        AListService aListService,
                        IndexService indexService,
                        SiteService siteService,
                        AppProperties appProperties,
                        DoubanService doubanService,
                        TmdbService tmdbService,
                        SubscriptionService subscriptionService,
                        ConfigFileService configFileService,
                        ObjectMapper objectMapper,
                        Environment environment) {
        this.accountRepository = accountRepository;
        this.aliasRepository = aliasRepository;
        this.shareRepository = shareRepository;
        this.metaRepository = metaRepository;
        this.aListService = aListService;
        this.indexService = indexService;
        this.siteService = siteService;
        this.appProperties = appProperties;
        this.doubanService = doubanService;
        this.tmdbService = tmdbService;
        this.subscriptionService = subscriptionService;
        this.configFileService = configFileService;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    private Site getXiaoyaSite() {
        for (Site site : siteService.list()) {
            if (site.isXiaoya() && site.isSearchable() && !site.isDisabled()) {
                return site;
            }
        }
        return null;
    }

    public CategoryList getCategoryList(Integer type) {
        CategoryList result = new CategoryList();

        if (type == 0) {
            Site site = getXiaoyaSite();
            if (site != null) {
                setTypes(result, site);
            }
        } else {
            int id = 1;
            for (Site site : siteService.list()) {
                Category category = new Category();
                category.setType_id(site.getId() + "$/" + "$1");
                category.setType_name(site.getName());
                result.getCategories().add(category);

                result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
                if (id++ == 1) {
                    addMyFavorite(result);
                }
            }
        }

        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());
        log.debug("category: {}", result);
        return result;
    }

    private void setTypes(CategoryList result, Site site) {
        int year = LocalDate.now().getYear();
        List<FilterValue> years = new ArrayList<>();
        years.add(new FilterValue("全部", ""));
        for (int i = 0; i < 20; ++i) {
            years.add(new FilterValue(String.valueOf(year - i), String.valueOf(year - i)));
        }
        years.add(new FilterValue("其它", "others"));

        Category category = new Category();
        category.setType_id(site.getId() + "$/$0");
        category.setType_name("\uD83C\uDFAC" + site.getName());
        category.setType_flag(0);
        result.getCategories().add(category);
        result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters2), new Filter("score", "评分", filters3), new Filter("year", "年份", years)));

        if (appProperties.isMix()) {
            category = new Category();
            category.setType_id(site.getId() + "$/$1");
            category.setType_name("AList");
            category.setType_flag(1);
            result.getCategories().add(category);
            result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
        }

        try {
            Path file = Paths.get("/data/category.txt");
            if (Files.exists(file)) {
                String typeId = "";
                List<FilterValue> filters = new ArrayList<>();
                filters.add(new FilterValue("全部", ""));
                for (String path : Files.readAllLines(file)) {
                    if (StringUtils.isBlank(path)) {
                        continue;
                    }
                    if (path.startsWith("  ")) {
                        setFilterValue(filters, path);
                        continue;
                    } else if (!typeId.isEmpty()) {
                        addFilters(result, typeId, filters, years);
                        filters = new ArrayList<>();
                        filters.add(new FilterValue("全部", ""));
                    }

                    String name = path;
                    String[] parts = path.split(":");
                    if (parts.length == 2) {
                        path = parts[0];
                        name = parts[1];
                    }

                    String newPath = fixPath("/" + path);
                    category = new Category();
                    category.setType_id(site.getId() + "$" + newPath + "$0");
                    category.setType_name((appProperties.isMerge() ? "\uD83C\uDFAC" : "") + name);
                    category.setType_flag(0);
                    typeId = category.getType_id();

                    AListAlias alias = aliasRepository.findByPath(newPath);
                    log.debug("{}: {}", newPath, alias);
                    if (alias != null) {
                        List<String> paths = Arrays.asList(alias.getContent().split("\n"));
                        String prefix = Utils.getCommonPrefix(paths);
                        String suffix = Utils.getCommonSuffix(paths);
                        log.debug("prefix: {} suffix: {} list: {}", prefix, suffix, paths);
                        for (String dir : paths) {
                            parts = dir.split(":");
                            dir = parts[0];
                            name = parts.length == 2 ? parts[1] : dir.replace(prefix, "").replace(suffix, "").trim();
                            filters.add(new FilterValue(name, dir));
                        }
                    }

                    result.getCategories().add(category);
                }
                addFilters(result, typeId, filters, years);
                return;
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        for (FsInfo fsInfo : aListService.listFiles(site, "/", 1, 20).getFiles()) {
            String name = fsInfo.getName();
            if (fsInfo.getType() != 1 || !metaRepository.existsByPathStartsWith("/" + name)) {
                log.info("ignore path: {}", name);
                continue;
            }
            category = new Category();
            category.setType_id(site.getId() + "$/" + name + "$0");
            category.setType_name(name);
            category.setType_flag(0);
            result.getCategories().add(category);
            result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters2), new Filter("score", "评分", filters3), new Filter("year", "年份", years)));
        }
    }

    private void addFilters(CategoryList result, String typeId, List<FilterValue> filters, List<FilterValue> years) {
        if (filters.size() <= 1) {
            result.getFilters().put(typeId, List.of(new Filter("sort", "排序", filters2), new Filter("score", "评分", filters3), new Filter("year", "年份", years)));
        } else {
            result.getFilters().put(typeId, List.of(new Filter("dir", "目录", filters), new Filter("sort", "排序", filters2), new Filter("score", "评分", filters3), new Filter("year", "年份", years)));
        }
    }

    private static void setFilterValue(List<FilterValue> filters, String path) {
        String[] parts = path.split(":");
        if (parts.length == 2) {
            filters.add(new FilterValue(parts[1].trim(), parts[0].trim()));
        } else {
            filters.add(new FilterValue(getNameFromPath(path.trim()), path.trim()));
        }
    }

    private void addMyFavorite(CategoryList result) {
        if (accountRepository.findAll().stream().anyMatch(Account::isShowMyAli)) {
            Category category = new Category();
            category.setType_id("1$/\uD83D\uDCC0我的阿里云盘$1");
            category.setType_name("我的云盘");
            result.getCategories().add(category);
            result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
        }

        int pp = shareRepository.countByType(1);
        if (shareRepository.count() > pp) {
            Category category = new Category();
            category.setType_id("1$/\uD83C\uDE34我的阿里分享$1");
            category.setType_name("阿里分享");
            result.getCategories().add(category);
            result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
        }

        if (shareRepository.countByType(2) > 0) {
            Category category = new Category();
            category.setType_id("1$/\uD83C\uDF1E我的夸克网盘$1");
            category.setType_name("夸克网盘");
            result.getCategories().add(category);
            result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
        }

        if (shareRepository.countByType(5) > 0) {
            Category category = new Category();
            category.setType_id("1$/我的夸克分享$1");
            category.setType_name("夸克分享");
            result.getCategories().add(category);
            result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
        }

        if (shareRepository.countByType(3) > 0) {
            Category category = new Category();
            category.setType_id("1$/115网盘$1");
            category.setType_name("115网盘");
            result.getCategories().add(category);
            result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
        }

        if (pp > 0) {
            Category category = new Category();
            category.setType_id("1$/\uD83D\uDD78️我的PikPak分享$1");
            category.setType_name("PikPak");
            result.getCategories().add(category);
            result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
        }
    }

    public MovieList recommend(String ac, int pg) {
        List<MovieDetail> list = new ArrayList<>();
        Pageable pageable = PageRequest.of(pg - 1, 60, Sort.Direction.DESC, "time", "id");
        Page<Meta> page = metaRepository.findAll(pageable);
        Map<String, List<Meta>> map = new HashMap<>();
        Set<String> added = new HashSet<>();
        for (Meta meta : page.getContent()) {
            String name = getName(meta);
            List<Meta> metas = map.computeIfAbsent(name, id -> new ArrayList<>());
            metas.add(meta);
        }

        for (Meta meta : page.getContent()) {
            String name = getName(meta);
            if (added.contains(name)) {
                log.debug("skip {}: {}", name, meta.getPath());
                continue;
            }
            MovieDetail movieDetail = new MovieDetail();
            List<Meta> metas = map.get(name);
            if (metas.size() > 1) {
                String ids = metas.stream().map(Meta::getId).map(String::valueOf).collect(Collectors.joining("-"));
                log.debug("duplicate: {} {} {}", name, metas.size(), ids);
                movieDetail.setVod_id(Objects.toString(meta.getSiteId(), "1") + "$" + encodeUrl(ids) + "$0");
                added.add(name);
            } else {
                movieDetail.setVod_id(String.valueOf(meta.getId()));
                movieDetail.setVod_remarks(getLabel(meta.getPath()));
            }
            movieDetail.setVod_name(name);
            movieDetail.setVod_pic(Constants.ALIST_PIC);
            movieDetail.setVod_content(meta.getPath());
            setMovieInfo(movieDetail, meta, "videolist".equals(ac));
            list.add(movieDetail);
        }

        MovieList result = new MovieList();
        result.setList(list);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("recommend {}", result);
        return result;
    }

    public MovieList msearch(Integer type, String keyword) {
        String name = TextUtils.fixName(keyword);
        MovieList result = search(type, "", name, 1);
        if (result.getTotal() > 0) {
            return getDetail("", result.getList().get(0).getVod_id());
        }
        return result;
    }

    private String getLabel(String path) {
        for (var item : configFileService.getLabels()) {
            if (item.getPath().startsWith("/") && path.startsWith(item.getPath())) {
                return item.getName() + " ";
            }
            if (path.contains(item.getPath())) {
                return item.getName() + " ";
            }
        }

        if (path.startsWith("/电影")) {
            return "\uD83C\uDF9E  ";
        }
        if (path.startsWith("/电视剧")) {
            return "\uD83D\uDCFA  ";
        }
        if (path.startsWith("/动漫")) {
            return "\uD83E\uDDF8  ";
        }
        if (path.startsWith("/综艺")) {
            return "\uD83C\uDFA4  ";
        }
        if (path.startsWith("/纪录片")) {
            return "\uD83D\uDD2C  ";
        }
        if (path.startsWith("/音乐")) {
            return "\uD83C\uDFB6  ";
        }
        if (path.startsWith("/有声书")) {
            return "\uD83D\uDCD6  ";
        }
        if (path.startsWith("/整理中")) {
            return "\uD83E\uDDFA  ";
        }
        if (path.startsWith("/每日更新/PikPak")) {
            return "\uD83C\uDD7F\uFE0F  ";
        }
        if (path.startsWith("/每日更新")) {
            return "\uD83D\uDCC5  ";
        }
        if (path.startsWith("/教育")) {
            return "\uD83C\uDF93  ";
        }
        if (path.startsWith("/曲艺")) {
            return "\uD83C\uDFB8  ";
        }
        if (path.startsWith("/体育")) {
            return "⚽\uFE0F  ";
        }
        if (path.startsWith("/\uD83C\uDE34我的阿里分享/Tacit0924")) {
            return "\uD83D\uDCEE  ";
        }
        if (path.startsWith("/\uD83C\uDE34我的阿里分享")) {
            return "\uD83C\uDE34  ";
        }

        if (path.contains("115")) {
            return "5\uFE0F⃣  ";
        }
        if (path.contains("PikPak")) {
            return "\uD83C\uDD7F\uFE0F  ";
        }
        if (path.contains("阿里云盘")) {
            return "\uD83D\uDCC0  ";
        }
        if (path.contains("夸克网盘")) {
            return "\uD83C\uDF1E  ";
        }
        if (path.contains("我的套娃")) {
            return "\uD83C\uDF8E  ";
        }
        return "";
    }

    private Site getSite(Integer id) {
        for (Site site : sites) {
            if (site.getId().equals(id) || (id == null && site.getId() == 1)) {
                return site;
            }
        }
        return null;
    }

    public MovieList search(Integer type, String ac, String keyword, int page) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        sites = siteService.findAll();
        for (Site site : sites) {
            if (site.getId() == 1) {
                site.setUrl(buildUrl(site, ""));
            }
        }

        if (type != null && type == 0) {
            for (Meta meta : metaRepository.findByPathContains(keyword, PageRequest.of(page - 1, appProperties.getMaxSearchResult(), Sort.Direction.DESC, "time", "id"))) {
                String name = getName(meta);
                boolean isMediaFile = isMediaFile(meta.getPath());
                String newPath = fixPath(meta.getPath() + (isMediaFile ? "" : PLAYLIST));
                MovieDetail movieDetail = new MovieDetail();
                movieDetail.setVod_id(String.valueOf(meta.getId()));
                movieDetail.setVod_name(name);
                movieDetail.setVod_pic(Constants.ALIST_PIC);
                movieDetail.setVod_content(meta.getPath());
                if ("web".equals(ac)) {
                    movieDetail.setVod_play_url(buildUrl(getSite(meta.getSiteId()), meta.getPath()));
                } else {
                    movieDetail.setVod_remarks(getLabel(newPath));
                }
                setMovieInfo(movieDetail, meta, false);
                list.add(movieDetail);
            }
        } else {
            List<Future<List<MovieDetail>>> futures = new ArrayList<>();
            for (Site site : siteService.list()) {
                if (site.isSearchable()) {
                    if (StringUtils.isNotEmpty(site.getIndexFile())) {
                        futures.add(executorService.submit(() -> searchByFile(site, ac, keyword)));
                    } else {
                        futures.add(executorService.submit(() -> searchByApi(site, ac, keyword)));
                    }
                }
            }

            for (Future<List<MovieDetail>> future : futures) {
                try {
                    list.addAll(future.get());
                } catch (Exception e) {
                    log.warn("", e);
                }
            }

            list = list.stream().distinct().limit(appProperties.getMaxSearchResult()).toList();
            for (MovieDetail movie : list) {
                if (movie.getVod_pic() != null && movie.getVod_pic().contains(".doubanio.com/")) {
                    fixCover(movie);
                }
            }
        }

        log.info("search \"{}\" result: {}", keyword, list.size());
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        return result;
    }

    private List<MovieDetail> searchByFile(Site site, String ac, String keyword) throws IOException {
        String indexFile = site.getIndexFile();
        if (indexFile.startsWith("http://") || indexFile.startsWith("https://")) {
            indexFile = indexService.downloadIndexFile(site);
        }

        List<MovieDetail> list = searchFromIndexFile(site, ac, keyword, indexFile);
        File customIndexFile = new File("/data/index/" + site.getId() + "/custom_index.txt");
        log.debug("custom index file: {}", customIndexFile);
        if (customIndexFile.exists()) {
            list.addAll(searchFromIndexFile(site, ac, keyword, customIndexFile.getAbsolutePath()));
        }
        log.debug("search \"{}\" from site {}:{}, result: {}", keyword, site.getId(), site.getName(), list.size());
        return list;
    }

    private List<MovieDetail> searchFromIndexFile(Site site, String ac, String keyword, String indexFile) throws IOException {
        log.info("search \"{}\" from site {}:{}, index file: {}", keyword, site.getId(), site.getName(), indexFile);
        Set<String> keywords = Arrays.stream(keyword.split("\\s+")).collect(Collectors.toSet());
        Set<String> lines = Files.readAllLines(Paths.get(indexFile))
                .stream()
                .filter(path -> !path.startsWith("-"))
                .filter(path -> keywords.stream().allMatch(path::contains))
                .limit(appProperties.getMaxSearchResult())
                .collect(Collectors.toSet());

        log.debug("search \"{}\" from file: {}, result: {}", keyword, indexFile, lines.size());
        List<MovieDetail> list = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("+")) {
                line = line.substring(1);
            }
            if (line.startsWith("./")) {
                line = line.substring(1);
            }

            String raw = line;
            String[] parts = line.split("#");
            if (parts.length > 1) {
                line = parts[0];
            }

            boolean isMediaFile = isMediaFile(line);
            if (isMediaFile && lines.contains(getParent(raw))) {
                continue;
            }
            String path = fixPath("/" + line + (isMediaFile ? "" : PLAYLIST));
            if (StringUtils.isNotBlank(site.getFolder()) && !"/".equals(site.getFolder())) {
                if (path.startsWith(site.getFolder())) {
                    path = path.substring(site.getFolder().length());
                } else {
                    continue;
                }
            }
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site.getId() + "$" + encodeUrl(path) + "$1");
            movieDetail.setVod_name(getNameFromPath(line));
            movieDetail.setVod_pic(Constants.ALIST_PIC);
            movieDetail.setVod_content(path.replace(PLAYLIST, ""));
            movieDetail.setVod_tag(FILE);
            if ("web".equals(ac)) {
                movieDetail.setVod_play_url(buildUrl(getSite(site.getId()), path.replace(PLAYLIST, "")));
            } else {
                movieDetail.setVod_remarks(getLabel(path));
            }
            if (!isMediaFile) {
                setMovieInfo(site, movieDetail, getParent(path), false);
            }
            list.add(movieDetail);
        }
        list.sort(Comparator.comparing(MovieDetail::getVod_id));
        return list;
    }

    private List<MovieDetail> searchByApi(Site site, String ac, String keyword) throws IOException {
        if (site.isXiaoya()) {
            try {
                return searchByXiaoya(site, ac, keyword);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        List<MovieDetail> result = new ArrayList<>();
        for (File file : Utils.listFiles("/data/index/" + site.getId(), "txt")) {
            result.addAll(searchFromIndexFile(site, ac, keyword, file.getAbsolutePath()));
        }

        try {
            var list = aListService.search(site, keyword)
                    .stream()
                    .map(e -> {
                        boolean isMediaFile = isMediaFile(e.getName());
                        String path = fixPath(e.getParent() + "/" + e.getName() + (isMediaFile ? "" : PLAYLIST));
                        if (StringUtils.isNotBlank(site.getFolder()) && !"/".equals(site.getFolder())) {
                            if (path.startsWith(site.getFolder())) {
                                path = path.substring(site.getFolder().length());
                            } else {
                                return null;
                            }
                        }

                        MovieDetail movieDetail = new MovieDetail();
                        movieDetail.setVod_id(site.getId() + "$" + encodeUrl(path) + "$1");
                        movieDetail.setVod_name(e.getName());
                        movieDetail.setVod_pic(Constants.ALIST_PIC);
                        movieDetail.setVod_tag(FILE);
                        if (!isMediaFile) {
                            setMovieInfo(site, movieDetail, getParent(path), false);
                        }
                        return movieDetail;
                    })
                    .filter(Objects::nonNull)
                    .toList();
            result.addAll(list);
        } catch (Exception e) {
            log.warn("search by API failed", e);
        }

        log.debug("search \"{}\" from site {}:{}, result: {}", keyword, site.getId(), site.getName(), result.size());
        return result;
    }

    private List<MovieDetail> searchByXiaoya(Site site, String ac, String keyword) throws IOException {
        List<MovieDetail> list = new ArrayList<>();
        for (File file : Utils.listFiles("/data/index/" + site.getId(), "txt")) {
            list.addAll(searchFromIndexFile(site, ac, keyword, file.getAbsolutePath()));
        }

        if (site.getId() == 1) {
            list.addAll(searchFromIndexFile(site, ac, keyword, "/data/index/index.video.txt"));
            return list;
        }

        log.info("search \"{}\" from xiaoya {}:{}", keyword, site.getId(), site.getName());
        String url = site.getUrl() + "/search?url=&type=video&box=" + keyword;
        Document doc = Jsoup.connect(url).get();
        Elements links = doc.select("div a[href]");

        for (Element element : links) {
            MovieDetail movieDetail = new MovieDetail();
            String path = URLDecoder.decode(element.attr("href"), "UTF-8");
            String name = path;
            boolean isMediaFile = isMediaFile(name);
            path = fixPath(path + (isMediaFile ? "" : PLAYLIST));
            if (StringUtils.isNotBlank(site.getFolder()) && !"/".equals(site.getFolder())) {
                if (path.startsWith(site.getFolder())) {
                    path = path.substring(site.getFolder().length());
                } else {
                    continue;
                }
            }
            movieDetail.setVod_id(site.getId() + "$" + encodeUrl(path) + "$1");
            movieDetail.setVod_name(getNameFromPath(name));
            movieDetail.setVod_pic(Constants.ALIST_PIC);
            movieDetail.setVod_content(path.replace(PLAYLIST, ""));
            movieDetail.setVod_tag(FILE);
            if (!isMediaFile) {
                setMovieInfo(site, movieDetail, getParent(path), false);
            }
            list.add(movieDetail);
            if (list.size() > appProperties.getMaxSearchResult()) {
                break;
            }
        }

        log.debug("{}", list);
        return list;
    }

    private boolean isFolder(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1);
            return suffix.length() > 4;
        }
        return true;
    }

    private boolean isMediaFile(String path) {
        String name = path;
        int index = path.lastIndexOf('/');
        if (index > -1) {
            name = path.substring(index + 1);
        }
        return isMediaFormat(name);
    }

    private Site getSite(String tid) {
        int index = tid.indexOf('$');
        String id = tid.substring(0, index);
        if ("0".equals(id) || "null".equals(id)) {
            return getXiaoyaSite();
        }
        try {
            Integer siteId = Integer.parseInt(id);
            return siteService.getById(siteId);
        } catch (NumberFormatException e) {
            // ignore
        }

        return siteService.getByName(id);
    }

    private boolean exclude(String name) {
        for (String text : Set.of("订阅", "福利", "会员", "微信", "QQ群", "招募", "代找")) {
            if (name.contains(text)) {
                log.warn("exclude {}", name);
                return true;
            }
        }
        return false;
    }

    public MovieList getMovieList(String client, String ac, String tid, String filter, String sort, int page) {
        String[] parts = tid.split("\\$");
        String path = parts[1];
        int type = 1;
        if (parts.length > 2) {
            type = Integer.parseInt(parts[2]);
        }

        if (type == 0) {
            return getMetaList(ac, tid, filter, sort, page);
        }

        Site site = getSite(tid);
        if (path.contains(PLAYLIST)) {
            return getPlaylist("", site, path);
        }

        List<MovieDetail> folders = new ArrayList<>();
        List<MovieDetail> files = new ArrayList<>();
        List<MovieDetail> playlists = new ArrayList<>();
        MovieList result = new MovieList();

        int size = appProperties.getPageSize();
        FsResponse fsResponse = aListService.listFiles(site, path, page, size);
        int total = fsResponse.getTotal();

        for (FsInfo fsInfo : fsResponse.getFiles()) {
            String filepath = path + fsInfo.getName();
            if ((fsInfo.getType() == 1 && exclude(fsInfo.getName()))
                    || filepath.equals("/元数据")
                    || filepath.equals("/\uD83D\uDEE0\uFE0F安装，配置，修复 xiaoya docker 指南")
                    || (fsInfo.getType() != 1 && !isMediaFormat(fsInfo.getName()))) {
                total--;
                continue;
            }

            String newPath = fixPath(path + "/" + fsInfo.getName());
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site.getId() + "$" + encodeUrl(newPath) + "$1");
            movieDetail.setVod_name(fsInfo.getName());
            movieDetail.setVod_tag(fsInfo.getType() == 1 ? FOLDER : FILE);
            movieDetail.setVod_pic(getCover(fsInfo.getThumb(), fsInfo.getType()));
            movieDetail.setVod_remarks(fileSize(fsInfo.getSize()) + (fsInfo.getType() == 1 ? "文件夹" : ""));
            movieDetail.setVod_time(fsInfo.getModified());
            movieDetail.setSize(fsInfo.getSize());
            if (fsInfo.getType() == 1) {
                if ("open".equals(client) || "node".equals(client)) {
                    movieDetail.setVod_pic("");
                    if ("AliyundriveOpen".equals(fsResponse.getProvider())) {
                        movieDetail.setVod_pic("https://pic.rmb.bdstatic.com/bjh/6a2278365c10139b5b03229c2ecfeea4.jpeg");
                    }
                    movieDetail.setCate(new CategoryList());
                }
                setMovieInfo(site, movieDetail, newPath, false);
                folders.add(movieDetail);
            } else {
                files.add(movieDetail);
            }
        }

        sortFiles(sort, folders, files);

        result.getList().addAll(folders);

        if (page == 1 && files.size() > 1) {
            playlists = generatePlaylist(site.getId() + "$" + fixPath(path + PLAYLIST), total - folders.size(), files);
        }

        result.getList().addAll(playlists);
        result.getList().addAll(files);

        result.setPage(page);
        result.setTotal(total);
        result.setLimit(result.getList().size());
        result.setPagecount((total + size - 1) / size);
        log.debug("list: {}", result);
        return result;
    }

    public MovieList getMetaList(String ac, String tid, String filter, String sort, int page) {
        String[] parts = tid.split("\\$");
        String path = parts[1];
        List<MovieDetail> files = new ArrayList<>();
        MovieList result = new MovieList();

        Pageable pageable;
        String score = "";
        String dir = "";
        String year = "";
        if (StringUtils.isNotBlank(filter)) {
            try {
                Map<String, String> map = objectMapper.readValue(filter, Map.class);
                score = map.getOrDefault("score", "");
                sort = map.getOrDefault("sort", sort);
                dir = map.getOrDefault("dir", "");
                year = map.getOrDefault("year", "");
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        if (StringUtils.isBlank(sort)) {
            sort = "time,desc";
        }

        int size = 60;
        if (StringUtils.isNotBlank(sort)) {
            List<Sort.Order> orders = new ArrayList<>();
            for (String item : sort.split(";")) {
                parts = item.split(",");
                Sort.Order order = parts[1].equals("asc") ? Sort.Order.asc(parts[0]) : Sort.Order.desc(parts[0]);
                orders.add(order);
            }
            pageable = PageRequest.of(page - 1, size, Sort.by(orders));
        } else {
            pageable = PageRequest.of(page - 1, size);
        }

        AListAlias aListAlias = aliasRepository.findByPath(path);
        List<String> paths = new ArrayList<>();
        if (!dir.isEmpty()) {
            if (aListAlias == null) {
                paths.add(fixPath(path + "/" + dir));
            } else {
                paths.add(fixPath(dir));
            }
        } else {
            if (aListAlias != null) {
                paths = Arrays.stream(aListAlias.getContent().split("\n")).map(e -> e.split(":")[0]).collect(Collectors.toList());
                log.debug("{}: {} {}", path, aListAlias, paths);
            } else {
                paths.add(path);
            }
        }
        log.debug("paths: {}", paths);

        int pages = 0;
        Page<Meta> list = new PageImpl<>(List.of());
        for (String line : paths) {
            path = line;
            log.debug("get movies from {} {}", path, pages);
            pageable = PageRequest.of(page - pages - 1, size, pageable.getSort());
            if (year.isEmpty()) {
                if ("normal".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreGreaterThanEqual(path, 60, pageable);
                } else if ("high".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreGreaterThanEqual(path, 80, pageable);
                } else if ("low".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreLessThan(path, 60, pageable);
                } else if ("no".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreIsNull(path, pageable);
                } else {
                    list = metaRepository.findByPathStartsWith(path, pageable);
                }
            } else if ("others".equals(year)) {
                int y = LocalDate.now().getYear() - 20;
                if ("normal".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreGreaterThanEqualAndYearLessThan(path, 60, y, pageable);
                } else if ("high".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreGreaterThanEqualAndYearLessThan(path, 80, y, pageable);
                } else if ("low".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreLessThanAndYearLessThan(path, 60, y, pageable);
                } else if ("no".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreIsNullAndYearLessThan(path, y, pageable);
                } else {
                    list = metaRepository.findByPathStartsWithAndYearLessThan(path, y, pageable);
                }
            } else {
                if ("normal".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreGreaterThanEqualAndYear(path, 60, Integer.parseInt(year), pageable);
                } else if ("high".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreGreaterThanEqualAndYear(path, 80, Integer.parseInt(year), pageable);
                } else if ("low".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreLessThanAndYear(path, 60, Integer.parseInt(year), pageable);
                } else if ("no".equals(score)) {
                    list = metaRepository.findByPathStartsWithAndScoreIsNullAndYear(path, Integer.parseInt(year), pageable);
                } else {
                    list = metaRepository.findByPathStartsWithAndYear(path, Integer.parseInt(year), pageable);
                }
            }
            pages += list.getTotalPages();
            if (list.getNumberOfElements() > 0) {
                break;
            }
        }

        log.debug("{} {} {}", pageable, list, list.getContent().size());
        Map<String, List<Meta>> map = new HashMap<>();
        Set<String> added = new HashSet<>();
        for (Meta meta : list) {
            String name = getName(meta);
            List<Meta> metas = map.computeIfAbsent(name, id -> new ArrayList<>());
            metas.add(meta);
        }

        for (Meta meta : list) {
            String name = getName(meta);
            if (added.contains(name)) {
                log.debug("skip {}: {}", name, meta.getPath());
                continue;
            }
            MovieDetail movieDetail = new MovieDetail();
            List<Meta> metas = map.get(name);
            if (metas.size() > 1) {
                String ids = metas.stream().map(Meta::getId).map(String::valueOf).collect(Collectors.joining("-"));
                log.debug("duplicate: {} {} {}", name, metas.size(), ids);
                movieDetail.setVod_id(Objects.toString(meta.getSiteId(), "1") + "$" + encodeUrl(ids) + "$0");
                added.add(name);
            } else {
                movieDetail.setVod_id(String.valueOf(meta.getId()));
                if (path.equals("/")) {
                    movieDetail.setVod_remarks(getLabel(meta.getPath()));
                }
            }
            movieDetail.setVod_name(name);
            movieDetail.setVod_pic(Constants.ALIST_PIC);
            setMovieInfo(movieDetail, meta, "videolist".equals(ac));
            files.add(movieDetail);
            log.debug("{}", movieDetail);
        }

        result.getList().addAll(files);

        result.setPage(page);
        result.setTotal((int) list.getTotalElements());
        result.setLimit(files.size());
        result.setPagecount(pages + 1);
        log.debug("list: {}", result);
        return result;
    }

    private static String getName(Meta meta) {
        String name;
        if (meta.getTmdb() != null) {
            name = meta.getTmdb().getName();
        } else if (meta.getMovie() != null) {
            name = meta.getMovie().getName();
        } else {
            name = getNameFromPath(meta.getPath());
        }
        return name;
    }

    private void sortFiles(String sort, List<MovieDetail> folders, List<MovieDetail> files) {
        if (sort == null) {
            sort = "name,asc";
        }
        Comparator<MovieDetail> comparator;
        switch (sort) {
            case "name,asc" -> comparator = Comparator.comparing(e -> new FileNameInfo(e.getVod_name()));
            case "time,asc" -> comparator = Comparator.comparing(MovieDetail::getVod_time);
            case "size,asc" -> comparator = Comparator.comparing(MovieDetail::getSize);
            case "name,desc" -> {
                comparator = Comparator.comparing(e -> new FileNameInfo(e.getVod_name()));
                comparator = comparator.reversed();
            }
            case "time,desc" -> {
                comparator = Comparator.comparing(MovieDetail::getVod_time);
                comparator = comparator.reversed();
            }
            case "size,desc" -> {
                comparator = Comparator.comparing(MovieDetail::getSize);
                comparator = comparator.reversed();
            }
            default -> {
                return;
            }
        }
        folders.sort(comparator);
        files.sort(comparator);
    }

    private List<MovieDetail> generatePlaylist(String path, int total, List<MovieDetail> files) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(encodeUrl(path) + "$1");
        movieDetail.setVod_name("播放列表");
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(getListPic());
        if (total < appProperties.getPageSize()) {
            movieDetail.setVod_remarks("共" + files.size() + "集");
        }

        List<MovieDetail> list = new ArrayList<>();
        list.add(movieDetail);

        return list;
    }

    private String getListPic() {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath("/list.png")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    public Map<String, Object> getPlayUrl(Integer siteId, String path, boolean getSub, String client) {
        Site site = siteService.getById(siteId);
        String url = null;
        String name = getNameFromPath(path);
        String fullPath = path;
        if (isMediaFile(path)) {
            log.info("get play url - site {}:{}  path: {}", site.getId(), site.getName(), path);
        } else {
            FsResponse fsResponse = aListService.listFiles(site, path, 1, 100);
            for (FsInfo fsInfo : fsResponse.getFiles()) {
                if (fsInfo.getType() != 1 && isMediaFormat(fsInfo.getName())) {
                    name = fsInfo.getName();
                    fullPath = fixPath(path + "/" + name);
                    break;
                }
            }
            log.info("get play url -- site {}:{}  path: {}", site.getId(), site.getName(), fullPath);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("parse", 0);
        result.put("playUrl", "");

        List<Subtitle> subtitles = new ArrayList<>();

        if ("com.fongmi.android.tv".equals(client)) {
            try {
                var preview = aListService.preview(site, fullPath);
                log.debug("preview: {} {}", fullPath, preview);
                if (preview != null) {
                    Collections.reverse(preview.getPlayInfo().getVideos());
                    List<String> urls = new ArrayList<>();
                    for (var item : preview.getPlayInfo().getVideos()) {
                        if (!"finished".equals(item.getStatus())) {
                            continue;
                        }
                        urls.add(item.getId());
                        urls.add(item.getUrl());
                    }
                    if (urls.size() > 1) {
                        url = urls.get(1);
                        result.put("url", urls);
                    }

                    if (preview.getPlayInfo().getSubtitles() != null) {
                        for (var item : preview.getPlayInfo().getSubtitles()) {
                            if (!"finished".equals(item.getStatus())) {
                                continue;
                            }
                            Subtitle subtitle = new Subtitle();
                            subtitle.setUrl(item.getUrl());
                            subtitle.setLang(item.getLanguage());
                            subtitles.add(subtitle);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("preview failed", e);
            }
        }

        if (url == null) {
            FsDetail fsDetail = aListService.getFile(site, path);
            if (fsDetail == null) {
                throw new BadRequestException("找不到文件 " + path);
            }

            if (fsDetail.getProvider().contains("Aliyundrive")
                    || (("open".equals(client) || "node".equals(client)) && fsDetail.getProvider().contains("115"))) {
                url = buildProxyUrl(site, path, fsDetail.getSign());
                log.info("play url: {}", url);
            } else {
                url = fixHttp(fsDetail.getRawUrl());
            }

            result.put("url", url);
        }

        if (url.contains("xunlei.com")) {
            result.put("header", "{\"User-Agent\":\"Dalvik/2.1.0 (Linux; U; Android 12; M2004J7AC Build/SP1A.210812.016)\"}");
        } else if (url.contains("115.com")) {
            result.put("header", "{\"User-Agent\":\"" + USER_AGENT + "\",\"Referer\":\"https://115.com/\"}");
        } else if (url.contains("ali")) {
            result.put("format", "application/octet-stream");
            result.put("header", "{\"User-Agent\":\"" + USER_AGENT + "\",\"Referer\":\"https://www.aliyundrive.com/\"}");
        }

        if (!getSub) {
            log.debug("result: {}", result);
            return result;
        }

        Subtitle subtitle = getSubtitle(site, isMediaFile(path) ? getParent(path) : path, name);
        if (subtitle != null) {
            subtitles.add(subtitle);
        }

        if (!subtitles.isEmpty()) {
            result.put("subt", subtitles.get(0).getUrl());
        }

        result.put("subs", subtitles);

        log.debug("result: {}", result);
        return result;
    }

    public Map<String, Object> getPlayUrl(Integer siteId, Integer id, Integer index, boolean getSub, String client) {
        return getPlayUrl(siteId, id, cache.getIfPresent(id).get(index - 1), getSub, client);
    }

    public Map<String, Object> getPlayUrl(Integer siteId, Integer id, boolean getSub, String client) {
        return getPlayUrl(siteId, id, "", getSub, client);
    }

    public Map<String, Object> getPlayUrl(Integer siteId, Integer id, String path, boolean getSub, String client) {
        Meta meta = metaRepository.findById(id).orElseThrow(NotFoundException::new);
        if (siteId == null) {
            siteId = meta.getSiteId();
        }
        if (siteId == null) {
            siteId = 1;
        }
        log.debug("getPlayUrl: {} {} {}", siteId, id, path);
        return getPlayUrl(siteId, meta.getPath() + path, getSub, client);
    }

    private String findBestSubtitle(List<String> subtitles, String name) {
        if (subtitles.isEmpty()) {
            return null;
        }

        String prefix = Utils.getCommonPrefix(subtitles, false);
        String suffix = Utils.getCommonSuffix(subtitles, false);

        log.debug("'{}' '{}' '{}' {}", name, prefix, suffix, subtitles);
        String best = null;
        int min = name.length();
        for (String subtitle : subtitles) {
            String sub = fixName(subtitle, prefix, suffix);
            if (sub.equals(name) || sub.startsWith(name) || name.startsWith(sub)) {
                return subtitle;
            }

            int dis = TextUtils.minDistance(name, sub);
            log.debug("'{}' '{}' {} {}", name, sub, dis, min);
            if (dis < min) {
                min = dis;
                best = subtitle;
            }
        }

        log.debug("best: {} {}", min, best);
        return best;
    }

    public Subtitle getSubtitle(Site site, String path, String name) {
        FsResponse fsResponse = aListService.listFiles(site, path, 1, 100);
        List<FsInfo> files = fsResponse.getFiles();
        List<String> names = files.stream().map(FsInfo::getName).filter(this::isMediaFile).collect(Collectors.toList());
        String prefix = Utils.getCommonPrefix(names, false);
        String suffix = Utils.getCommonSuffix(names, false);
        log.debug("{} {}", prefix, suffix);
        List<String> subtitles = files.stream().map(FsInfo::getName).filter(this::isSubtitleFormat).collect(Collectors.toList());

        String best = findBestSubtitle(subtitles, name.replace(prefix, "").replace(suffix, ""));
        if (best == null) {
            return null;
        }

        FsDetail fsDetail = aListService.getFile(site, path + "/" + best);
        log.debug("FsDetail: {}", fsDetail);
        if (fsDetail != null) {
            Subtitle subtitle = new Subtitle();
            if (best.contains("chs")) {
                subtitle.setLang("chs");
                subtitle.setName("简体中文");
            } else if (best.contains("cht")) {
                subtitle.setLang("cht");
                subtitle.setName("繁体中文");
            } else if (best.contains("en")) {
                subtitle.setLang("eng");
                subtitle.setName("英文");
            }
            if (best.endsWith("ssa")) {
                subtitle.setFormat("text/x-ssa");
            } else if (best.endsWith("vtt")) {
                subtitle.setFormat("text/vtt");
            } else if (best.endsWith("ttml")) {
                subtitle.setFormat("application/ttml+xml");
            }
            subtitle.setExt(getExt(best));
            subtitle.setUrl(fixHttp(fsDetail.getRawUrl()));
            log.debug("subtitle: {}", subtitle);
            return subtitle;
        }
        return null;
    }

    private static final Pattern ID_PATH = Pattern.compile("(\\d+)(-\\d+)+");

    public MovieList getDetail(String ac, Integer id) {
        MovieList result = new MovieList();
        Meta meta = metaRepository.findById(id).orElseThrow(NotFoundException::new);
        Site site = siteService.getById(meta.getSiteId() == null ? 1 : meta.getSiteId());
        MovieDetail movieDetail = new MovieDetail();
        if (isMediaFile(meta.getPath())) {
            movieDetail.setVod_id(String.valueOf(id));
            movieDetail.setVod_name(meta.getName());
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_time(String.valueOf(meta.getYear()));
            movieDetail.setVod_pic(ALIST_PIC);
            movieDetail.setVod_play_from(site.getName());
            movieDetail.setVod_play_url(String.valueOf(id));
            movieDetail.setVod_content(meta.getPath());
            setMovieInfo(movieDetail, meta, true);
        } else {
            movieDetail = getMovieDetail(site, meta);
        }

        result.getList().add(movieDetail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    public MovieList getDetail(String ac, String tid) {
        if (!tid.contains("$")) {
            return getDetail(ac, Integer.parseInt(tid));
        }

        Site site = getSite(tid);
        String[] parts = tid.split("\\$");
        String path = parts[1];
        if (path.contains(PLAYLIST)) {
            return getPlaylist(ac, site, path);
        }

        MovieList result = new MovieList();
        if (ID_PATH.matcher(path).matches()) {
            String[] ids = path.split("\\-");
            List<Meta> list = metaRepository.findAllById(Arrays.stream(ids).map(Integer::parseInt).collect(Collectors.toList()));
            Meta meta = list.get(0);
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(tid + "$1");
            movieDetail.setVod_name(meta.getName());
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_time(String.valueOf(meta.getYear()));
            movieDetail.setVod_pic(ALIST_PIC);
            List<String> from = new ArrayList<>();
            List<String> url = new ArrayList<>();
            String playUrl;
            if (isMediaFile(meta.getPath())) {
                for (int i = 0; i < list.size(); ++i) {
                    from.add("版本" + (i + 1));
                }
                playUrl = list.stream().map(m -> String.valueOf(m.getId())).collect(Collectors.joining("$$$"));
            } else {
                for (int i = 0; i < list.size(); ++i) {
                    var m = getMovieDetail(site, list.get(i));
                    if (m.getVod_play_from().contains("$$$")) {
                        for (String folder : m.getVod_play_from().split("\\$\\$\\$")) {
                            from.add("版本" + (i + 1) + "-" + folder);
                        }
                    } else {
                        from.add("版本" + (i + 1));
                    }
                    url.add(m.getVod_play_url());
                }
                playUrl = String.join("$$$", url);
            }
            movieDetail.setVod_play_from(String.join("$$$", from));
            movieDetail.setVod_play_url(playUrl);

            movieDetail.setVod_content(getParent(path));
            setMovieInfo(movieDetail, meta, true);
            result.getList().add(movieDetail);
        } else {
            FsDetail fsDetail = aListService.getFile(site, path);
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(tid + "$1");
            movieDetail.setVod_name(fsDetail.getName());
            movieDetail.setVod_tag(fsDetail.getType() == 1 ? FOLDER : FILE);
            movieDetail.setVod_time(fsDetail.getModified());
            movieDetail.setVod_pic(getCover(fsDetail.getThumb(), fsDetail.getType()));
            movieDetail.setVod_play_from(site.getName());
            if ("detail".equals(ac)) {
                String sign = subscriptionService.getTokens().isEmpty() ? "" : aListService.getFile(site, path).getSign();
                movieDetail.setVod_play_url(buildProxyUrl(site, path, sign));
            } else {
                movieDetail.setVod_play_url(fsDetail.getName() + "$" + buildPlayUrl(site, path));
            }
            movieDetail.setVod_content(getParent(path));
            setMovieInfo(site, movieDetail, getParent(path), true);
            if ("PikPakShare".equals(fsDetail.getProvider())) {
                movieDetail.setVod_remarks("P" + movieDetail.getVod_remarks());
            }
            result.getList().add(movieDetail);
        }
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private String getMovieName(String filename, String path) {
        if (filename.startsWith("4K") || filename.equalsIgnoreCase("1080P")
                || NUMBER.matcher(filename).matches() || NUMBER1.matcher(filename).matches()
                || NUMBER2.matcher(filename).matches() || NUMBER3.matcher(filename).matches()) {
            String[] parts = path.split("/");
            if (filename.contains(parts[parts.length - 2])) {
                return filename;
            }
            return parts[parts.length - 2] + " " + filename;
        }
        return filename;
    }

    private String buildPlayUrl(Site site, String path) {
        return encodeUrl(site.getId() + "~~~" + path);
    }

    public MovieList getPlaylist(String ac, Site site, String path) {
        log.info("load playlist {}:{} {}", site.getId(), site.getName(), path);
        String newPath = getParent(path);
        FsDetail fsDetail = aListService.getFile(site, newPath);
        if (fsDetail == null) {
            throw new BadRequestException("加载文件失败: " + newPath);
        }

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(site.getId() + "$" + encodeUrl(path) + "$1");
        movieDetail.setVod_name(getMovieName(fsDetail.getName(), newPath));
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_play_from(site.getName());
        movieDetail.setVod_content(site.getName() + ":" + newPath);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(getListPic());

        setMovieInfo(site, movieDetail, newPath, true);

        FsResponse fsResponse = aListService.listFiles(site, newPath, 1, 0);
        List<FsInfo> files = fsResponse.getFiles().stream()
                .filter(e -> isMediaFormat(e.getName()))
                .collect(Collectors.toList());
        List<String> list = new ArrayList<>();

        if (files.isEmpty()) {
            List<String> folders = fsResponse.getFiles().stream().map(FsInfo::getName).filter(this::isFolder).collect(Collectors.toList());
            log.info("load media files from folders: {}", folders);
            for (String folder : folders) {
                fsResponse = aListService.listFiles(site, newPath + "/" + folder, 1, 0);
                files = fsResponse.getFiles().stream()
                        .filter(e -> isMediaFormat(e.getName()))
                        .collect(Collectors.toList());
                List<String> fileNames = files.stream().map(FsInfo::getName).collect(Collectors.toList());
                String prefix = Utils.getCommonPrefix(fileNames);
                String suffix = Utils.getCommonSuffix(fileNames);
                log.debug("files common prefix: '{}'  common suffix: '{}'", prefix, suffix);

                if (appProperties.isSort()) {
                    fileNames.sort(Comparator.comparing(FileNameInfo::new));
                }

                List<String> urls = new ArrayList<>();
                for (String name : fileNames) {
                    String filepath = newPath + "/" + folder + "/" + name;
                    if ("detail".equals(ac)) {
                        String sign = subscriptionService.getTokens().isEmpty() ? "" : aListService.getFile(site, filepath).getSign();
                        String url = buildProxyUrl(site, filepath, sign);
                        urls.add(fixName(name, prefix, suffix) + "$" + url);
                    } else {
                        String url = buildPlayUrl(site, filepath);
                        urls.add(fixName(name, prefix, suffix) + "$" + url);
                    }
                }
                list.add(String.join("#", urls));
            }
            String prefix = Utils.getCommonPrefix(folders);
            String suffix = Utils.getCommonSuffix(folders);
            log.debug("folders common prefix: '{}'  common suffix: '{}'", prefix, suffix);
            String folderNames = folders.stream().map(e -> fixName(e, prefix, suffix)).collect(Collectors.joining("$$$"));
            movieDetail.setVod_play_from(folderNames);
            movieDetail.setVod_play_url(String.join("$$$", list));
        } else {
            List<String> fileNames = files.stream().map(FsInfo::getName).collect(Collectors.toList());
            String prefix = Utils.getCommonPrefix(fileNames);
            String suffix = Utils.getCommonSuffix(fileNames);
            log.debug("files common prefix: '{}'  common suffix: '{}'", prefix, suffix);

            if (appProperties.isSort()) {
                fileNames.sort(Comparator.comparing(FileNameInfo::new));
            }

            for (String name : fileNames) {
                String filepath = newPath + "/" + name;
                if ("detail".equals(ac)) {
                    String sign = subscriptionService.getTokens().isEmpty() ? "" : aListService.getFile(site, filepath).getSign();
                    String url = buildProxyUrl(site, filepath, sign);
                    list.add(fixName(name, prefix, suffix) + "$" + url);
                } else {
                    String url = buildPlayUrl(site, filepath);
                    list.add(fixName(name, prefix, suffix) + "$" + url);
                }
            }
            movieDetail.setVod_play_url(String.join("#", list));
        }

        MovieList result = new MovieList();
        result.getList().add(movieDetail);
        result.setLimit(result.getList().size());
        result.setTotal(result.getList().size());
        log.debug("playlist: {}", result);
        return result;
    }

    public MovieDetail getMovieDetail(Site site, Meta meta) {
        String path = meta.getPath();
        log.info("load MovieDetail {}:{} {}", site.getId(), site.getName(), path);
        FsDetail fsDetail = aListService.getFile(site, path);
        if (fsDetail == null) {
            throw new BadRequestException("加载文件失败: " + path);
        }

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(String.valueOf(meta.getId()));
        movieDetail.setVod_name(meta.getName());
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_content(site.getName() + ":" + path);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(getListPic());

        setMovieInfo(movieDetail, meta, true);

        FsResponse fsResponse = aListService.listFiles(site, path, 1, 0);
        List<FsInfo> files = fsResponse.getFiles().stream()
                .filter(e -> e.getType() != 1 && isMediaFormat(e.getName()))
                .toList();
        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        int id = 1;

        if (!files.isEmpty()) {
            List<String> fileNames = files.stream().map(FsInfo::getName).collect(Collectors.toList());
            String prefix = Utils.getCommonPrefix(fileNames);
            String suffix = Utils.getCommonSuffix(fileNames);
            log.debug("files common prefix: '{}'  common suffix: '{}'", prefix, suffix);

            if (appProperties.isSort()) {
                fileNames.sort(Comparator.comparing(FileNameInfo::new));
            }

            List<String> urls = new ArrayList<>();
            for (String name : fileNames) {
                paths.add("/" + name);
                String url = meta.getId() + "-" + id++;
                urls.add(fixName(name, prefix, suffix) + "$" + url);
            }
            playFrom.add("默认");
            playUrl.add(String.join("#", urls));
        }

        List<String> folders = fsResponse.getFiles().stream().map(FsInfo::getName).filter(this::isFolder).toList();
        if (!folders.isEmpty()) {
            log.info("load media files from folders: {}", folders);
            String fprefix = Utils.getCommonPrefix(folders);
            String fsuffix = Utils.getCommonSuffix(folders);
            log.debug("folders common prefix: '{}'  common suffix: '{}'", fprefix, fsuffix);

            boolean empty = files.isEmpty();
            List<String> subFolders = new ArrayList<>();
            for (String folder : folders) {
                fsResponse = aListService.listFiles(site, path + "/" + folder, 1, 0);
                files = fsResponse.getFiles().stream()
                        .filter(e -> e.getType() != 1 && isMediaFormat(e.getName()))
                        .toList();
                if (files.isEmpty()) {
                    fsResponse.getFiles().stream()
                            .filter(e -> e.getType() == 1)
                            .forEach(e -> subFolders.add(folder + "/" + e.getName()));
                    continue;
                }
                empty = false;
                List<String> fileNames = files.stream().map(FsInfo::getName).collect(Collectors.toList());
                String prefix = Utils.getCommonPrefix(fileNames);
                String suffix = Utils.getCommonSuffix(fileNames);
                log.debug("files common prefix: '{}'  common suffix: '{}'", prefix, suffix);

                if (appProperties.isSort()) {
                    fileNames.sort(Comparator.comparing(FileNameInfo::new));
                }

                List<String> urls = new ArrayList<>();
                for (String name : fileNames) {
                    paths.add("/" + folder + "/" + name);
                    String url = meta.getId() + "-" + id++;
                    urls.add(fixName(name, prefix, suffix) + "$" + url);
                }
                playFrom.add(fixName(folder, fprefix, fsuffix));
                playUrl.add(String.join("#", urls));
            }

            if (empty) {
                log.debug("subFolders: {}", subFolders);
                fprefix = Utils.getCommonPrefix(subFolders);
                fsuffix = Utils.getCommonSuffix(subFolders);
                log.debug("sub folders common prefix: '{}'  common suffix: '{}'", fprefix, fsuffix);

                for (String folder : subFolders) {
                    fsResponse = aListService.listFiles(site, path + "/" + folder, 1, 0);
                    files = fsResponse.getFiles().stream()
                            .filter(e -> e.getType() != 1 && isMediaFormat(e.getName()))
                            .toList();
                    if (files.isEmpty()) {
                        continue;
                    }
                    List<String> fileNames = files.stream().map(FsInfo::getName).collect(Collectors.toList());
                    String prefix = Utils.getCommonPrefix(fileNames);
                    String suffix = Utils.getCommonSuffix(fileNames);
                    log.debug("files common prefix: '{}'  common suffix: '{}'", prefix, suffix);

                    if (appProperties.isSort()) {
                        fileNames.sort(Comparator.comparing(FileNameInfo::new));
                    }

                    List<String> urls = new ArrayList<>();
                    for (String name : fileNames) {
                        paths.add("/" + folder + "/" + name);
                        String url = meta.getId() + "-" + id++;
                        urls.add(fixName(name, prefix, suffix) + "$" + url);
                    }
                    playFrom.add(fixName(folder, fprefix, fsuffix));
                    playUrl.add(String.join("#", urls));
                }
            }
        }

        movieDetail.setVod_play_from(String.join("$$$", playFrom));
        movieDetail.setVod_play_url(String.join("$$$", playUrl));

        cache.put(meta.getId(), paths);
        log.debug("MovieDetail: {}", movieDetail);
        return movieDetail;
    }

    private static String fixName(String name, String prefix, String suffix) {
        String text = name.replace(prefix, "").replace(suffix, "");
        if (text.isEmpty()) {
            return name;
        }
        return text;
    }

    private void setMovieInfo(Site site, MovieDetail movieDetail, String path, boolean details) {
        if (setTmdbInfo(site, movieDetail, path, details)) {
            return;
        }

        try {
            Movie movie = null;
            if (site.isXiaoya()) {
                movie = doubanService.getByPath(path);
                if (movie == null) {
                    movie = doubanService.getByPath(getParent(path));
                }
            }

            if (movie == null) {
                movie = doubanService.getByName(movieDetail.getVod_name());
            }

            setMovieInfo(movieDetail, movie, null, details);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void setMovieInfo(MovieDetail movieDetail, Meta meta, boolean details) {
        setMovieInfo(movieDetail, meta.getMovie(), meta.getTmdb(), details);
    }

    private void setMovieInfo(MovieDetail movieDetail, Movie movie, Tmdb tmdb, boolean details) {
        if (tmdb != null) {
            setTmdbInfo(movieDetail, tmdb, details);
            return;
        }

        if (movie == null) {
            return;
        }
        movieDetail.setVod_pic(movie.getCover());
        movieDetail.setVod_year(String.valueOf(movie.getYear()));
        movieDetail.setVod_remarks(Utils.trim(movieDetail.getVod_remarks() + Objects.toString(movie.getDbScore(), "")));
        if (!details) {
            return;
        }
        movieDetail.setVod_actor(movie.getActors());
        movieDetail.setVod_director(movie.getDirectors());
        movieDetail.setVod_area(movie.getCountry());
        movieDetail.setType_name(movie.getGenre());
        movieDetail.setVod_lang(movie.getLanguage());
        if (StringUtils.isNotEmpty(movie.getDescription())) {
            movieDetail.setVod_content(movieDetail.getVod_content() + ";\n" + movie.getDescription());
        }
    }

    private boolean setTmdbInfo(Site site, MovieDetail movieDetail, String path, boolean details) {
        Tmdb movie = null;
        try {
            if (site.isXiaoya()) {
                movie = tmdbService.getByPath(path);
                if (movie == null) {
                    movie = tmdbService.getByPath(getParent(path));
                }
            }

            if (movie == null) {
                movie = tmdbService.getByName(movieDetail.getVod_name());
            }

            setTmdbInfo(movieDetail, movie, details);
        } catch (Exception e) {
            log.warn("", e);
        }
        return movie != null;
    }

    private void setTmdbInfo(MovieDetail movieDetail, Tmdb movie, boolean details) {
        if (movie == null) {
            return;
        }
        movieDetail.setVod_pic(movie.getCover());
        movieDetail.setVod_year(String.valueOf(movie.getYear()));
        if ("0.0".equals(movie.getScore())) {
            movie.setScore("");
        }
        movieDetail.setVod_remarks(Utils.trim(movieDetail.getVod_remarks() + Objects.toString(movie.getScore(), "")));
        if (!details) {
            return;
        }
        movieDetail.setVod_actor(movie.getActors());
        movieDetail.setVod_director(movie.getDirectors());
        movieDetail.setVod_area(movie.getCountry());
        movieDetail.setType_name(movie.getGenre());
        movieDetail.setVod_lang(movie.getLanguage());
        if (StringUtils.isNotEmpty(movie.getDescription())) {
            movieDetail.setVod_content(movieDetail.getVod_content() + ";\n" + movie.getDescription());
        }
    }

    private void fixCover(MovieDetail movie) {
        try {
            if (movie.getVod_pic() != null && !movie.getVod_pic().isEmpty()) {
                String cover = ServletUriComponentsBuilder.fromCurrentRequest()
                        .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                        .replacePath("/images")
                        .replaceQuery("url=" + movie.getVod_pic())
                        .build()
                        .toUriString();
                log.debug("cover url: {}", cover);
                movie.setVod_pic(cover);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private String getCover(String thumb, int type) {
        String pic = thumb;
        if (pic.isEmpty() && type == 1) {
            pic = ServletUriComponentsBuilder.fromCurrentRequest()
                    .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                    .replacePath("/folder.png")
                    .replaceQuery(null)
                    .build()
                    .toUriString();
        }
        return pic;
    }

    private String fileSize(long size) {
        double sz = size;
        String filesize;
        if (sz > 1024 * 1024 * 1024 * 1024.0) {
            sz /= (1024 * 1024 * 1024 * 1024.0);
            filesize = "TB";
        } else if (sz > 1024 * 1024 * 1024.0) {
            sz /= (1024 * 1024 * 1024.0);
            filesize = "GB";
        } else if (sz > 1024 * 1024.0) {
            sz /= (1024 * 1024.0);
            filesize = "MB";
        } else {
            sz /= 1024.0;
            filesize = "KB";
        }
        String remark = "";
        if (size > 0) {
            remark = String.format("%.2f%s", sz, filesize);
        }
        return remark;
    }

    private boolean isMediaFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1);
            return appProperties.getFormats().contains(suffix);
        }
        return false;
    }

    private boolean isSubtitleFormat(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            String suffix = name.substring(index + 1);
            return appProperties.getSubtitles().contains(suffix);
        }
        return false;
    }

    private String getExt(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            return name.substring(index + 1);
        }
        return name;
    }

    private String getParent(String path) {
        int index = path.lastIndexOf('/');
        if (index > 0) {
            return path.substring(0, index);
        }
        return path;
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

    private String fixPath(String path) {
        return path.replaceAll("/+", "/");
    }

    private String fixHttp(String url) {
        if (url.startsWith("//")) {
            url = "http:" + url;
        }

        if (url.startsWith("http://localhost")) {
            String port = appProperties.isHostmode() ? "5234" : environment.getProperty("ALIST_PORT", "5344");
            String proxy = ServletUriComponentsBuilder.fromCurrentRequest()
                    .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                    .port(port)
                    .replacePath("/")
                    .replaceQuery("")
                    .build()
                    .toUriString();
            url = url.replace("http://localhost:5244/", proxy);
            url = url.replace("http://localhost/", proxy);
            log.debug("fixHttp: {}", url);
        }

        return url;
    }

    private String buildProxyUrl(Site site, String path, String sign) {
        if (site.getUrl().startsWith("http://localhost")) {
            return ServletUriComponentsBuilder.fromCurrentRequest()
                    .port(appProperties.isHostmode() ? "5234" : environment.getProperty("ALIST_PORT", "5344"))
                    .replacePath("/d" + path)
                    .replaceQuery("sign=" + sign)
                    .build()
                    .toUri()
                    .toASCIIString();
        } else {
            if (StringUtils.isNotBlank(site.getFolder())) {
                path = fixPath(site.getFolder() + "/" + path);
            }
            return UriComponentsBuilder.fromHttpUrl(site.getUrl())
                    .replacePath("/d" + path)
                    .replaceQuery("sign=" + sign)
                    .build()
                    .toUri()
                    .toASCIIString();
        }
    }

    private String buildUrl(Site site, String path) {
        if (site == null || site.getUrl().startsWith("http://localhost")) {
            return ServletUriComponentsBuilder.fromCurrentRequest()
                    .port(appProperties.isHostmode() ? "5234" : environment.getProperty("ALIST_PORT", "5344"))
                    .replacePath(path)
                    .replaceQuery(null)
                    .build()
                    .toUri()
                    .toASCIIString();
        } else {
            return UriComponentsBuilder.fromHttpUrl(site.getUrl())
                    .replacePath(path)
                    .replaceQuery(null)
                    .build()
                    .toUri()
                    .toASCIIString();
        }
    }

    private String encodeUrl(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return url;
        }
    }

}
