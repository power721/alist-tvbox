package cn.har01d.alist_tvbox.dto;

import cn.har01d.alist_tvbox.model.FsDetail;
import lombok.Data;

@Data
public class Video {
    private int id;
    private String name;
    private String title;
    private String path;
    private String time;
    private String url;
    private Long size;
    private Integer rating;

    public Video() {
    }

    public Video(FsDetail fsDetail) {
        name = fsDetail.getName();
        title = fsDetail.getName();
        time = fsDetail.getModified();
        size = fsDetail.getSize();
    }
}
