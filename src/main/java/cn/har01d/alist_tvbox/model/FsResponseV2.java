package cn.har01d.alist_tvbox.model;

import lombok.Data;

import java.util.List;

@Data
public class FsResponseV2 {
    private String type;
    private List<FsInfoV2> files;
}
