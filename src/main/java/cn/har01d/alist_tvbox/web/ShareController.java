package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.OpenApiDto;
import cn.har01d.alist_tvbox.dto.SharesDto;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.model.Response;
import cn.har01d.alist_tvbox.service.ShareService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
public class ShareController {
    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    @GetMapping("/api/shares")
    public Page<Share> list(Pageable pageable) {
        return shareService.list(pageable);
    }

    @PostMapping("/api/shares")
    public Share create(@RequestBody Share share) {
        return shareService.create(share);
    }

    @PostMapping("/api/shares/{id}")
    public Share update(@PathVariable Integer id, @RequestBody Share share) {
        return shareService.update(id, share);
    }

    @DeleteMapping("/api/shares/{id}")
    public void delete(@PathVariable Integer id) {
        shareService.deleteShare(id);
    }

    @PostMapping("/api/delete-shares")
    public void deleteShares(@RequestBody List<Integer> ids) {
        shareService.deleteShares(ids);
    }

    @PostMapping("/api/tacit0924")
    public void getTacit0924() {
        shareService.getTacit0924();
    }

//    @GetMapping("/api/resources")
//    public Page<ShareInfo> listResources(Pageable pageable) {
//        return shareService.listResources(pageable);
//    }

    @GetMapping("/api/storages")
    public Object listStorages(Pageable pageable) {
        return shareService.listStorages(pageable);
    }

    @PostMapping("/api/storages/{id}")
    public Response reloadStorage(@PathVariable Integer id) {
        return shareService.reloadStorage(id);
    }

    @PostMapping("/api/import-shares")
    public int importShares(@RequestBody SharesDto sharesDto) {
        return shareService.importShares(sharesDto);
    }

    @GetMapping("/api/export-shares")
    public String exportShare(HttpServletResponse response, int type) {
        return shareService.exportShare(response, type);
    }

    @PostMapping("/api/open-token-url")
    public void updateOpenTokenUrl(@RequestBody OpenApiDto dto) {
        shareService.updateOpenTokenUrl(dto);
    }
}
