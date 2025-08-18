package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FilesList {
    private List<String> files = new ArrayList<>();
    private List<String> folders = new ArrayList<>();
}
