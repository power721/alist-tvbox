package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.model.*;
import cn.har01d.alist_tvbox.tvbox.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TvBoxService {
    public static final String FOLDER_PIC = "http://img1.3png.com/281e284a670865a71d91515866552b5f172b.png";
    public static final String LIST_PIC = "http://img1.3png.com/3063ad894f04619af7270df68a124f129c8f.png";
    public static final String PLAYLIST = "/~playlist"; // auto generated playlist
    public static final String PLAYLIST_TXT = "playlist.txt"; // user provided playlist
    public static final String FILE = "file";
    public static final String FOLDER = "folder";
    private final AListService aListService;
    private final AppProperties appProperties;
    private final FileNameComparator nameComparator = new FileNameComparator();
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

    public TvBoxService(AListService aListService, AppProperties appProperties) {
        this.aListService = aListService;
        this.appProperties = appProperties;
    }

    public CategoryList getCategoryList() {
        CategoryList result = new CategoryList();

        for (Site site : appProperties.getSites()) {
            Category category = new Category();
            category.setType_id(site.getName() + "$/");
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
        for (Site site : appProperties.getSites()) {
            if (site.isSearchable()) {
                log.info("search \"{}\" from site {}: {}", keyword, site.getName(), site.getUrl());
                futures.add(executorService.submit(() -> aListService.search(site.getName(), keyword)
                        .stream()
                        .map(e -> {
                            boolean isMediaFile = isMediaFile(e);
                            String path = fixPath("/" + e + (isMediaFile ? "" : PLAYLIST));
                            MovieDetail movieDetail = new MovieDetail();
                            movieDetail.setVod_id(site.getName() + "$" + path);
                            movieDetail.setVod_name(e);
                            movieDetail.setVod_tag(isMediaFile ? FILE : FOLDER);
                            return movieDetail;
                        })
                        .collect(Collectors.toList())));
            }
        }

        List<MovieDetail> list = new ArrayList<>();
        for (Future<List<MovieDetail>> future : futures) {
            try {
                list.addAll(future.get());
            } catch (InterruptedException | ExecutionException e) {
                log.warn("", e);
            }
        }

        log.info("search \"{}\" result: {}", keyword, list.size());
        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        return result;
    }

    private boolean isMediaFile(String path) {
        String name = path;
        int index = path.lastIndexOf('/');
        if (index > -1) {
            name = path.substring(index + 1);
        }
        return isMediaFormat(name);
    }

    public MovieList getMovieList(String tid, String sort, int page) {
        int index = tid.indexOf('$');
        String site = tid.substring(0, index);
        String path = tid.substring(index + 1);
        List<MovieDetail> folders = new ArrayList<>();
        List<MovieDetail> files = new ArrayList<>();
        List<MovieDetail> playlists = new ArrayList<>();
        MovieList result = new MovieList();

        int size = appProperties.getPlaylistSize();
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
            movieDetail.setVod_id(site + "$" + fixPath(path + "/" + fsInfo.getName()));
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
            playlists = generatePlaylist(site + "$" + fixPath(path + PLAYLIST + "#"), total - folders.size(), files);
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
                comparator = Comparator.comparing(e -> new FileNameInfo(e.getVod_name()), nameComparator);
                break;
            case "time,asc":
                comparator = Comparator.comparing(MovieDetail::getVod_time);
                break;
            case "size,asc":
                comparator = Comparator.comparing(MovieDetail::getSize);
                break;
            case "name,desc":
                comparator = Comparator.comparing(e -> new FileNameInfo(e.getVod_name()), nameComparator);
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

    private List<MovieDetail> generatePlaylistFromFile(String site, String path) {
        List<MovieDetail> list = new ArrayList<>();
        String content = aListService.readFileContent(site, path);
        if (content != null) {
            int count = 0;
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site + "$" + path + "#" + 0);
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
                    movieDetail.setVod_id(site + "$" + path + "#" + list.size());
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
        int size = appProperties.getPlaylistSize();
        List<MovieDetail> list = new ArrayList<>();
        int id = 0;
        for (; id < total / size; ++id) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(path + id);
            movieDetail.setVod_name("播放列表" + (total > size ? id + 1 : ""));
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_pic(LIST_PIC);
            list.add(movieDetail);
        }

        if (total % size > 0) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(path + id);
            movieDetail.setVod_name("播放列表" + (id > 0 ? id + 1 : ""));
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_pic(LIST_PIC);
            if (total < size) {
                movieDetail.setVod_remarks("共" + files.size() + "集");
            }
            list.add(movieDetail);
        }

        return list;
    }

    public String getPlayUrl(String site, String path) {
        FsDetail fsDetail = aListService.getFile(site, path);
        return fixHttp(fsDetail.getRaw_url());
    }

    public MovieList getDetail(String tid) {
        int index = tid.indexOf('$');
        String site = tid.substring(0, index);
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
        movieDetail.setVod_play_url(fsDetail.getName() + "$" + fixHttp(fsDetail.getRaw_url()));
        result.getList().add(movieDetail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    private String buildPlayUrl(String site, String path) {
        ServletUriComponentsBuilder builder = ServletUriComponentsBuilder.fromCurrentRequestUri();
        builder.replacePath("/play");
        builder.queryParam("site", encodeUrl(site));
        builder.queryParam("path", encodeUrl(path));
        return builder.build().toUriString();
    }

    public MovieList getPlaylist(String site, String path) {
        log.info("load playlist: {} {}", site, path);
        if (!path.contains(PLAYLIST)) {
            return readPlaylistFromFile(site, path);
        }
        String newPath = getParent(path);
        FsDetail fsDetail = aListService.getFile(site, newPath);

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(site + "$" + path);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_play_from(fsDetail.getProvider());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);

        int id = getPlaylistId(path);

        List<String> list = new ArrayList<>();

        int size = appProperties.getPlaylistSize();
        FsResponse fsResponse = aListService.listFiles(site, newPath, id + 1, size);
        List<FsInfo> files = fsResponse.getFiles().stream()
                .filter(e -> isMediaFormat(e.getName()))
                .collect(Collectors.toList());

        if (appProperties.isSort()) {
            files.sort(Comparator.comparing(e -> new FileNameInfo(e.getName()), nameComparator));
        }

        for (FsInfo fsInfo : files) {
            list.add(getName(fsInfo.getName()) + "$" + buildPlayUrl(site, newPath + "/" + fsInfo.getName()));
        }

        movieDetail.setVod_play_url(String.join("#", list));

        MovieList result = new MovieList();
        result.getList().add(movieDetail);
        result.setLimit(size);
        result.setTotal(result.getList().size());
        log.debug("playlist: {}", result);
        return result;
    }

    private MovieList readPlaylistFromFile(String site, String path) {
        List<String> files = new ArrayList<>();
        int id = getPlaylistId(path);

        String newPath = getParent(path);
        String pname = "";
        FsDetail fsDetail = aListService.getFile(site, newPath);
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(site + "$" + path);
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
                    if (files.size() > 0) {
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

    private String getParent(String path) {
        int index = path.lastIndexOf('/');
        if (index > 0) {
            return path.substring(0, index);
        }
        return path;
    }

    private List<FsInfo> getMoviesInPlaylist(int id, List<FsInfo> files) {
        if (id < 0) {
            return files;
        }

        int start = id * appProperties.getPlaylistSize();
        int end = start + appProperties.getPlaylistSize();
        if (end > files.size()) {
            end = files.size();
        }

        return files.subList(start, end);
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
