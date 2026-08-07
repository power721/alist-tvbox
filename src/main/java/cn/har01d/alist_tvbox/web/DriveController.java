package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.DriveFilesResponse;
import cn.har01d.alist_tvbox.dto.DriveResolveRequest;
import cn.har01d.alist_tvbox.dto.DriveResolveResponse;
import cn.har01d.alist_tvbox.service.DriveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clean REST API for the atv-player desktop client to browse drive/pan shares one directory
 * at a time. Authenticated via the {@code Authorization} header (see WebSecurityConfiguration),
 * not the spider {@code /{token}} path mechanism.
 */
@RestController
@RequestMapping("/api/drive")
public class DriveController {
    private final DriveService driveService;

    public DriveController(DriveService driveService) {
        this.driveService = driveService;
    }

    @PostMapping("/resolve")
    public DriveResolveResponse resolve(@RequestBody DriveResolveRequest request) {
        return driveService.resolve(request.getSource(), request.getTitle());
    }

    @GetMapping("/{resourceId}/files")
    public DriveFilesResponse files(@PathVariable String resourceId,
                                    @RequestParam(name = "dir", required = false) String dir) {
        return new DriveFilesResponse(driveService.listFiles(resourceId, dir));
    }
}
