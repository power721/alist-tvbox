package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.model.FsDetail;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TvBoxService {
    public static final String FOLDER_PIC = "http://img1.3png.com/281e284a670865a71d91515866552b5f172b.png";
    public static final String LIST_PIC = "http://img1.3png.com/3063ad894f04619af7270df68a124f129c8f.png";
    public static final String PLAYLIST = "/#playlist"; // auto generated playlist
    public static final String PLAYLIST_TXT = "playlist.txt"; // user provided playlist
    public static final String FILE = "file";
    public static final String FOLDER = "folder";

    private final AListService aListService;
    private final AppProperties appProperties;
    private final LoadingCache<String, MovieList> cache;
    private final List<Category> categories = new ArrayList<>();

    public TvBoxService(AListService aListService, AppProperties appProperties) {
        this.aListService = aListService;
        this.appProperties = appProperties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(appProperties.getCache().getSize())
                .expireAfterWrite(appProperties.getCache().getExpire())
                .build(this::getPlaylist);
        appProperties.getSites()
                .forEach(site -> categories.add(new Category(site.getName() + "$/", site.getName())));
    }

    public CategoryList getCategoryList() {
        CategoryList result = new CategoryList(categories);
        log.debug("category: {}", result);
        return result;
    }

    public MovieList getMovieList(String tid) {
        int index = tid.indexOf('$');
        String site = tid.substring(0, index);
        String path = tid.substring(index + 1);
        List<MovieDetail> folders = new ArrayList<>();
        List<MovieDetail> files = new ArrayList<>();
        List<MovieDetail> playlists = new ArrayList<>();
        MovieList result = new MovieList();

        for (FsInfo fsInfo : aListService.listFiles(site, path)) {
            if (fsInfo.getType() != 1 && fsInfo.getName().equals(PLAYLIST_TXT)) {
                playlists = generatePlaylistFromFile(site, path + "/" + PLAYLIST_TXT);
                continue;
            }
            if (fsInfo.getType() != 1 && !isMediaFormat(fsInfo.getName())) {
                continue;
            }

            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(site + "$" + fixPath(path + "/" + fsInfo.getName()));
            movieDetail.setVod_name(fsInfo.getName());
            movieDetail.setVod_tag(fsInfo.getType() == 1 ? FOLDER : FILE);
            movieDetail.setVod_pic(getCover(fsInfo.getThumb(), fsInfo.getType()));
            movieDetail.setVod_remarks(fileSize(fsInfo.getSize()) + (fsInfo.getType() == 1 ? "文件夹" : ""));
            if (fsInfo.getType() == 1) {
                folders.add(movieDetail);
            } else {
                files.add(movieDetail);
            }
        }

        if (appProperties.isSort()) {
            folders.sort(Comparator.comparing(MovieDetail::getVod_name));
            files.sort(Comparator.comparing(MovieDetail::getVod_name));
        }

        result.getList().addAll(folders);

        if (files.size() > 1 && playlists.isEmpty()) {
            playlists = generatePlaylist(site + "$" + fixPath(path + PLAYLIST + "#"), files);
        }

        result.getList().addAll(playlists);
        result.getList().addAll(files);

        log.debug("list: {}", result);
        return result;
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

    private List<MovieDetail> generatePlaylist(String path, List<MovieDetail> files) {
        int size = appProperties.getPlaylistSize();
        List<MovieDetail> list = new ArrayList<>();
        int id = 0;
        for (; id < files.size() / size; ++id) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(path + id);
            movieDetail.setVod_name("播放列表" + (id + 1));
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_pic(LIST_PIC);
            movieDetail.setVod_remarks("共" + size + "集");
            list.add(movieDetail);
        }

        if (files.size() % size > 0) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(path + id);
            movieDetail.setVod_name("播放列表" + (id > 0 ? id + 1 : ""));
            movieDetail.setVod_tag(FILE);
            movieDetail.setVod_pic(LIST_PIC);
            movieDetail.setVod_remarks("共" + (files.size() % size) + "集");
            list.add(movieDetail);
        }

        return list;
    }

    public MovieList getDetail(String tid) {
        int index = tid.indexOf('$');
        String site = tid.substring(0, index);
        String path = tid.substring(index + 1);
        if (path.contains(PLAYLIST) || path.contains(PLAYLIST_TXT)) {
            return cache.get(tid);
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
        log.debug("detail: {}", result);
        return result;
    }

    public MovieList getPlaylist(String tid) {
        int index = tid.indexOf('$');
        String site = tid.substring(0, index);
        String path = tid.substring(index + 1);
        log.info("load playlist: {}", path);
        if (!path.contains(PLAYLIST)) {
            return readPlaylistFromFile(site, path);
        }
        String newPath = getParent(path);
        FsDetail fsDetail = aListService.getFile(site, newPath);

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(tid);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_tag(FILE);
        movieDetail.setVod_pic(LIST_PIC);

        List<String> list = new ArrayList<>();
        List<FsInfo> files = aListService.listFiles(site, newPath).stream()
                .filter(e -> isMediaFormat(e.getName()))
                .collect(Collectors.toList());

        if (appProperties.isSort()) {
            files.sort(Comparator.comparing(FsInfo::getName));
        }

        int id = getPlaylistId(path);
        for (FsInfo fsInfo : getMoviesInPlaylist(id, files)) {
            FsDetail detail = aListService.getFile(site, newPath + "/" + fsInfo.getName());
            list.add(getName(detail.getName()) + "$" + fixHttp(detail.getRaw_url()));
            if (movieDetail.getVod_play_from() == null) {
                movieDetail.setVod_play_from(detail.getProvider());
            }
        }

        movieDetail.setVod_play_url(String.join("#", list));

        MovieList result = new MovieList();
        result.getList().add(movieDetail);
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
                FsDetail detail = aListService.getFile(site, newPath + "/" + file);
                list.add(name + "$" + fixHttp(detail.getRaw_url()));
                if (movieDetail.getVod_play_from() == null) {
                    movieDetail.setVod_play_from(detail.getProvider());
                }
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
                String[] parts = path.substring(index + 1).split("#", 2);
                if (parts.length == 2) {
                    return Integer.parseInt(parts[1]);
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

}
