package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.DoubanData;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.model.*;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.*;

@Slf4j
@Service
public class TvBoxService {

    private final AListService aListService;
    private final IndexService indexService;
    private final SiteService siteService;
    private final AppProperties appProperties;
    private final DoubanService doubanService;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final List<FilterValue> filters = Arrays.asList(
            new FilterValue("原始顺序", ""),
            new FilterValue("名字⬆️", "name,asc"),
            new FilterValue("名字⬇️", "name,desc"),
            new FilterValue("时间⬆️", "time,asc"),
            new FilterValue("时间⬇️", "time,desc"),
            new FilterValue("大小⬆️", "size,asc"),
            new FilterValue("大小⬇️", "size,desc")
    );

    public TvBoxService(AListService aListService, IndexService indexService, SiteService siteService, AppProperties appProperties, DoubanService doubanService) {
        this.aListService = aListService;
        this.indexService = indexService;
        this.siteService = siteService;
        this.appProperties = appProperties;
        this.doubanService = doubanService;
    }

    public CategoryList getCategoryList() {
        CategoryList result = new CategoryList();

        for (Site site : siteService.list()) {
            Category category = new Category();
            category.setType_id(site.getId() + "$/");
            category.setType_name(site.getName());
            result.getList().add(category);
            result.getFilters().put(category.getType_id(), new Filter("sort", "排序", filters));
        }

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("category: {}", result);
        return result;
    }

