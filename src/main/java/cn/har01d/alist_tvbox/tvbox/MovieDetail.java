package cn.har01d.alist_tvbox.tvbox;

import cn.har01d.alist_tvbox.dto.PlayItem;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Data
public class MovieDetail {
    private String vod_id;
    private String vod_name;
    private String vod_pic;
    private String vod_tag;
    private String vod_time;
    private String vod_remarks = "";
    private String vod_play_from;
    private String vod_play_url;
    private String type_name;
    private String vod_actor;
    private String vod_area;
    private String vod_content = "";
    private String vod_director;
    private String vod_lang;
    private String vod_year;
    private String path;
    private Integer dbid;
    private Integer type;
    private Long size;
    private CategoryList cate;
    private List<PlayItem> items = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MovieDetail that = (MovieDetail) o;
        return Objects.equals(vod_id, that.vod_id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vod_id);
    }
}
