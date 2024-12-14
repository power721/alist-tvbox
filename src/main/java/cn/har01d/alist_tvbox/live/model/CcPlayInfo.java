package cn.har01d.alist_tvbox.live.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CcPlayInfo {
    private List<String> cdn_list;
    private List<Integer> vbr_list;
    private Map<String, Integer> tcvbr_list;
    private Map<String, String> vbrname_mapping;
    private String videourl;
}