    public MovieList search(String keyword) {
        MovieList result = new MovieList();
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

        List<MovieDetail> list = new ArrayList<>();
        for (Future<List<MovieDetail>> future : futures) {
            try {
                list.addAll(future.get());
            } catch (Exception e) {
                log.warn("", e);
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

        log.info("search \"{}\" from site {}:{}, index file: {}", keyword, site.getId(), site.getName(), indexFile);
        Set<String> keywords = Arrays.stream(keyword.split("\\s+")).collect(Collectors.toSet());
        Set<String> lines = Files.readAllLines(Paths.get(indexFile))
                .stream()
                .filter(path -> keywords.stream().allMatch(path::contains))
                .collect(Collectors.toSet());

        List<MovieDetail> list = new ArrayList<>();
        for (String line : lines) {
            boolean isMediaFile = isMediaFile(line);
            if (isMediaFile && lines.contains(getParent(line))) {
                continue;
            }
            String path = fixPath("/" + line + (isMediaFile ? "" : PLAYLIST));
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site.getId() + "$" + path);
            movieDetail.setVod_name(site.getName() + ":" + line);
            movieDetail.setVod_tag(isMediaFile ? FILE : FOLDER);
            list.add(movieDetail);
        }
        list.sort(Comparator.comparing(MovieDetail::getVod_id));

        log.debug("search \"{}\" from site {}:{}, result: {}", keyword, site.getId(), site.getName(), list.size());
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
                    MovieDetail movieDetail = new MovieDetail();
                    movieDetail.setVod_id(site.getId() + "$" + path);
                    movieDetail.setVod_name(e.getName());
                    movieDetail.setVod_tag(isMediaFile ? FILE : FOLDER);
                    return movieDetail;
                })
                .collect(Collectors.toList());
    }

    private List<MovieDetail> searchByXiaoya(Site site, String keyword) throws IOException {
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
            movieDetail.setVod_id(site.getId() + "$" + path);
            movieDetail.setVod_name(name);
            movieDetail.setVod_tag(isMediaFile ? FILE : FOLDER);
            list.add(movieDetail);
        }

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
        try {
            Integer siteId = Integer.parseInt(id);
            return siteService.getById(siteId);
        } catch (NumberFormatException e) {
            // ignore
        }

        return siteService.getByName(id);
    }

    public MovieList getMovieList(String tid, String sort, int page) {
        int index = tid.indexOf('$');
        Site site = getSite(tid);
        String path = tid.substring(index + 1);
        List<MovieDetail> folders = new ArrayList<>();
        List<MovieDetail> files = new ArrayList<>();
        List<MovieDetail> playlists = new ArrayList<>();
        MovieList result = new MovieList();

        int size = appProperties.getPageSize();
        FsResponse fsResponse = aListService.listFiles(site, path, page, size);
        int total = fsResponse.getTotal();

        for (FsInfo fsInfo : fsResponse.getFiles()) {
            if (fsInfo.getType() != 1 && fsInfo.getName().equals(PLAYLIST_TXT)) {
                playlists = generatePlaylistFromFile(site, path + "/" + PLAYLIST_TXT);
                total--;
                continue;
            }
            if (fsInfo.getType() != 1 && !isMediaFormat(fsInfo.getName())) {
                total--;
                continue;
            }

            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site.getId() + "$" + fixPath(path + "/" + fsInfo.getName()));
            movieDetail.setVod_name(fsInfo.getName());
            movieDetail.setVod_tag(fsInfo.getType() == 1 ? FOLDER : FILE);
            movieDetail.setVod_pic(getCover(fsInfo.getThumb(), fsInfo.getType()));
            movieDetail.setVod_remarks(fileSize(fsInfo.getSize()) + (fsInfo.getType() == 1 ? "文件夹" : ""));
            movieDetail.setVod_time(fsInfo.getModified());
            movieDetail.setSize(fsInfo.getSize());
            if (fsInfo.getType() == 1) {
                folders.add(movieDetail);
            } else {
                files.add(movieDetail);
            }
        }

        sortFiles(sort, folders, files);

        result.getList().addAll(folders);

        if (page == 1 && files.size() > 1 && playlists.isEmpty()) {
            playlists = generatePlaylist(site.getId() + "$" + fixPath(path + PLAYLIST), total - folders.size(), files);
        }

        result.getList().addAll(playlists);
        result.getList().addAll(files);

        result.setPage(page);
        result.setTotal(total);
        result.setLimit(size);
        result.setPagecount((total + size - 1) / size);
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

    private List<MovieDetail> generatePlaylistFromFile(Site site, String path) {
        List<MovieDetail> list = new ArrayList<>();
        String content = aListService.readFileContent(site, path);
        if (content != null) {
            int count = 0;
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site.getId() + "$" + path + "#" + 0);
            movieDetail.setVod_name("播放列表");
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_pic(LIST_PIC);

            for (String line : content.split("[\r\n]")) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    if (text.startsWith("#cover")) {
                        movieDetail.setVod_pic(text.substring("#cover".length()).trim());
                    }
                    continue;
                }
                if (text.contains(",#genre#")) {
                    if (count > 0) {
                        movieDetail.setVod_remarks("共" + count + "集");
                        list.add(movieDetail);
                    }
                    count = 0;
                    String[] parts = text.split(",");
                    movieDetail = new MovieDetail();
                    movieDetail.setVod_id(site.getId() + "$" + path + "#" + list.size());
                    movieDetail.setVod_name(parts[0]);
                    movieDetail.setVod_tag(FILE);
                    movieDetail.setVod_pic(parts.length == 3 ? parts[2].trim() : LIST_PIC);
                } else {
                    count++;
                }
            }

            if (count > 0) {
                movieDetail.setVod_remarks("共" + count + "集");
                list.add(movieDetail);
            }
        }

        return list;
    }

    private List<MovieDetail> generatePlaylist(String path, int total, List<MovieDetail> files) {
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(path);
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
        log.info("get play url - site {}:{}  path: {}", site.getId(), site.getName(), path);
        FsDetail fsDetail = aListService.getFile(site, path);
        String url = fixHttp(fsDetail.getRawUrl());
        if (url.contains("abnormal.png")) {
            throw new IllegalStateException("阿里云盘开放token过期");
        }
        if (url.contains("diskfull.png")) {
            throw new IllegalStateException("阿里云盘空间不足");
        }
        return url;
    }

    public MovieList getDetail(String tid) {
        int index = tid.indexOf('$');
        Site site = getSite(tid);
        String path = tid.substring(index + 1);
        if (path.contains(PLAYLIST) || path.contains(PLAYLIST_TXT)) {
            return getPlaylist(site, path);
        }

        FsDetail fsDetail = aListService.getFile(site, path);
        MovieList result = new MovieList();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(tid);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_tag(fsDetail.getType() == 1 ? FOLDER : FILE);
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_pic(getCover(fsDetail.getThumb(), fsDetail.getType()));
        movieDetail.setVod_play_from(fsDetail.getProvider());
        movieDetail.setVod_play_url(fsDetail.getName() + "$" + fixHttp(fsDetail.getRawUrl()));
        movieDetail.setVod_content(site.getName() + ":" + getParent(path));
        setDoubanInfo(movieDetail, site, path);
        result.getList().add(movieDetail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private String buildPlayUrl(Site site, String path) {
        ServletUriComponentsBuilder builder = ServletUriComponentsBuilder.fromCurrentRequestUri();
        builder.replacePath("/play");
        builder.queryParam("site", String.valueOf(site.getId()));
        builder.queryParam("path", encodeUrl(path));
        return builder.build().toUriString();
    }

    public MovieList getPlaylist(Site site, String path) {
        log.info("load playlist {}:{} {}", site.getId(), site.getName(), path);
        if (!path.contains(PLAYLIST)) {
            return readPlaylistFromFile(site, path);
        }
        String newPath = getParent(path);
        FsDetail fsDetail = aListService.getFile(site, newPath);

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(site.getId() + "$" + path);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_play_from(fsDetail.getProvider());
        movieDetail.setVod_content(site.getName() + ":" + newPath);
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);

        setDoubanInfo(fsDetail, movieDetail);

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
                if (appProperties.isSort()) {
                    files.sort(Comparator.comparing(e -> new FileNameInfo(e.getName())));
                }

                for (FsInfo fsInfo : files) {
                    list.add(getName(fsInfo.getName()) + "$" + buildPlayUrl(site, newPath + "/" + folder + "/" + fsInfo.getName()));
                }
            }
        } else {
            if (appProperties.isSort()) {
                files.sort(Comparator.comparing(e -> new FileNameInfo(e.getName())));
            }

            for (FsInfo fsInfo : files) {
                list.add(getName(fsInfo.getName()) + "$" + buildPlayUrl(site, newPath + "/" + fsInfo.getName()));
            }
        }

        movieDetail.setVod_play_url(String.join("#", list));

        MovieList result = new MovieList();
        result.getList().add(movieDetail);
        result.setLimit(result.getList().size());
        result.setTotal(result.getList().size());
        log.debug("playlist: {}", result);
        return result;
    }

    private void setDoubanInfo(MovieDetail movieDetail, Site site, String path) {
        try {
            String newPath = getParent(path);
            FsDetail fsDetail = aListService.getFile(site, newPath);
            setDoubanInfo(fsDetail, movieDetail);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void setDoubanInfo(FsDetail fsDetail, MovieDetail movieDetail) {
        DoubanData data = doubanService.getDataFromUrl(fsDetail.getReadme());
        if (data != null) {
            movieDetail.setVod_area(data.getCountry());
            movieDetail.setType_name(data.getGenre());
            if (StringUtils.isNotEmpty(data.getDescription())) {
                movieDetail.setVod_content(data.getDescription());
            }
        }
    }

    private MovieList readPlaylistFromFile(Site site, String path) {
        List<String> files = new ArrayList<>();
        int id = getPlaylistId(path);

        String newPath = getParent(path);
        String pname = "";
        FsDetail fsDetail = aListService.getFile(site, newPath);
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(site.getId() + "$" + path);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_play_from(fsDetail.getProvider());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);

        String content = aListService.readFileContent(site, path);
        if (content != null) {
            int count = 0;
            for (String line : content.split("[\r\n]")) {
                String text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.startsWith("#")) {
                    readMetadata(movieDetail, text);
                    continue;
                }
                if (text.contains(",#genre#")) {
                    if (!files.isEmpty()) {
                        count++;
                    }
                    if (count > id) {
                        break;
                    }
                    pname = text.split(",")[0];
                    files = new ArrayList<>();
                } else {
                    files.add(text);
                }
            }
        }

        List<String> list = new ArrayList<>();
        for (String line : files) {
            try {
                String name = line.split(",")[0];
                String file = line.split(",")[1];
                list.add(name + "$" + buildPlayUrl(site, newPath + "/" + file));
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        movieDetail.setVod_play_url(String.join("#", list));
        movieDetail.setVod_name(movieDetail.getVod_name() + " " + pname);

        MovieList result = new MovieList();
        result.getList().add(movieDetail);
        log.debug("playlist: {}", result);
        return result;
    }

    private void readMetadata(MovieDetail movieDetail, String text) {
        if (text.startsWith("#name")) {
            String name = text.substring("#name".length()).trim();
            if (!name.isEmpty()) {
                movieDetail.setVod_name(name);
            }
        } else if (text.startsWith("#type")) {
            movieDetail.setType_name(text.substring("#type".length()).trim());
        } else if (text.startsWith("#actor")) {
            movieDetail.setVod_actor(text.substring("#actor".length()).trim());
        } else if (text.startsWith("#director")) {
            movieDetail.setVod_director(text.substring("#director".length()).trim());
        } else if (text.startsWith("#content")) {
            movieDetail.setVod_content(text.substring("#content".length()).trim());
        } else if (text.startsWith("#lang")) {
            movieDetail.setVod_lang(text.substring("#lang".length()).trim());
        } else if (text.startsWith("#area")) {
            movieDetail.setVod_area(text.substring("#area".length()).trim());
        } else if (text.startsWith("#year")) {
            movieDetail.setVod_year(text.substring("#year".length()).trim());
        }
    }

    private int getPlaylistId(String path) {
        try {
            int index = path.lastIndexOf('/');
            if (index > 0) {
                String[] parts = path.substring(index + 1).split("#");
                if (parts.length >= 2) {
                    return Integer.parseInt(parts[parts.length - 1]);
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        return -1;
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

    private String getParent(String path) {
        int index = path.lastIndexOf('/');
        if (index > 0) {
            return path.substring(0, index);
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
