package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.har01d.alist_tvbox.util.Constants.FOLDER;

@Slf4j
@Service
public class LiveService {
    private final List<LivePlatform> platforms = new ArrayList<>();
    private final HuyaService huyaService;

    public LiveService(HuyaService huyaService) {
        this.huyaService = huyaService;
        platforms.add(huyaService);
    }

    public MovieList home() throws IOException {
        return huyaService.home();
    }

    public CategoryList category() throws IOException {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();
        for (LivePlatform platform : platforms) {
            Category category = new Category();
            category.setType_id(platform.getType());
            category.setType_name(platform.getName());
            category.setType_flag(0);
            list.add(category);
        }
        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    public MovieList list(String id, String sort, Integer pg) throws IOException {
        MovieList result = new MovieList();
        if (id.contains("-")) {
            String[] parts = id.split("-");
            for (LivePlatform platform : platforms) {
                if (platform.getType().equals(parts[0])) {
                    return platform.list(id, sort, pg);
                }
            }
        } else {
            for (LivePlatform platform : platforms) {
                if (platform.getType().equals(id)) {
                    var categoryList = platform.category();
                    List<MovieDetail> list = new ArrayList<>();
                    for (var item : categoryList.getCategories()) {
                        MovieDetail detail = new MovieDetail();
                        detail.setVod_id(item.getType_id());
                        detail.setVod_name(item.getType_name());
                        detail.setVod_pic(item.getCover());
                        detail.setVod_tag(FOLDER);
                        list.add(detail);
                    }

                    result.setList(list);
                    result.setTotal(result.getList().size());
                    result.setLimit(result.getList().size());
                }
            }
        }
        return result;
    }

    public MovieList search(String wd) throws IOException {
        MovieList result = new MovieList();
        return result;
    }

    public MovieList detail(String tid) throws IOException {
        MovieList result = new MovieList();
        String[] parts = tid.split("\\$");
        for (LivePlatform platform : platforms) {
            if (platform.getType().equals(parts[0])) {
                result = platform.detail(tid);
                if (!result.getList().isEmpty()) {
                    result.getList().get(0).setVod_director(platform.getName());
                }
                return result;
            }
        }
        return result;
    }

    public Object play(String id) {
        Map<String, Object> result = new HashMap<>();
        //result.put("url", urls);
        //result.put("header", "{\"User-Agent\": \"" + Constants.USER_AGENT + "\"}");
        result.put("parse", 0);
        log.debug("{}", result);
        return result;
    }
}
