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
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class TvBoxService {
    public static final String FOLDER_PIC = "http://img1.3png.com/281e284a670865a71d91515866552b5f172b.png";
    public static final String LIST_PIC = "http://img1.3png.com/3063ad894f04619af7270df68a124f129c8f.png";
    private final AListService aListService;
    private final AppProperties appProperties;
    private final LoadingCache<String, MovieList> cache;

    public TvBoxService(AListService aListService, AppProperties appProperties) {
        this.aListService = aListService;
        this.appProperties = appProperties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(appProperties.getCache().getSize())
                .expireAfterWrite(appProperties.getCache().getExpire())
                .build(this::getPlaylist);
    }

    public CategoryList getCategoryList() {
        CategoryList result = new CategoryList();
        for (FsInfo fsInfo : aListService.listFiles("/")) {
            Category category = new Category();
            category.setType_id("/" + fsInfo.getName());
            category.setType_name(fsInfo.getName());
            result.getList().add(category);
        }
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("list: {}", result);
        return result;
    }

    public MovieList getMovieList(String path) {
        MovieList result = new MovieList();
        int count = 0;
        for (FsInfo fsDetail : aListService.listFiles(path)) {
            String pic = fsDetail.getThumb();
            if(pic.isEmpty() && fsDetail.getType()==1) {
                pic = FOLDER_PIC;
            }
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(path + "/" + fsDetail.getName());
            movieDetail.setVod_name(fsDetail.getName());
            movieDetail.setVod_tag(fsDetail.getType() == 1 ? "folder" : "file");
            movieDetail.setVod_pic(pic);
            movieDetail.setVod_remarks(fileSize(fsDetail.getSize()) + (fsDetail.getType() == 1 ? "文件夹" : ""));
            result.getList().add(movieDetail);
            if (isMediaFormat(fsDetail.getName())) {
                count++;
            }
        }

        if (count > 1) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(path + "/playlist");
            movieDetail.setVod_name("播放列表");
            movieDetail.setVod_tag("file");
            movieDetail.setVod_pic(LIST_PIC);
            movieDetail.setVod_remarks("共" + count + "集");
            result.getList().add(0, movieDetail);
        }

        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    public MovieList getDetail(String path) {
        if (path.endsWith("/playlist")) {
            return cache.get(path);
        }
        FsDetail fsDetail = aListService.getFile(path);
        String pic = fsDetail.getThumb();
        if(pic.isEmpty() && fsDetail.getType()==1) {
            pic = FOLDER_PIC;
        }
        MovieList result = new MovieList();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(path);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_tag(fsDetail.getType() == 1 ? "folder" : "file");
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_pic(pic);
        movieDetail.setVod_play_from(fsDetail.getProvider());
        movieDetail.setVod_play_url(fsDetail.getName() + "$" + fsDetail.getRaw_url());
        result.getList().add(movieDetail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
    }

    public MovieList getPlaylist(String path) {
        log.info("load playlist: {}", path);
        String newPath = path.substring(0, path.length() - "/playlist".length());
        FsDetail fsDetail = aListService.getFile(newPath);
        MovieList result = new MovieList();

        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id(path);
        movieDetail.setVod_name(fsDetail.getName());
        movieDetail.setVod_time(fsDetail.getModified());
        movieDetail.setVod_tag("file");
        movieDetail.setVod_pic(LIST_PIC);

        List<String> list = new ArrayList<>();
        for (FsInfo fsInfo : aListService.listFiles(newPath)) {
            if (isMediaFormat(fsInfo.getName())) {
                FsDetail detail = aListService.getFile(newPath + "/" + fsInfo.getName());
                list.add(getName(detail.getName()) + "$" + detail.getRaw_url());
                if (movieDetail.getVod_pic() == null) {
                    movieDetail.setVod_pic(detail.getThumb());
                }
                if (movieDetail.getVod_play_from() == null) {
                    movieDetail.setVod_play_from(detail.getProvider());
                }
            }
        }
        if (appProperties.isPlaylistSort()) {
            Collections.sort(list);
        }
        movieDetail.setVod_play_url(String.join("#", list));

        result.getList().add(movieDetail);
        result.setTotal(result.getList().size());
        result.setLimit(result.getList().size());
        log.debug("detail: {}", result);
        return result;
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
}
