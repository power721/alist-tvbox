package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChannelList {
    private boolean has_more;
    private String offset;
    private int total;
    private List<ChannelArchives> archive_channels = new ArrayList<>();
}
