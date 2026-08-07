package cn.har01d.alist_tvbox.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class DriveFilesResponse {
    /** Media files directly under the requested directory (single level, non-recursive). */
    private List<Video> files;
}
