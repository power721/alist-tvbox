package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.AccountAccessGuard;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.PianDanService;
import cn.har01d.alist_tvbox.service.ProxyService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping
public class PlayController {
    private final TvBoxService tvBoxService;
    private final BiliBiliService biliBiliService;
    private final SubscriptionService subscriptionService;
    private final ProxyService proxyService;
    private final MediaSubscriptionService mediaSubscriptionService;
    private final MediaSubscriptionCheckService checkService;
    private final PianDanService pianDanService;
    private final AccountAccessGuard accountAccessGuard;

    public PlayController(TvBoxService tvBoxService,
                          BiliBiliService biliBiliService,
                          SubscriptionService subscriptionService,
                          ProxyService proxyService,
                          MediaSubscriptionService mediaSubscriptionService,
                          MediaSubscriptionCheckService checkService,
                          PianDanService pianDanService,
                          AccountAccessGuard accountAccessGuard) {
        this.tvBoxService = tvBoxService;
        this.biliBiliService = biliBiliService;
        this.subscriptionService = subscriptionService;
        this.proxyService = proxyService;
        this.mediaSubscriptionService = mediaSubscriptionService;
        this.checkService = checkService;
        this.pianDanService = pianDanService;
        this.accountAccessGuard = accountAccessGuard;
    }

    @RequestMapping(value = "/p/{token}/{id}")
    public void proxy(@PathVariable String token, @PathVariable String id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        subscriptionService.checkToken(token);

        // 盘线路 pid 归属校验(§3.3):用户级 token 解析 uid;全局/共享 token(管理级)uid=0 放行
        cn.har01d.alist_tvbox.entity.User tokenUser = mediaSubscriptionService.resolveTokenUser(token);
        int uid = tokenUser == null || tokenUser.getId() == null ? 0 : tokenUser.getId();
        proxyService.proxy(id, uid, request, response);
    }

    @GetMapping("/play-urls")
    public Page <PlayUrl> list(Pageable pageable) {
        // 按用户吊销入口:管理级全量;USER 仅自己的归属行(解析失败 fail-closed,按无行可见处理)
        return proxyService.list(pageable, accountAccessGuard.effectiveUid());
    }

    @DeleteMapping("/play-urls")
    public void delete() {
        proxyService.deleteByOwner(accountAccessGuard.effectiveUid());
    }

    @GetMapping("/play")
    public Object play(Integer site, String path, String id, String bvid, String type, boolean dash, HttpServletRequest request) throws IOException {
        return play("", site, path, id, bvid, type, dash, request);
    }

    @GetMapping("/play/{token}")
    public Object play(@PathVariable String token, Integer site, String path, String id, String bvid, String type, boolean dash, HttpServletRequest request) throws IOException {
        subscriptionService.checkToken(token);

        String client = request.getHeader("X-CLIENT");
        // com.mygithub0.tvbox0.osdX 影视仓
        // com.fongmi.android.tv    影视
        // com.github.tvbox.osc     q版
        // com.github.tvbox.osc.bh  宝盒
        // com.github.tvbox.osc.tk  takagen99
        // com.qingsong.yingmi      影迷
        log.debug("get play url - site: {}  path: {}  id: {}  bvid: {}  type: {}  client: {}", site, path, id, bvid, type, client);

        if (StringUtils.isNotBlank(bvid)) {
            return biliBiliService.getPlayUrl(bvid, dash, client);
        }

        if (StringUtils.isNotBlank(id) && id.startsWith("msubadd-")) {
            // 片单条目「加入追剧」(msubadd-{vodId}):按片单条目建订阅,msg 通道回执(播放器把 msg 显示为提示)
            int uid = mediaSubscriptionService.resolveUid(token);
            return subscribePianDan(uid, id.substring(MediaSubscriptionService.SUBSCRIBE_PLAY_PREFIX.length()));
        }

        if (StringUtils.isNotBlank(id) && id.startsWith("msubdel-")) {
            // 片单条目「取消追剧」(msubdel-{vodId}):撤销同名订阅(含多季),msg 通道回执
            int uid = mediaSubscriptionService.resolveUid(token);
            return unsubscribePianDan(uid, id.substring(MediaSubscriptionService.UNSUBSCRIBE_PLAY_PREFIX.length()));
        }

        if (StringUtils.isNotBlank(id) && id.startsWith("msubinfo-")) {
            // 片单条目「媒体信息」:msg 通道返回条目元数据,无副作用(TMDB 现拉详情,豆瓣条目只有标题)
            int uid = mediaSubscriptionService.resolveUid(token);
            return infoPianDan(uid, id.substring(MediaSubscriptionService.INFO_PLAY_PREFIX.length()));
        }

        if (StringUtils.isNotBlank(id) && id.startsWith("msubstat-")) {
            // 订阅详情「操作」线路首条:纯占位零副作用(防内核切线路自动触发第 1 条),msg 返回状态摘要
            int uid = mediaSubscriptionService.resolveUid(token);
            return Map.of("msg", mediaSubscriptionService.subscriptionStatusText(uid,
                    parseSubId(id, MediaSubscriptionService.STAT_PLAY_PREFIX)));
        }

        if (StringUtils.isNotBlank(id) && id.startsWith("msubcheck-")) {
            // 订阅详情「操作」线路「检查更新」:同步轻量检查(刷新元数据→官方已播 vs 本地已有),msg 回执结论
            int uid = mediaSubscriptionService.resolveUid(token);
            return Map.of("msg", mediaSubscriptionService.checkUpdateText(uid,
                    parseSubId(id, MediaSubscriptionService.CHECK_PLAY_PREFIX)));
        }

        if (StringUtils.isNotBlank(id) && id.startsWith("msubep-")) {
            // 追剧逻辑集 msubep-{subId}-{集}:实时选源(转存>主源>补缺)并自动回退,用户无感知
            String[] parts = id.split("-");
            if (parts.length < 3) {
                throw new BadRequestException("播放参数格式不正确");
            }
            int uid = mediaSubscriptionService.resolveUid(token);
            try {
                return mediaSubscriptionService.playEpisode(uid, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), client, type);
            } catch (NumberFormatException e) {
                throw new BadRequestException("播放参数格式不正确", e);
            }
        }

