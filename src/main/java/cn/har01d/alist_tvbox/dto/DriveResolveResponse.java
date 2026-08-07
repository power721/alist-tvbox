package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;

/**
 * Resolution result for a drive share / discovery vodId.
 * Returns media metadata plus the top-level directory list (single level, non-recursive)
 * and the root directory's own media files (if any). Sub-directory files are loaded
 * lazily via {@code GET /api/drive/{resourceId}/files?dir=}.
 */
@Data
public class DriveResolveResponse {
    /** Opaque stateless handle encoding {@code siteId|rootPath}. */
    private String resourceId;
    private String vodName;
    /** Top-level sub-directories; one entry per folder shown in the player dropdown. */
    private List<DriveDirectory> directories;
    /** Media files directly under the root (single level). Empty when root only has folders. */
    private List<Video> files;
}
