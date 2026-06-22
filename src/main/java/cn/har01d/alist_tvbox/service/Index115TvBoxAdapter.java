package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.Index115File;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Version-1 site TVBox backend: browse/search/play against PowerList /index115. */
@Slf4j
public class Index115TvBoxAdapter {
    private static final int PER_PAGE = 20;

    private final Index115Client client;
    private final ProxyService proxyService;
    private final DriverAccountRepository driverAccountRepository;

    public Index115TvBoxAdapter(Index115Client client, ProxyService proxyService, DriverAccountRepository driverAccountRepository) {
        this.client = client;
        this.proxyService = proxyService;
        this.driverAccountRepository = driverAccountRepository;
    }

    public MovieList list(Site site, String path, int page, int size) {
        String[] ref = Index115PathCodec.decode(path);
        List<Index115File> items;
        if (ref == null) {
            items = client.browse(site, "", "", "");
        } else {
            String parentId = ref[2] == null ? "0" : ref[2];
            items = client.browse(site, ref[0], ref[1], parentId);
        }

        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();
        for (Index115File f : items) {
            String childPath = (ref == null)
                    ? Index115PathCodec.shareRoot(f.getShareCode(), f.getReceiveCode())
                    : Index115PathCodec.child(ref[0], ref[1], f.getFileId());
            int pid = proxyService.generatePath(site, childPath);
            MovieDetail md = new MovieDetail();
            md.setVod_id(site.getId() + "$" + pid + "$1");
            md.setVod_name(f.getName());
            md.setVod_tag(f.isDir() ? Constants.FOLDER : Constants.FILE);
            md.setVod_pic(Constants.ALIST_PIC);
            list.add(md);
        }
        result.setList(list);
        result.setPage(page);
        result.setTotal(list.size());
        result.setLimit(list.size());
        result.setPagecount(1);
        return result;
    }

    public Map<String, Object> play(Site site, String path) {
        String[] ref = Index115PathCodec.decode(path);
        if (ref == null || ref[2] == null) {
            throw new BadRequestException("无效的115索引播放路径: " + path);
        }
        String cookie = driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)
                .map(DriverAccount::getCookie).orElse("");
        String url = client.resolveLink(site, cookie, ref[0], ref[1], ref[2]);
        return Map.of(
                "parse", 0,
                "playUrl", "",
                "url", url,
                "type", DriverType.PAN115,
                "header", Map.of("User-Agent", Constants.USER_AGENT, "Referer", "https://115.com/"));
    }

    public List<MovieDetail> search(Site site, String keyword) {
        log.debug("search site: {}, keyword: {}", site, keyword);
        var data = client.search(site, keyword, 1, PER_PAGE);
        List<MovieDetail> list = new ArrayList<>();
        if (data == null || data.getItems() == null) {
            return list;
        }
        log.debug("search result: {}", data.getItems().size());
        for (Index115File f : data.getItems()) {
            if (f.isDir()) {
                continue;
            }
            int pid = proxyService.generatePath(site, Index115PathCodec.child(f.getShareCode(), f.getReceiveCode(), f.getFileId()));
            MovieDetail md = new MovieDetail();
            md.setVod_id(site.getId() + "$" + pid + "$1");
            md.setVod_name(f.getName());
            md.setVod_tag(Constants.FILE);
            md.setVod_pic(Constants.ALIST_PIC);
            list.add(md);
        }
        return list;
    }
}
