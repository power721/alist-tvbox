package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChannelArchives {
    private String name;
    private List<ChannelArchive> archives = new ArrayList<>();
}
