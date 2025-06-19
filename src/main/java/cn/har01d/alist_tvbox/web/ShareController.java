package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.OpenApiDto;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.SharesDto;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.Response;
import cn.har01d.alist_tvbox.service.ShareService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;


@RestController
public class ShareController {
    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    @GetMapping("/api/shares")
    public Page<Share> list(Pageable pageable, Integer type, String keyword) {
        return shareService.list(pageable, type, keyword);
    }

    @PostMapping("/api/shares")
    public Share create(@RequestBody Share share) {
        return shareService.create(share);
    }

    @PostMapping("/api/share-link")
    public String add(@RequestBody ShareLink share) {
        return shareService.add(share);
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

//    @GetMapping("/api/resources")
//    public Page<ShareInfo> listResources(Pageable pageable) {
//        return shareService.listResources(pageable);
//    }

    @GetMapping("/quark/cookie/{id}")
    public String getQuarkCookie(@PathVariable String id) {
        return shareService.getQuarkCookie(id);
    }

    @GetMapping("/uc/cookie/{id}")
    public String getUcCookie(@PathVariable String id) {
        return shareService.getUcCookie(id);
    }

    @GetMapping("/115/cookie/{id}")
    public String get115Cookie(@PathVariable String id) {
        return shareService.get115Cookie(id);
    }

    @GetMapping("/baidu/cookie/{id}")
    public String getBaiduCookie(@PathVariable String id) {
        return shareService.getBaiduCookie(id);
    }

    @GetMapping("/api/storages")
    public JsonNode listStorages(Pageable pageable) {
        return shareService.listStorages(pageable);
    }

    @PostMapping("/api/storages")
    public void validateStorages() {
        shareService.validateStorages();
    }

    @DeleteMapping("/api/storages")
    public int cleanStorages() {
        return shareService.cleanStorages();
    }

    @PostMapping("/api/storages/{id}")
    public Response reloadStorage(@PathVariable Integer id) {
        return shareService.reloadStorage(id);
    }

    @PostMapping("/api/import-shares")
    public int importShares(@RequestBody SharesDto sharesDto) {
        return shareService.importShares(sharesDto);
    }

    @PostMapping("/api/import-share-file")
    public int importShares(@RequestParam("file") MultipartFile file, int type) throws IOException {
        if (file.isEmpty()) {
            throw new BadRequestException();
        }

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        SharesDto sharesDto = new SharesDto();
        sharesDto.setType(type);
        sharesDto.setContent(content);
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
