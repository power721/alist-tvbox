package cn.har01d.alist_tvbox.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StaticFileInfo {
    private String name;
    private String path;
    private long size;
    private long lastModified;
    private boolean directory;
    private String url;
}
