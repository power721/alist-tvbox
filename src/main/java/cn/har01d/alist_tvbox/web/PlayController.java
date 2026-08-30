package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.PlayUrl;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.AccountAccessGuard;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.PianDanSubscriptionService;
import cn.har01d.alist_tvbox.service.ProxyService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
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
    private final PianDanSubscriptionService pianDanSubscriptionService;
    private final AccountAccessGuard accountAccessGuard;

    public PlayController(TvBoxService tvBoxService,
                          BiliBiliService biliBiliService,
                          SubscriptionService subscriptionService,
                          ProxyService proxyService,
                          MediaSubscriptionService mediaSubscriptionService,
                          PianDanSubscriptionService pianDanSubscriptionService,
                          AccountAccessGuard accountAccessGuard) {
        this.tvBoxService = tvBoxService;
        this.biliBiliService = biliBiliService;
        this.subscriptionService = subscriptionService;
        this.proxyService = proxyService;
        this.mediaSubscriptionService = mediaSubscriptionService;
        this.pianDanSubscriptionService = pianDanSubscriptionService;
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
            return Map.of("msg", pianDanSubscriptionService.subscribe(uid,
                    id.substring(MediaSubscriptionService.SUBSCRIBE_PLAY_PREFIX.length())).msg());
        }

        if (StringUtils.isNotBlank(id) && id.startsWith("msubdel-")) {
            // 片单条目「取消追剧」(msubdel-{vodId}):撤销同名订阅(含多季),msg 通道回执
            int uid = mediaSubscriptionService.resolveUid(token);
            return Map.of("msg", pianDanSubscriptionService.unsubscribe(uid,
                    id.substring(MediaSubscriptionService.UNSUBSCRIBE_PLAY_PREFIX.length())).msg());
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

        if (StringUtils.isNotBlank(id) && id.startsWith("msubinspect-")) {
            // 订阅详情「操作」线路「立即巡检」:异步完整巡检(搜索/挂载/缺集补全,分钟级),msg 只回执已开始
            int uid = mediaSubscriptionService.resolveUid(token);
            mediaSubscriptionService.inspectAsync(uid, parseSubId(id, MediaSubscriptionService.INSPECT_PLAY_PREFIX));
            return Map.of("msg", "已开始巡检:搜索挂载完成后即可播放,稍后点「订阅信息」查看进度");
        }

        if (StringUtils.isNotBlank(id) && id.startsWith("msubunsub-")) {
            // 订阅详情「操作」线路「取消追剧」:删除订阅与全部挂载记录(播放器端已弹窗确认),msg 回执结果
            int uid = mediaSubscriptionService.resolveUid(token);
            return Map.of("msg", mediaSubscriptionService.unsubscribeText(uid,
                    parseSubId(id, MediaSubscriptionService.UNSUBSCRIBE_SUB_PLAY_PREFIX)));
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

    /** msubstat-/msubcheck-/msubinspect-/msubunsub- 载荷 {subId} 解析;格式非法 400。 */
    private static int parseSubId(String id, String prefix) {
        try {
            return Integer.parseInt(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new BadRequestException("播放参数格式不正确", e);
        }
    }

    private int parseInt(String value, String message) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException(message, e);
        }
    }
}