        if (StringUtils.isNotBlank(id)) {
            String[] parts = id.split("@");
            if (parts.length > 1) {
                site = parseInt(parts[0], "站点参数格式不正确");
                path = parts[1];
                try {
                    path = proxyService.getPath(parseInt(path, "播放参数格式不正确"));
                } catch (NumberFormatException e) {
                    log.debug("", e);
                } catch (Exception e) {
                    log.warn("", e);
                }
            } else {
                path = id;
            }
        }

        if (StringUtils.isBlank(path)) {
            throw new BadRequestException("缺少播放参数");
        }

        boolean getSub = true;
        Map<String, Object> result;
        try {
            if (path.contains("/")) {
                if (path.startsWith("/")) {
                    result = tvBoxService.getPlayUrl(site, path, getSub, client, type);
                } else {
                    int index = path.indexOf('/');
                    id = path.substring(0, index);
                    path = path.substring(index);
                    result = tvBoxService.getPlayUrl(site, parseInt(id, "播放参数格式不正确"), path, getSub, client, type);
                }
            } else if (path.contains("-")) {
                String[] parts = path.split("-", 2);
                if (parts.length != 2) {
                    throw new BadRequestException("播放参数格式不正确");
                }
                id = parts[0];
                int index = parseInt(parts[1], "播放参数格式不正确");
                result = tvBoxService.getPlayUrl(site, parseInt(id, "播放参数格式不正确"), index, getSub, client, type);
            } else {
                result = tvBoxService.getPlayUrl(site, parseInt(path, "播放参数格式不正确"), getSub, client, type);
            }
        } catch (NumberFormatException e) {
            throw new BadRequestException("播放参数格式不正确", e);
        }

//        String url = (String) result.get("url");
//        if (url.contains("/redirect")) {
//            result.put("url", parseService.parse(url));
//        }

