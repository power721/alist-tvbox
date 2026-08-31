package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 片单条目 ⇄ 追剧订阅的编排(TvBox msubadd-/msubdel- 与 Telegram Bot 片单追更共用)。
 * <p>
 * 从 PlayController 下沉(2026-08-30,Bot 片单入口复用):TMDB 条目绑定元数据(官方集数/播出日程驱动追更);
 * 豆瓣条目按标题严格匹配 suggest 条目(名称相等+数字 id)自动绑豆瓣元数据,匹配不上回落纯标题订阅
 * (名称桥接补元数据);create 对同剧幂等(裸名+季号语义匹配),已存在时不重复触发首轮巡检。
 */
@Slf4j
@Service
public class PianDanSubscriptionService {
    private final MediaSubscriptionService mediaSubscriptionService;
    private final MediaSubscriptionCheckService checkService;
    private final PianDanService pianDanService;

    public PianDanSubscriptionService(MediaSubscriptionService mediaSubscriptionService,
                                       MediaSubscriptionCheckService checkService,
                                       PianDanService pianDanService) {
        this.mediaSubscriptionService = mediaSubscriptionService;
        this.checkService = checkService;
        this.pianDanService = pianDanService;
    }

    /** 片单条目载荷 {vodId}|{剧名}|{季?} 的解析结果(年份取自 s: vodId 的 @{年} 后缀,供豆瓣绑 id 消歧)。 */
    public record PianDanEntry(String vodId, String name, Integer season, Integer year) {
    }

    /** 订阅/取消结果:msg 为 TvBox msg 通道文案(两口径共用),结构化字段供 Bot 渲染。 */
    public record Result(cn.har01d.alist_tvbox.dto.MediaSubscriptionDto dto, boolean existed, String name,
                         Integer season, String msg) {
    }

    /** 片单条目一键订阅。 */
    public Result subscribe(int uid, String payload) {
        PianDanEntry entry = pianDanEntry(payload);
        String name = entry.name();
        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        if (entry.vodId().startsWith("tmdb:")) {
            request.setMetaProvider("tmdb");
            request.setMetaId(entry.vodId().split(":")[2]);
        } else {
            bindDoubanMeta(request, entry.name(), entry.year());
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
        return new Result(dto, existed, name, entry.season(),
                (entry.season() != null ? "第" + entry.season() + "季" : "")
                        + (existed ? "《" + name + "》已在追剧中" : "已加入追剧《" + name + "》,稍后在我的追剧查看"));
    }

    /** 片单条目取消追剧:按标题语义匹配撤销;载荷带季号只撤该季(多季条目「取消·第N季」),不带则
     *  条目名解析季、仍未标季的同名各季一并撤,删除走服务级联。 */
    public Result unsubscribe(int uid, String payload) {
        PianDanEntry entry = pianDanEntry(payload);
        String name = entry.name();
        List<Integer> ids = mediaSubscriptionService.subscriptionIdsByTitle(uid, name, entry.season());
        if (ids.isEmpty()) {
            return new Result(null, false, name, entry.season(),
                    (entry.season() != null ? "第" + entry.season() + "季" : "") + "《" + name + "》未在追剧中");
        }
        for (Integer id : ids) {
            mediaSubscriptionService.delete(uid, id);
        }
        return new Result(null, true, name, entry.season(),
                "已取消追剧" + (entry.season() != null ? "第" + entry.season() + "季" : "") + "《" + name + "》"
                        + (ids.size() > 1 ? "(" + ids.size() + " 部)" : ""));
    }

    /** 豆瓣片单条目自动绑元数据:优先本地豆瓣库精确名匹配(零网络,年份可消歧同名翻拍);回落 suggest 名称精确匹配。
     *  两级的同名消歧口径一致:名称相等的结果**恰好一个**才绑 —— 同名翻拍多条且无年份可用时,suggest 也无法消歧
     *  (片单条目无年份),取首条必赌错一半,不如不绑回落纯标题订阅,交给巡检名称桥接(有季号/年份上下文)消歧。 */
    private void bindDoubanMeta(MediaSubscriptionRequest request, String name, Integer year) {
        Integer localId = mediaSubscriptionService.localDoubanId(name, year);
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
            Set<String> matchedIds = new HashSet<>();
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

    /** 片单条目载荷 → (vodId, 剧名, 季号?, 年份?):详情页装配 play id 时已内嵌剧名与季号({vodId}|{名}|{季}),
     *  订阅/取消零网络;多季剧的季号让订阅精确到季(create 对显式 >1 的季号不覆盖)。
     *  无内嵌名的 TMDB 条目现拉一次 zh-CN 标题兜底;豆瓣条目(s:{标题}[@{年}])标题在 id 里、年份取后缀。 */
    public PianDanEntry pianDanEntry(String payload) {
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
        Integer year = null;
        if (StringUtils.isBlank(name) && vodId.startsWith("tmdb:")) {
            name = tmdbMeta(vodId).getVod_name();
        }
        if (StringUtils.isBlank(name)) {
            if (vodId.startsWith("s:")) {
                PianDanService.NameYear entry = PianDanService.parseSubjectId(vodId);
                name = entry.name();
                year = entry.year();
            } else {
                throw new BadRequestException("无效的片单条目: " + vodId);
            }
        } else if (vodId.startsWith("s:")) {
            year = PianDanService.parseSubjectId(vodId).year();
        }
        return new PianDanEntry(vodId, name, season, year);
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
}
