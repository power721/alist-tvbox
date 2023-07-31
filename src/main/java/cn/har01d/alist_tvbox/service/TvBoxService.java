package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.AListAlias;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.Meta;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import static cn.har01d.alist_tvbox.util.Constants.FOLDER_PIC;
import static cn.har01d.alist_tvbox.util.Constants.LIST_PIC;
import static cn.har01d.alist_tvbox.util.Constants.PLAYLIST;

@Slf4j
@Service
public class TvBoxService {
    private final AccountRepository accountRepository;
    private final AListAliasRepository aliasRepository;
    private final ShareRepository shareRepository;
    private final MetaRepository metaRepository;

    private final AListService aListService;
    private final IndexService indexService;
    private final SiteService siteService;
    private final AppProperties appProperties;
    private final DoubanService doubanService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
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

    public TvBoxService(AccountRepository accountRepository,
                        AListAliasRepository aliasRepository,
                        ShareRepository shareRepository,
                        MetaRepository metaRepository,
                        AListService aListService,
                        IndexService indexService,
                        SiteService siteService,
                        AppProperties appProperties,
                        DoubanService doubanService,
                        ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.aliasRepository = aliasRepository;
        this.shareRepository = shareRepository;
        this.metaRepository = metaRepository;
        this.aListService = aListService;
        this.indexService = indexService;
        this.siteService = siteService;
        this.appProperties = appProperties;
        this.doubanService = doubanService;
        this.objectMapper = objectMapper;
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
                if (id++ == 1 && appProperties.isXiaoya()) {
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
        int type = appProperties.isMix() ? 1 : 0;
        Category category = new Category();
        category.setType_id(site.getId() + "$/" + "$" + type);
        category.setType_name("\uD83C\uDFAC" + site.getName());
        category.setType_flag(type);
        result.getCategories().add(category);
        result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters2), new Filter("score", "评分", filters3), new Filter("year", "年份", years)));

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
                        String prefix = getCommonPrefix(paths);
                        String suffix = getCommonSuffix(paths);
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

        if (pp > 0) {
            Category category = new Category();
            category.setType_id("1$/\uD83D\uDD78️我的PikPak分享$1");
            category.setType_name("PikPak");
            result.getCategories().add(category);
            result.getFilters().put(category.getType_id(), List.of(new Filter("sort", "排序", filters)));
        }
    }

    public MovieList recommend() {
        MovieList result = new MovieList();
        result.setList(doubanService.getHotRank());
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        return result;
    }

    public MovieList msearch(Integer type, String keyword) {
        String name = TextUtils.fixName(keyword);
        MovieList result = search(type, name);
        if (result.getTotal() > 0) {
            return getDetail(result.getList().get(0).getVod_id());
        }
        return result;
    }

    public MovieList search(Integer type, String keyword) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        if (type != null && type == 0) {
            for (Meta meta : metaRepository.findByPathContains(keyword)) {
                Movie movie = meta.getMovie();
                String name;
                if (movie == null) {
                    name = getNameFromPath(meta.getPath());
                } else {
                    name = movie.getName();
                }

                String newPath = fixPath(meta.getPath() + "/" + PLAYLIST);
                MovieDetail movieDetail = new MovieDetail();
                movieDetail.setVod_id(getXiaoyaSite().getId() + "$" + newPath + "$0");
                movieDetail.setVod_name(name);
                movieDetail.setVod_pic(Constants.ALIST_PIC);
                setDoubanInfo(movieDetail, movie, false);
                list.add(movieDetail);
            }
        } else {
            List<Future<List<MovieDetail>>> futures = new ArrayList<>();
            for (Site site : siteService.list()) {
                if (site.isSearchable()) {
                    if (StringUtils.isNotEmpty(site.getIndexFile())) {
                        futures.add(executorService.submit(() -> searchByFile(site, keyword)));
                    } else {
                        futures.add(executorService.submit(() -> searchByApi(site, keyword)));
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

    private List<MovieDetail> searchByFile(Site site, String keyword) throws IOException {
        String indexFile = site.getIndexFile();
        if (indexFile.startsWith("http://") || indexFile.startsWith("https://")) {
            indexFile = indexService.downloadIndexFile(site);
        }

        List<MovieDetail> list = searchFromIndexFile(site, keyword, indexFile);
        File customIndexFile = new File("/data/index/" + site.getId() + "/custom_index.txt");
        log.debug("custom index file: {}", customIndexFile);
        if (customIndexFile.exists()) {
            list.addAll(searchFromIndexFile(site, keyword, customIndexFile.getAbsolutePath()));
        }
        log.debug("search \"{}\" from site {}:{}, result: {}", keyword, site.getId(), site.getName(), list.size());
        return list;
    }

    private List<MovieDetail> searchFromIndexFile(Site site, String keyword, String indexFile) throws IOException {
        log.info("search \"{}\" from site {}:{}, index file: {}", keyword, site.getId(), site.getName(), indexFile);
        Set<String> keywords = Arrays.stream(keyword.split("\\s+")).collect(Collectors.toSet());
        Set<String> lines = Files.readAllLines(Paths.get(indexFile))
                .stream()
                .filter(path -> keywords.stream().allMatch(path::contains))
                .limit(appProperties.getMaxSearchResult())
                .collect(Collectors.toSet());

        List<MovieDetail> list = new ArrayList<>();
        for (String line : lines) {
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
            movieDetail.setVod_id(site.getId() + "$" + path + "$1");
            movieDetail.setVod_name(getNameFromPath(line));
            movieDetail.setVod_pic(Constants.ALIST_PIC);
            movieDetail.setVod_tag(FILE);
            if (!isMediaFile) {
                setDoubanInfo(site, movieDetail, getParent(path), false);
            }
            list.add(movieDetail);
        }
        list.sort(Comparator.comparing(MovieDetail::getVod_id));
        return list;
    }

    private List<MovieDetail> searchByApi(Site site, String keyword) {
        if (site.isXiaoya()) {
            try {
                return searchByXiaoya(site, keyword);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        log.info("search \"{}\" from site {}:{}", keyword, site.getId(), site.getName());
        return aListService.search(site, keyword)
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
                    movieDetail.setVod_id(site.getId() + "$" + path + "$1");
                    movieDetail.setVod_name(e.getName());
                    movieDetail.setVod_pic(Constants.ALIST_PIC);
                    movieDetail.setVod_tag(FILE);
                    if (!isMediaFile) {
                        setDoubanInfo(site, movieDetail, getParent(path), false);
                    }
                    return movieDetail;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<MovieDetail> searchByXiaoya(Site site, String keyword) throws IOException {
        if (site.getId() == 1 && appProperties.isXiaoya()) {
            List<MovieDetail> list = searchFromIndexFile(site, keyword, "/data/index/index.video.txt");
            File customIndexFile = new File("/data/index/" + site.getId() + "/custom_index.txt");
            log.debug("custom index file: {}", customIndexFile);
            if (customIndexFile.exists()) {
                list.addAll(searchFromIndexFile(site, keyword, customIndexFile.getAbsolutePath()));
            }
            return list;
        }

        log.info("search \"{}\" from xiaoya {}:{}", keyword, site.getId(), site.getName());
        String url = site.getUrl() + "/search?url=&type=video&box=" + keyword;
        Document doc = Jsoup.connect(url).get();
        Elements links = doc.select("div a[href]");

        List<MovieDetail> list = new ArrayList<>();
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
            movieDetail.setVod_id(site.getId() + "$" + path + "$1");
            movieDetail.setVod_name(getNameFromPath(name));
            movieDetail.setVod_pic(Constants.ALIST_PIC);
            movieDetail.setVod_tag(FILE);
            if (!isMediaFile) {
                setDoubanInfo(site, movieDetail, getParent(path), false);
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
        if ("0".equals(id)) {
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

    public MovieList getMovieList(String tid, String filter, String sort, int page) {
        Site site = getSite(tid);
        String[] parts = tid.split("\\$");
        String path = parts[1];
        int type = 1;
        if (parts.length > 2) {
            type = Integer.parseInt(parts[2]);
        }

        if (type == 0) {
            return getMetaList(tid, filter, sort, page);
        }

        if (path.contains(PLAYLIST)) {
            return getPlaylist(site, path);
        }

        List<MovieDetail> folders = new ArrayList<>();
        List<MovieDetail> files = new ArrayList<>();
        List<MovieDetail> playlists = new ArrayList<>();
        MovieList result = new MovieList();

        int size = appProperties.getPageSize();
        FsResponse fsResponse = aListService.listFiles(site, path, page, size);
        int total = fsResponse.getTotal();

        for (FsInfo fsInfo : fsResponse.getFiles()) {
            if (fsInfo.getType() != 1 && !isMediaFormat(fsInfo.getName())) {
                total--;
                continue;
            }

            String newPath = fixPath(path + "/" + fsInfo.getName());
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site.getId() + "$" + newPath + "$1");
            movieDetail.setVod_name(fsInfo.getName());
            movieDetail.setVod_tag(fsInfo.getType() == 1 ? FOLDER : FILE);
            movieDetail.setVod_pic(getCover(fsInfo.getThumb(), fsInfo.getType()));
            movieDetail.setVod_remarks(fileSize(fsInfo.getSize()) + (fsInfo.getType() == 1 ? "文件夹" : ""));
            movieDetail.setVod_time(fsInfo.getModified());
            movieDetail.setSize(fsInfo.getSize());
            if (fsInfo.getType() == 1) {
                setDoubanInfo(site, movieDetail, newPath, false);
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

    public MovieList getMetaList(String tid, String filter, String sort, int page) {
        Site site = getSite(tid);
        String[] parts = tid.split("\\$");
        String path = parts[1];
//        if (path.contains(PLAYLIST)) {
//            return getPlaylist(site, path);
//        }

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
            sort = "id,desc";
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
                if (dir.isEmpty()) {
                    paths = Arrays.stream(aListAlias.getContent().split("\n")).map(e -> e.split(":")[0]).collect(Collectors.toList());
                    log.debug("{}: {} {}", path, aListAlias, paths);
                } else {
                    paths.add(fixPath(dir));
                }
            } else {
                paths.add(path);
            }
        }
        log.debug("paths: {}", paths);

        int pages = 0;
        Page<Meta> list = new PageImpl<>(List.of());
        for (String line : paths) {
            path = line;
            log.debug("get movies from {}", path);
            if (pageable.getSort() != null) {
                pageable = PageRequest.of(page - pages - 1, size, pageable.getSort());
            } else {
                pageable = PageRequest.of(page - pages - 1, size);
            }
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
        Map<String, List<Meta>> map = new LinkedHashMap<>();
        Map<String, Boolean> added = new HashMap<>();
        for (Meta meta : list) {
            Movie movie = meta.getMovie();
            String name;
            if (movie == null) {
                name = getNameFromPath(meta.getPath());
            } else {
                name = movie.getName();
            }
            List<Meta> metas = map.getOrDefault(name, new ArrayList<>());
            metas.add(meta);
            map.put(name, metas);
        }

        for (Meta meta : list) {
            Movie movie = meta.getMovie();
            String name;
            if (movie == null) {
                name = getNameFromPath(meta.getPath());
            } else {
                name = movie.getName();
            }

            if (added.containsKey(name)) {
                log.debug("skip {}: {}", name, meta.getPath());
                continue;
            }

            List<Meta> metas = map.get(name);
            MovieDetail movieDetail = new MovieDetail();
            log.debug("{} {}", name, metas.size());
            if (metas.size() > 1) {
                String ids = metas.stream().map(Meta::getId).map(String::valueOf).collect(Collectors.joining("-"));
                movieDetail.setVod_id(site.getId() + "$" + ids + "$0");
                added.put(name, true);
            } else {
                String newPath = fixPath(meta.getPath() + (isMediaFile(meta.getPath()) ? "" : "/" + PLAYLIST));
                movieDetail.setVod_id(site.getId() + "$" + encodeUrl(newPath) + "$0");
            }
            movieDetail.setVod_name(name);
            movieDetail.setVod_pic(Constants.ALIST_PIC);
            setDoubanInfo(movieDetail, movie, false);
            files.add(movieDetail);
            log.debug("{}", movieDetail);
        }

        result.getList().addAll(files);

        result.setPage(page);
        result.setTotal((int) list.getTotalElements());
        result.setLimit(files.size());
        result.setPagecount(list.getTotalPages());
        log.debug("list: {}", result);
        return result;
    }

    private void sortFiles(String sort, List<MovieDetail> folders, List<MovieDetail> files) {
        if (sort == null) {
            sort = "name,asc";
        }
        Comparator<MovieDetail> comparator;
        switch (sort) {
            case "name,asc":
                comparator = Comparator.comparing(e -> new FileNameInfo(e.getVod_name()));
                break;
            case "time,asc":
                comparator = Comparator.comparing(MovieDetail::getVod_time);
                break;
            case "size,asc":
                comparator = Comparator.comparing(MovieDetail::getSize);
                break;
            case "name,desc":
                comparator = Comparator.comparing(e -> new FileNameInfo(e.getVod_name()));
                comparator = comparator.reversed();
                break;
            case "time,desc":
                comparator = Comparator.comparing(MovieDetail::getVod_time);
                comparator = comparator.reversed();
                break;
            case "size,desc":
                comparator = Comparator.comparing(MovieDetail::getSize);
                comparator = comparator.reversed();
                break;
            default:
                return;
        }
        folders.sort(comparator);
        files.sort(comparator);
    }

    private List<MovieDetail> generatePlaylist(String path, int total, List<MovieDetail> files) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(path + "$1");
        movieDetail.setVod_name("播放列表");
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);
        if (total < appProperties.getPageSize()) {
            movieDetail.setVod_remarks("共" + files.size() + "集");
        }

        List<MovieDetail> list = new ArrayList<>();
        list.add(movieDetail);

        return list;
    }

    public String getPlayUrl(Integer siteId, String path) {
        Site site = siteService.getById(siteId);
        FsDetail fsDetail = null;
        if (isMediaFile(path)) {
            log.info("get play url - site {}:{}  path: {}", site.getId(), site.getName(), path);
            fsDetail = aListService.getFile(site, path);
        } else {
            FsResponse fsResponse = aListService.listFiles(site, path, 1, 100);
            for (FsInfo fsInfo : fsResponse.getFiles()) {
                if (isMediaFormat(fsInfo.getName())) {
                    fsDetail = aListService.getFile(site, path + "/" + fsInfo.getName());
                    break;
                }
            }
            if (fsDetail == null) {
                throw new BadRequestException("找不到文件 " + path);
            }
            log.info("get play url - site {}:{}  path: {}", site.getId(), site.getName(), path + "/" + fsDetail.getName());
        }
        String url = fixHttp(fsDetail.getRawUrl());
        if (url.contains("abnormal.png")) {
            throw new IllegalStateException("阿里云盘账号异常");
        } else if (url.contains("diskfull.png")) {
            throw new IllegalStateException("阿里云盘空间不足");
        } else if (url.contains(".png")) {
            log.warn("play url: {}", url);
        }
        return url;
    }

    public String getPlayUrl(Integer siteId, Integer id) {
        Meta meta = metaRepository.findById(id).orElseThrow(NotFoundException::new);
        log.debug("getPlayUrl: {} {}", siteId, id);
        return getPlayUrl(siteId, meta.getPath());
    }

    public String getPlayUrl(Integer siteId, Integer id, String path) {
        Meta meta = metaRepository.findById(id).orElseThrow(NotFoundException::new);
        log.debug("getPlayUrl: {} {}", siteId, id);
        return getPlayUrl(siteId, meta.getPath() + path);
    }

    private static final Pattern ID_PATH = Pattern.compile("(\\d+)(-\\d+)+");

    public MovieList getDetail(String tid) {
        Site site = getSite(tid);
        String[] parts = tid.split("\\$");
        String path = parts[1];
        if (path.contains(PLAYLIST)) {
            return getPlaylist(site, path);
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
            for (int i = 1; i <= list.size(); ++i) {
                from.add("版本" + i);
            }
            movieDetail.setVod_play_from(String.join("$$$", from));
            String playUrl;
            if (isMediaFile(meta.getPath())) {
                playUrl = list.stream().map(m -> getNameFromPath(m.getPath()) + "$" + buildPlayUrl(site, m)).collect(Collectors.joining("$$$"));
            } else {
                playUrl = list.stream().map(e -> buildPlaylist(site, e.getId(), e.getPath())).collect(Collectors.joining("$$$"));
            }
            movieDetail.setVod_play_url(playUrl);

            movieDetail.setVod_content(site.getName() + ":" + getParent(path));
            setDoubanInfo(movieDetail, meta.getMovie(), true);
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
            movieDetail.setVod_play_url(fsDetail.getName() + "$" + buildPlayUrl(site, path));
            movieDetail.setVod_content(site.getName() + ":" + getParent(path));
            setDoubanInfo(site, movieDetail, getParent(path), true);
            result.getList().add(movieDetail);
        }
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private String buildPlaylist(Site site, Integer id, String path) {
        FsResponse fsResponse = aListService.listFiles(site, path, 1, 0);
        List<FsInfo> files = fsResponse.getFiles().stream()
                .filter(e -> isMediaFormat(e.getName()))
                .collect(Collectors.toList());

        List<String> list = new ArrayList<>();

        if (files.isEmpty()) {
            List<String> folders = fsResponse.getFiles().stream().map(FsInfo::getName).filter(this::isFolder).collect(Collectors.toList());
            log.info("load media files from folders: {}", folders);
            for (String folder : folders) {
                fsResponse = aListService.listFiles(site, path + "/" + folder, 1, 0);
                files = fsResponse.getFiles().stream()
                        .filter(e -> isMediaFormat(e.getName()))
                        .collect(Collectors.toList());
                if (appProperties.isSort()) {
                    files.sort(Comparator.comparing(e -> new FileNameInfo(e.getName())));
                }

                List<String> fileNames = files.stream().map(FsInfo::getName).collect(Collectors.toList());
                String prefix = getCommonPrefix(fileNames);
                String suffix = getCommonSuffix(fileNames);
                for (String name : fileNames) {
                    list.add(getName(name.replace(prefix, "").replace(suffix, "")) + "$" + buildPlayUrl(site, id + "/" + folder + "/" + name));
                }
            }
        } else {
            if (appProperties.isSort()) {
                files.sort(Comparator.comparing(e -> new FileNameInfo(e.getName())));
            }

            List<String> fileNames = files.stream().map(FsInfo::getName).collect(Collectors.toList());
            String prefix = getCommonPrefix(fileNames);
            String suffix = getCommonSuffix(fileNames);
            for (String name : fileNames) {
                list.add(getName(name.replace(prefix, "").replace(suffix, "")) + "$" + buildPlayUrl(site, id + "/" + name));
            }
        }

        return String.join("#", list);
    }

    private String buildPlayUrl(Site site, String path) {
        return encodeUrl(site.getId() + "~~~" + path);
    }

    private String buildPlayUrl(Site site, Meta meta) {
        return buildPlayUrl(site, String.valueOf(meta.getId()));
    }

    public MovieList getPlaylist(Site site, String path) {
        log.info("load playlist {}:{} {}", site.getId(), site.getName(), path);
        String newPath = getParent(path);
        FsDetail fsDetail = aListService.getFile(site, newPath);

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(site.getId() + "$" + path + "$1");
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_play_from(site.getName());
        movieDetail.setVod_content(site.getName() + ":" + newPath);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);

        setDoubanInfo(site, movieDetail, newPath, true);

        FsResponse fsResponse = aListService.listFiles(site, newPath, 1, 0);
        List<FsInfo> files = fsResponse.getFiles().stream()
                .filter(e -> isMediaFormat(e.getName()))
                .collect(Collectors.toList());
        Map<String, String> map = new HashMap<>();
        List<String> list = new ArrayList<>();

        if (files.isEmpty()) {
            List<String> folders = fsResponse.getFiles().stream().map(FsInfo::getName).filter(this::isFolder).collect(Collectors.toList());
            log.info("load media files from folders: {}", folders);
            for (String folder : folders) {
                fsResponse = aListService.listFiles(site, newPath + "/" + folder, 1, 0);
                files = fsResponse.getFiles().stream()
                        .filter(e -> isMediaFormat(e.getName()))
                        .collect(Collectors.toList());
//                List<String> subtitles = fsResponse.getFiles().stream()
//                        .map(FsInfo::getName)
//                        .filter(this::isSubtitleFormat)
//                        .sorted()
//                        .collect(Collectors.toList());
//                List<String> names = files.stream().map(FsInfo::getName).sorted().collect(Collectors.toList());
//                for (int i = 0; i < subtitles.size() && i < names.size(); ++i) {
//                    map.put(names.get(i), subtitles.get(i));
//                }

                if (appProperties.isSort()) {
                    files.sort(Comparator.comparing(e -> new FileNameInfo(e.getName())));
                }

                List<String> urls = new ArrayList<>();
                List<String> fileNames = files.stream().map(FsInfo::getName).collect(Collectors.toList());
                String prefix = getCommonPrefix(fileNames);
                String suffix = getCommonSuffix(fileNames);
                log.debug("files common prefix: '{}'  common suffix: '{}'", prefix, suffix);
                for (String name : fileNames) {
                    String url = buildPlayUrl(site, newPath + "/" + folder + "/" + name);
                    if (map.containsKey(name)) {
                        url += "@@@" + newPath + "/" + folder + "/" + map.get(name);
                    }
                    urls.add(getName(name.replace(prefix, "").replace(suffix, "")) + "$" + url);
                }
                list.add(String.join("#", urls));
            }
            String prefix = getCommonPrefix(folders);
            String suffix = getCommonSuffix(folders);
            log.debug("folders common prefix: '{}'  common suffix: '{}'", prefix, suffix);
            String folderNames = folders.stream().map(e -> e.replace(prefix, "").replace(suffix, "")).collect(Collectors.joining("$$$"));
            movieDetail.setVod_play_from(folderNames);
            movieDetail.setVod_play_url(String.join("$$$", list));
        } else {
//          List<String> subtitles = fsResponse.getFiles().stream()
//                .map(FsInfo::getName)
//                .filter(this::isSubtitleFormat)
//                .sorted()
//                .collect(Collectors.toList());
//        List<String> names = files.stream().map(FsInfo::getName).sorted().collect(Collectors.toList());
//            for (int i = 0; i < subtitles.size() && i < names.size(); ++i) {
//                map.put(names.get(i), subtitles.get(i));
//            }

            if (appProperties.isSort()) {
                files.sort(Comparator.comparing(e -> new FileNameInfo(e.getName())));
            }

            List<String> fileNames = files.stream().map(FsInfo::getName).collect(Collectors.toList());
            String prefix = getCommonPrefix(fileNames);
            String suffix = getCommonSuffix(fileNames);
            log.debug("files common prefix: '{}'  common suffix: '{}'", prefix, suffix);

            for (String name : fileNames) {
                String url = buildPlayUrl(site, newPath + "/" + name);
                if (map.containsKey(name)) {
                    url += "@@@" + newPath + "/" + map.get(name);
                }
                list.add(getName(name.replace(prefix, "").replace(suffix, "")) + "$" + url);
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

    private void setDoubanInfo(Site site, MovieDetail movieDetail, String path, boolean details) {
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

            setDoubanInfo(movieDetail, movie, details);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private static void setDoubanInfo(MovieDetail movieDetail, Movie movie, boolean details) {
        if (movie == null) {
            return;
        }
        fixCover(movie);
        movieDetail.setVod_pic(movie.getCover());
        movieDetail.setVod_year(String.valueOf(movie.getYear()));
        movieDetail.setVod_remarks(movie.getDbScore());
        if (!details) {
            return;
        }
        movieDetail.setVod_actor(movie.getActors());
        movieDetail.setVod_director(movie.getDirectors());
        movieDetail.setVod_area(movie.getCountry());
        movieDetail.setType_name(movie.getGenre());
        movieDetail.setVod_lang(movie.getLanguage());
        if (StringUtils.isNotEmpty(movie.getDescription())) {
            movieDetail.setVod_content(movie.getDescription());
        }
    }

    private static void fixCover(MovieDetail movie) {
        try {
            if (movie.getVod_pic() != null && !movie.getVod_pic().isEmpty()) {
                String cover = ServletUriComponentsBuilder.fromCurrentRequest()
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

    private static void fixCover(Movie movie) {
        try {
            if (movie.getCover() != null && !movie.getCover().isEmpty() && !movie.getCover().contains("/images")) {
                String cover = ServletUriComponentsBuilder.fromCurrentRequest()
                        .replacePath("/images")
                        .replaceQuery("url=" + movie.getCover())
                        .build()
                        .toUriString();
                log.debug("movie: {} cover url: {}", movie.getId(), cover);
                movie.setCover(cover);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private static String getCover(String thumb, int type) {
        String pic = thumb;
        if (pic.isEmpty() && type == 1) {
            pic = FOLDER_PIC;
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

    private String getName(String name) {
        int index = name.lastIndexOf('.');
        if (index > 0) {
            return name.substring(0, index);
        }
        return name;
    }

    private String getCommonPrefix(List<String> names) {
        int n = names.size();
        if (n <= 1) return "";
        String ans = names.get(0);
        for (int i = 1; i < n; i++) {
            int j = 0;
            while (j < ans.length() && j < names.get(i).length() && ans.charAt(j) == names.get(i).charAt(j)) {
                j++;
            }
            ans = names.get(i).substring(0, j);
        }
        return ans;
    }

    private String getCommonSuffix(List<String> names) {
        int n = names.size();
        if (n <= 1) return "";
        names = names.stream().map(e -> new StringBuilder(e).reverse().toString()).collect(Collectors.toList());
        String text = new StringBuilder(getCommonPrefix(names)).reverse().toString();
        if (text.startsWith("集")) {
            return text.substring(1);
        }
        return text;
    }

    private String fixPath(String path) {
        return path.replaceAll("/+", "/");
    }

    private String fixHttp(String url) {
        if (url.startsWith("//")) {
            return "http:" + url;
        }
        return url;
    }

    private String encodeUrl(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return url;
        }
    }

}
