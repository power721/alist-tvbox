package cn.har01d.alist_tvbox.dto;

import java.util.List;

import cn.har01d.alist_tvbox.model.FsDetail;
import lombok.Data;

@Data
public class Video {
    private int id;
    private String name;
    private String title;
    private String path;
    /** Stable short playback identity: siteId@playUrlId. */
    private String playId;
    private String time;
    private String url;
    private Long size;
    private Integer duration;
    private Integer rating;
    /** Sibling subtitle files (gui/web detail): raw URLs so desktop clients can mount them. */
    private List<Subtitle> subs;

    public Video() {
    }

    public Video(FsDetail fsDetail) {
        name = fsDetail.getName();
        title = fsDetail.getName();
        time = fsDetail.getModified();
        size = fsDetail.getSize();
    }
}
