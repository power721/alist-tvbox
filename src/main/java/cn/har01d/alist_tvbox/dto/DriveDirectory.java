package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class DriveDirectory {
    /** Opaque stateless handle encoding the directory's absolute alist path. */
    private String id;
    private String name;
}
