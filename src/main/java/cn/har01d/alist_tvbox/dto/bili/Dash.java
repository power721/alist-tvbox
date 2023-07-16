package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.List;

@Data
public class Dash {

    private String duration;
    private String minBufferTime;
    private List<Media> video;
    private List<Media> audio;
}
