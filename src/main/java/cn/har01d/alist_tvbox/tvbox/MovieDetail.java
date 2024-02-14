package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

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
    private String vod_content;
    private String vod_director;
    private String vod_lang;
    private String vod_year;
    private long size;
    private CategoryList cate;

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
