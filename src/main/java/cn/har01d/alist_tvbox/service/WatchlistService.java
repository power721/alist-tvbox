package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.WatchlistItem;
import cn.har01d.alist_tvbox.entity.WatchlistItemRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 稍后再看(用户级想看队列,docs/watchlist-design.md):纯静态标记,零后台巡检 ——
 * 想要「更新了提醒我」走转订阅({@link PianDanSubscriptionService#subscribe})。
 * <p>
 * 载荷 {vodId}|{剧名}|{季?} 与 msubadd- 同构,解析复用 {@link PianDanSubscriptionService#pianDanEntry}。
 * 列表条目 vod_id 为片单形态,详情/转订阅复用片单链路(MediaLibraryController.pianDanDetail)。
 */
@Slf4j
@Service
public class WatchlistService {
    private static final int PAGE_SIZE = 20;

    private final WatchlistItemRepository repository;
    private final PianDanSubscriptionService pianDanSubscriptionService;
    private final PianDanService pianDanService;
    private final MediaSubscriptionService mediaSubscriptionService;

    public WatchlistService(WatchlistItemRepository repository,
                            PianDanSubscriptionService pianDanSubscriptionService,
                            PianDanService pianDanService,
                            MediaSubscriptionService mediaSubscriptionService) {
        this.repository = repository;
        this.pianDanSubscriptionService = pianDanSubscriptionService;
        this.pianDanService = pianDanService;
        this.mediaSubscriptionService = mediaSubscriptionService;
    }

    /** 加入/移出结果:msg 为 TvBox msg 通道文案。 */
    public record Result(boolean existed, String name, String msg) {
    }

    /** 加入稍后再看(载荷同 msubadd):同 (uid, vodId) 幂等;快照富化尽力而为,失败不阻塞加入。 */
    public Result add(int uid, String payload) {
        PianDanSubscriptionService.PianDanEntry entry = pianDanSubscriptionService.pianDanEntry(payload);
        Optional<WatchlistItem> existing = repository.findByUidAndVodId(uid, entry.vodId());
        if (existing.isPresent()) {
            return new Result(true, entry.name(), "《" + entry.name() + "》已在稍后再看中");
        }
        WatchlistItem item = new WatchlistItem();
        item.setUid(uid);
        item.setVodId(StringUtils.abbreviate(entry.vodId(), 250));
        item.setTitle(StringUtils.abbreviate(entry.name(), 250));
        item.setYear(entry.year());
        item.setSeason(entry.season());
        enrichSnapshot(item, entry);
        item.setStatus(WatchlistItem.STATUS_WANT);
        item.setCreatedTime(System.currentTimeMillis());
        try {
            repository.save(item);
        } catch (DataIntegrityViolationException e) {
            // 并发双击撞唯一键 = 已加入;此路径无外层事务,不受 rollback-only 拖累
            return new Result(true, entry.name(), "《" + entry.name() + "》已在稍后再看中");
        }
        return new Result(false, entry.name(), "已加入稍后再看《" + entry.name() + "》");
    }

    /** 移出稍后再看(载荷同加入;按 vodId 精确删)。 */
    @Transactional
    public Result remove(int uid, String payload) {
        PianDanSubscriptionService.PianDanEntry entry = pianDanSubscriptionService.pianDanEntry(payload);
        long removed = repository.deleteByUidAndVodId(uid, entry.vodId());
        if (removed == 0) {
            return new Result(false, entry.name(), "《" + entry.name() + "》不在稍后再看中");
        }
        return new Result(true, entry.name(), "已移出稍后再看《" + entry.name() + "》");
    }

    public boolean exists(int uid, String vodId) {
        return StringUtils.isNotBlank(vodId) && repository.existsByUidAndVodId(uid, vodId);
    }

    public long count(int uid) {
        return repository.countByUid(uid);
    }

    public List<WatchlistItem> list(int uid) {
        return repository.findByUidOrderByCreatedTimeDesc(uid);
    }

    /** TVBox「稍后再看」分类列表(t=want,WANT 队列,createdTime 倒序)。条目 vod_id 为片单形态,
     *  点击进 pianDanDetail 详情(全套片单动作+追剧按钮);「已追」角标由控制器统一拼(与其他列表同口径)。 */
    public MovieList content(int uid, int pg) {
        List<WatchlistItem> items = repository.findByUidAndStatusOrderByCreatedTimeDesc(uid, WatchlistItem.STATUS_WANT);
        MovieList result = new MovieList();
        result.setPage(Math.max(pg, 1));
        int total = items.size();
        result.setTotal(total);
        result.setPagecount(Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE));
        int fromIndex = (result.getPage() - 1) * PAGE_SIZE;
        if (fromIndex < total) {
            for (WatchlistItem item : items.subList(fromIndex, Math.min(fromIndex + PAGE_SIZE, total))) {
                result.getList().add(toMovieDetail(item));
            }
        }
        result.setLimit(result.getList().size());
        return result;
    }

    private static MovieDetail toMovieDetail(WatchlistItem item) {
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(item.getVodId());
        detail.setVod_name(item.getSeason() != null
                ? item.getTitle() + " 第" + item.getSeason() + "季" : item.getTitle());
        detail.setVod_pic(item.getPic());
        if (item.getYear() != null) {
            detail.setVod_year(String.valueOf(item.getYear()));
        }
        String remarks = StringUtils.defaultString(item.getRemarks()).trim();
        detail.setVod_remarks(remarks.isEmpty() && item.getYear() == null ? ""
                : remarks.isEmpty() ? String.valueOf(item.getYear())
                : item.getYear() != null ? item.getYear() + " · " + remarks : remarks);
        return detail;
    }

    @Transactional
    public void delete(int uid, int id) {
        repository.findById(id)
                .filter(item -> item.getUid() == uid)
                .ifPresent(repository::delete);
    }

    @Transactional
    public void deleteAll(int uid, List<Integer> ids) {
        repository.findAllById(ids).stream()
                .filter(item -> item.getUid() == uid)
                .forEach(repository::delete);
    }

    /** 状态流转(WANT↔WATCHED↔FAVORITE);非本 uid 行静默忽略。 */
    @Transactional
    public boolean updateStatus(int uid, int id, String status) {
        if (!WatchlistItem.STATUS_WANT.equals(status) && !WatchlistItem.STATUS_WATCHED.equals(status)
                && !WatchlistItem.STATUS_FAVORITE.equals(status)) {
            return false;
        }
        return repository.findById(id)
                .filter(item -> item.getUid() == uid)
                .map(item -> {
                    item.setStatus(status);
                    item.setStatusTime(System.currentTimeMillis());
                    repository.save(item);
                    return true;
                })
                .orElse(false);
    }

    /** 封面/评分快照富化:与 pianDanDetail 同数据源(TMDB 短缓存/豆瓣本地库),尽力而为零阻塞 ——
     *  外部源挂了照常入列,列表以占位封面兜底。 */
    private void enrichSnapshot(WatchlistItem item, PianDanSubscriptionService.PianDanEntry entry) {
        try {
            MovieDetail meta = null;
            if (entry.vodId().startsWith(PianDanService.TMDB_PREFIX)) {
                String[] parts = entry.vodId().split(":");
                if (parts.length >= 3) {
                    meta = pianDanService.tmdbDetail(parts[1], Integer.parseInt(parts[2]));
                }
            } else if (entry.doubanId() != null) {
                meta = mediaSubscriptionService.localDoubanDetailById(entry.doubanId());
                if (meta == null) {
                    meta = pianDanService.doubanSubjectDetail(entry.doubanId());
                }
            } else {
                meta = mediaSubscriptionService.localDoubanDetail(entry.name(), entry.year());
            }
            if (meta != null) {
                item.setPic(StringUtils.abbreviate(StringUtils.defaultString(meta.getVod_pic()), 500));
                item.setRemarks(StringUtils.abbreviate(StringUtils.defaultString(meta.getVod_remarks()).trim(), 250));
                if (item.getYear() == null && StringUtils.isNumeric(meta.getVod_year())) {
                    item.setYear(Integer.valueOf(meta.getVod_year()));
                }
            }
        } catch (Exception e) {
            log.debug("watchlist snapshot enrich failed for {}: {}", entry.vodId(), e.getMessage());
        }
    }
}
