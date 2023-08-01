package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.SharesDto;
import cn.har01d.alist_tvbox.dto.UrlDto;
import cn.har01d.alist_tvbox.entity.Share;
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

    @GetMapping("/shares")
    public Page<Share> list(Pageable pageable) {
        return shareService.list(pageable);
    }

    @PostMapping("/shares")
    public Share create(@RequestBody Share share) {
        return shareService.create(share);
    }

    @PostMapping("/shares/{id}")
    public Share update(@PathVariable Integer id, @RequestBody Share share) {
        return shareService.update(id, share);
    }

    @DeleteMapping("/shares/{id}")
    public void delete(@PathVariable Integer id) {
        shareService.deleteShare(id);
    }

    @PostMapping("/delete-shares")
    public void deleteShares(@RequestBody List<Integer> ids) {
        shareService.deleteShares(ids);
    }

//    @GetMapping("/resources")
//    public Page<ShareInfo> listResources(Pageable pageable) {
//        return shareService.listResources(pageable);
//    }

    @GetMapping("/storages")
    public Object listStorages(Pageable pageable) {
        return shareService.listStorages(pageable);
    }

    @PostMapping("/import-shares")
    public int importShares(@RequestBody SharesDto sharesDto) {
        return shareService.importShares(sharesDto);
    }

    @GetMapping("/export-shares")
    public String exportShare(HttpServletResponse response, int type) {
        return shareService.exportShare(response, type);
    }

    @PostMapping("/open-token-url")
    public void updateOpenTokenUrl(@RequestBody UrlDto dto) {
        shareService.updateOpenTokenUrl(dto.getUrl());
    }
}