        return result;
    }

    /** 片单条目「媒体信息」:纯占位条目(防播放器内核进详情自动触发第一集误订阅),静态响应——
     *  元数据详情页已展示,这里零网络零 DB,什么都不做。 */
    private Map<String, Object> infoPianDan(int uid, String payload) {
        return Map.of("msg", "媒体信息见详情页");
    }

    /** msubstat-/msubcheck- 载荷 {subId} 解析;格式非法 400。 */
    private static int parseSubId(String id, String prefix) {
        try {
            return Integer.parseInt(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new BadRequestException("播放参数格式不正确", e);
        }
    }

    /** 片单条目载荷 → (vodId, 剧名, 季号?):详情页装配 play id 时已内嵌剧名与季号({vodId}|{名}|{季}),
     *  订阅/取消零网络;多季剧的季号让订阅精确到季(create 对显式 >1 的季号不覆盖)。
     *  无内嵌名的 TMDB 条目现拉一次 zh-CN 标题兜底,豆瓣条目(s:{标题})标题本就在 id 里。 */
    private record PianDanEntry(String vodId, String name, Integer season) {
    }

    private PianDanEntry pianDanEntry(String payload) {
        String[] parts = payload.split("\\|", -1);
        String vodId = parts[0];
        String name = parts.length > 1 ? parts[1].trim() : "";
        Integer season = null;
        if (parts.length > 2) {
            try {
                season = Integer.valueOf(parts[2].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (StringUtils.isBlank(name) && vodId.startsWith("tmdb:")) {
            name = tmdbMeta(vodId).getVod_name();
        }
        if (StringUtils.isBlank(name)) {
            if (vodId.startsWith("s:")) {
                name = vodId.substring(2);
            } else {
                throw new BadRequestException("无效的片单条目: " + vodId);
            }
        }
        return new PianDanEntry(vodId, name, season);
    }

    /** TMDB vodId(tmdb:tv:42 / tmdb:movie:42)→ 详情;格式非法 400。 */
    private MovieDetail tmdbMeta(String vodId) {
        String[] parts = vodId.split(":");
        if (parts.length < 3) {
            throw new BadRequestException("无效的片单条目: " + vodId);
        }
        int tmdbId;
        try {
            tmdbId = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new BadRequestException("无效的片单条目: " + vodId, e);
        }
        MovieDetail meta = pianDanService.tmdbDetail(parts[1], tmdbId);
        if (meta == null || StringUtils.isBlank(meta.getVod_name())) {
            throw new BadRequestException("片单条目信息获取失败: " + vodId);
        }
        return meta;
    }

    /** 片单条目一键订阅:TMDB 条目绑定元数据(官方集数/播出日程驱动追更);豆瓣条目按标题严格匹配
     *  suggest 条目(名称相等+数字 id)自动绑豆瓣元数据,匹配不上回落纯标题订阅(名称桥接补元数据);
     *  create 对同剧幂等(裸名+季号语义匹配),已存在时不重复触发首轮巡检。 */
    private Map<String, Object> subscribePianDan(int uid, String payload) {
        PianDanEntry entry = pianDanEntry(payload);
        String name = entry.name();
        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        if (entry.vodId().startsWith("tmdb:")) {
            request.setMetaProvider("tmdb");
            request.setMetaId(entry.vodId().split(":")[2]);
        } else {
            bindDoubanMeta(request, name);
        }
        if (entry.season() != null) {
            request.setSeason(entry.season()); // 多季剧条目显式落季,create 的 resolveSeason 对显式 >1 不覆盖
        }
        boolean existed = mediaSubscriptionService.isSubscribedTitle(uid,
                entry.season() != null ? name + " 第" + entry.season() + "季" : name);
        request.setName(name);
        request.setKeyword(name);
        var dto = mediaSubscriptionService.create(uid, request);
        if (!existed) {
            checkService.checkAsync(uid, dto.getId());
        }
        return Map.of("msg", (entry.season() != null ? "第" + entry.season() + "季" : "")
                + (existed ? "《" + name + "》已在追剧中" : "已加入追剧《" + name + "》,稍后在我的追剧查看"));
    }

    /** 豆瓣片单条目自动绑元数据:优先本地豆瓣库精确名匹配(零网络);回落 suggest 名称精确匹配。
     *  两级的同名消歧口径一致:名称相等的结果**恰好一个**才绑 —— 同名翻拍多条时 suggest 也无法消歧
     *  (片单条目无年份),取首条必赌错一半,不如不绑回落纯标题订阅,交给巡检名称桥接(有季号/年份上下文)消歧。 */
    private void bindDoubanMeta(MediaSubscriptionRequest request, String name) {
        Integer localId = mediaSubscriptionService.localDoubanId(name);
        if (localId != null) {
            request.setDoubanId(localId);
            request.setMetaProvider("douban");
            request.setMetaId(String.valueOf(localId));
            return;
        }
        try {
            Object items = mediaSubscriptionService.metaSearch("douban", name).get("items");
            if (!(items instanceof List<?> list)) {
                return;
            }
            java.util.Set<String> matchedIds = new java.util.HashSet<>();
            for (Object entry : list) {
                if (entry instanceof MetadataSearchItem item
                        && "douban".equals(item.getProvider()) && name.equals(item.getName())
                        && item.getId() != null && item.getId().matches("\\d+")) {
                    matchedIds.add(item.getId());
                }
            }
            if (matchedIds.size() == 1) {
                String id = matchedIds.iterator().next();
                request.setDoubanId(Integer.valueOf(id));
                request.setMetaProvider("douban");
                request.setMetaId(id);
            }
        } catch (Exception e) {
            log.debug("douban meta bind for pian-dan entry {} failed: {}", name, e.getMessage());
        }
    }

    /** 片单条目取消追剧:按标题语义匹配撤销;载荷带季号只撤该季(多季条目「取消·第N季」),不带则
     *  条目名解析季、仍未标季的同名各季一并撤,删除走服务级联。 */
    private Map<String, Object> unsubscribePianDan(int uid, String payload) {
        PianDanEntry entry = pianDanEntry(payload);
        String name = entry.name();
        List<Integer> ids = mediaSubscriptionService.subscriptionIdsByTitle(uid, name, entry.season());
        if (ids.isEmpty()) {
            return Map.of("msg", (entry.season() != null ? "第" + entry.season() + "季" : "")
                    + "《" + name + "》未在追剧中");
        }
        for (Integer id : ids) {
            mediaSubscriptionService.delete(uid, id);
        }
        return Map.of("msg", "已取消追剧" + (entry.season() != null ? "第" + entry.season() + "季" : "")
                + "《" + name + "》" + (ids.size() > 1 ? "(" + ids.size() + " 部)" : ""));
    }

    private int parseInt(String value, String message) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException(message, e);
        }
    }
}
