package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.auth.TokenService;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.playback.PlaybackDeleteInput;
import cn.har01d.alist_tvbox.dto.playback.PlaybackSyncInput;
import cn.har01d.alist_tvbox.dto.playback.PlaybackSyncPage;
import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.entity.HistoryRepository;
import cn.har01d.alist_tvbox.entity.PlaybackToken;
import cn.har01d.alist_tvbox.entity.PlaybackTokenRepository;
import cn.har01d.alist_tvbox.entity.PlaybackChangeSequence;
import cn.har01d.alist_tvbox.entity.PlaybackChangeSequenceRepository;
import cn.har01d.alist_tvbox.entity.PlaybackTombstone;
import cn.har01d.alist_tvbox.entity.PlaybackTombstoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多端播放记录同步的语义回归:LWW 删除、作用域删除、游标分页、分源过滤。
 * 这些都是"静默丢数据"型缺陷 —— 接口照常 200,记录却没了或再也发不出来。
 */
@ExtendWith(MockitoExtension.class)
class PlaybackSyncServiceTest {
    private static final int UID = 1;

    @Mock
    private HistoryRepository historyRepository;
    @Mock
    private PlaybackTokenRepository tokenRepository;
    @Mock
    private PlaybackTombstoneRepository tombstoneRepository;
    @Mock
    private PlaybackChangeSequenceRepository changeSequenceRepository;
    @Mock
    private TokenService tokenService;
    @Mock
    private ProxyService proxyService;

    private PlaybackSyncService service;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        PlaybackChangeSequence sequence = new PlaybackChangeSequence();
        sequence.setId(1);
        org.mockito.Mockito.lenient().when(changeSequenceRepository.findByIdForUpdate(1))
                .thenReturn(java.util.Optional.of(sequence));
        appProperties = new AppProperties();
        appProperties.setPlaybackSyncEnabled(true);
        service = new PlaybackSyncService(
                historyRepository, tokenRepository, tombstoneRepository, changeSequenceRepository, tokenService,
                appProperties, proxyService);
    }

    /** uid 级身份(syncScope=null):沿用旧行为,所有订阅互通。 */
    private PlaybackSyncService.TokenIdentity id(int uid) {
        return new PlaybackSyncService.TokenIdentity(uid, null);
    }

    @Test
    void disabledSyncPersistsPushButPullReturnsEmpty() {
        appProperties.setPlaybackSyncEnabled(false);

        // PUSH 始终落库:网页端自身的续看进度不应受跨端同步开关影响
        service.apply(id(UID), Map.of("sourceKey", "abc", "vodId", "v1"), null, null);
        verify(historyRepository).save(any());

        // 跨端分发(PULL)仍受开关门控:关闭时返回空页,客户端继续轮询不刷错误日志
        assertThat(service.pull(UID, 0, 100, null).getItems()).isEmpty();
    }

    // ── 删除:LWW ───────────────────────────────────────────────────────────

    @Test
    void staleDeleteKeepsNewerHistory() {
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "site", "abc", "v1"))
                .thenReturn(List.of(history("abc", "v1", 200)));

        service.delete(UID, null,deleteInput(Map.of("scope", "item", "sourceKey", "abc", "vodId", "v1", "deletedAt", 100)));

        // 迟到的删除(100)不得抹掉更新的本地记录(200):墓碑挡不住已发生的删除
        verify(historyRepository, never()).deleteAll(any());
        assertThat(savedTombstone().getDeletedAt()).isEqualTo(100);
    }

    @Test
    void deleteRemovesHistoryNotNewerThanEvent() {
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "site", "abc", "v1"))
                .thenReturn(List.of(history("abc", "v1", 100)));

        service.delete(UID, null,deleteInput(Map.of("scope", "item", "sourceKey", "abc", "vodId", "v1", "deletedAt", 200)));

        assertThat(deletedRows()).extracting(History::getVodId).containsExactly("v1");
    }

    // ── 删除:回声限频 ───────────────────────────────────────────────────────

    @Test
    void repeatedItemDeleteWithinThrottleWindowIsDropped() {
        // 旧版客户端每分钟重发同一条删除:墓碑 1 分钟前刚生效,本次必须按回声丢弃
        PlaybackTombstone recent = tombstone("site", "v1", System.currentTimeMillis() - 60_000);
        when(tombstoneRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "site", "abc", "v1"))
                .thenReturn(List.of(recent));
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "site", "abc", "v1"))
                .thenReturn(List.of(history("abc", "v1", 100)));

        service.delete(UID, null, deleteInput(Map.of(
                "scope", "item", "sourceKey", "abc", "vodId", "v1",
                "deletedAt", System.currentTimeMillis())));

        verify(tombstoneRepository, never()).save(any());
        verify(historyRepository, never()).deleteAll(any());
    }

    @Test
    void itemDeleteAfterThrottleWindowAppliesAgain() {
        // 窗口(默认 10 分钟)过后,重复删除可再次生效;配置 0 关闭限频
        PlaybackTombstone stale = tombstone("site", "v1", System.currentTimeMillis() - 700_000);
        when(tombstoneRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "site", "abc", "v1"))
                .thenReturn(List.of(stale));
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "site", "abc", "v1"))
                .thenReturn(List.of(history("abc", "v1", 100)));

        service.delete(UID, null, deleteInput(Map.of(
                "scope", "item", "sourceKey", "abc", "vodId", "v1",
                "deletedAt", System.currentTimeMillis())));

        assertThat(deletedRows()).extracting(History::getVodId).containsExactly("v1");
    }

    @Test
    void zeroThrottleConfigDisablesEchoSuppression() {
        appProperties.setPlaybackDeleteThrottleMs(0);
        PlaybackTombstone recent = tombstone("site", "v1", System.currentTimeMillis());
        when(tombstoneRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "site", "abc", "v1"))
                .thenReturn(List.of(recent));
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "site", "abc", "v1"))
                .thenReturn(List.of(history("abc", "v1", 100)));

        service.delete(UID, null, deleteInput(Map.of(
                "scope", "item", "sourceKey", "abc", "vodId", "v1",
                "deletedAt", System.currentTimeMillis())));

        assertThat(deletedRows()).extracting(History::getVodId).containsExactly("v1");
    }

    @Test
    void deleteTombstonesPurgesByIdentityAcrossSyncScopes() {
        PlaybackTombstone scoped = tombstone("spider_plugin", "173", 100);
        scoped.setSyncScope("sub-1");
        PlaybackTombstone global = tombstone("spider_plugin", "173", 200);
        when(tombstoneRepository.findItemAnyScope(UID, "spider_plugin", "key", "173"))
                .thenReturn(List.of(scoped, global));

        int removed = service.deleteTombstones(UID, List.of(deleteInput(Map.of(
                "scope", "item", "sourceKind", "spider_plugin", "sourceKey", "key", "vodId", "173"))));

        assertThat(removed).isEqualTo(2);
        verify(tombstoneRepository).deleteAll(List.of(scoped, global));
    }

    // ── 删除:作用域 ────────────────────────────────────────────────────────

    @Test
    void siteScopeDeleteWithoutVodIdStillApplies() {
        when(historyRepository.findByUidAndSourceKindAndSourceKey(UID, "site", "abc"))
                .thenReturn(List.of(history("abc", "v1", 500), history("abc", "v2", 1500)));

        // site 作用域的删除通常不带 vodId,不能因缺少条目身份就被丢弃
        service.apply(id(UID),Map.of("event", "playback.deleted", "scope", "site", "sourceKey", "abc",
                "deletedAt", 1000), null, null);

        PlaybackTombstone tomb = savedTombstone();
        assertThat(tomb.getScope()).isEqualTo("site");
        assertThat(tomb.getSourceKey()).isEqualTo("abc");
        assertThat(tomb.getVodId()).isNull();
        assertThat(tomb.getDeletedAt()).isEqualTo(1000);
        assertThat(deletedRows()).extracting(History::getVodId).containsExactly("v1");
    }

    @Test
    void allScopeDeleteWithoutIdentityStillApplies() {
        when(historyRepository.findAllByUidAndSourceKindIsNotNull(eq(UID), any()))
                .thenReturn(List.of(history("abc", "v1", 500), history("pan", "v2", 1500)));

        service.apply(id(UID),Map.of("event", "playback.deleted", "scope", "all", "deletedAt", 1000), null, null);

        PlaybackTombstone tomb = savedTombstone();
        assertThat(tomb.getScope()).isEqualTo("all");
        assertThat(tomb.getVodId()).isNull();
        assertThat(deletedRows()).extracting(History::getVodId).containsExactly("v1");
    }

    @Test
    void managementDeleteCreatesItemTombstoneForOtherClients() {
        History row = history("plugin-id", "vod-1", 500);
        row.setSourceKind("spider_plugin");
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "spider_plugin", "plugin-id", "vod-1")).thenReturn(List.of(row));

        PlaybackDeleteInput input = new PlaybackDeleteInput();
        input.setSourceKind("spider_plugin");
        input.setSourceKey("plugin-id");
        input.setVodId("vod-1");
        service.deleteRecords(UID, List.of(input));

        PlaybackTombstone tombstone = savedTombstone();
        assertThat(tombstone.getScope()).isEqualTo("item");
        assertThat(tombstone.getSourceKind()).isEqualTo("spider_plugin");
        assertThat(tombstone.getSourceKey()).isEqualTo("plugin-id");
        assertThat(tombstone.getVodId()).isEqualTo("vod-1");
        // 管理端删除走 uid 级:墓碑必须落在 uid 全局分区,scoped 客户端才拉得到
        assertThat(tombstone.getSyncScope()).isNull();
        assertThat(deletedRows()).containsExactly(row);
    }

    @Test
    void staleUpsertDoesNotResurrectAfterSiteDelete() {
        PlaybackTombstone site = new PlaybackTombstone();
        site.setScope("site");
        site.setDeletedAt(1000);
        when(tombstoneRepository.findFirstByUidAndScopeAndSourceKindAndSourceKeyOrderByDeletedAtDesc(
                UID, "site", "pan", "abc")).thenReturn(site);

        service.apply(id(UID),Map.of("sourceKind", "pan", "sourceKey", "abc", "vodId", "v1",
                "positionMs", 10, "updatedAt", 500), null, null);

        verify(historyRepository, never()).save(any());
    }

    @Test
    void atvSourceAliasUsesSameIdentityAsTvBoxSite() {
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "site", "csp_TgDouBan", "v1")).thenReturn(List.of());

        service.apply(id(UID),Map.of("sourceKind", "telegram", "vodId", "v1", "updatedAt", 500), null, null);

        History saved = savedHistory();
        assertThat(saved.getSourceKind()).isEqualTo("site");
        assertThat(saved.getSourceKey()).isEqualTo("csp_TgDouBan");
        assertThat(saved.getKey()).isEqualTo("csp_TgDouBan@@@v1@@@0");
    }

    @Test
    void telegramAliasKeepsConcreteTvBoxSiteKey() {
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "site", "csp_TgWeb", "v1")).thenReturn(List.of());

        service.apply(id(UID),Map.of("sourceKind", "telegram", "sourceKey", "csp_TgWeb",
                "vodId", "v1", "updatedAt", 500), null, null);

        assertThat(savedHistory().getSourceKey()).isEqualTo("csp_TgWeb");
    }

    @Test
    void sameVersionCanRepairMissingPluginSourceName() {
        String stableId = "ff03a81ea2c940d4838e71fb21cf6651157d";
        History existing = history(stableId, "星芽@51", 500);
        existing.setSourceKind("spider_plugin");
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "spider_plugin", stableId, "星芽@51")).thenReturn(List.of(existing));

        service.apply(id(UID),Map.of("sourceKind", "spider_plugin", "sourceKey", stableId,
                "sourceName", "短剧优选", "vodId", "星芽@51", "updatedAt", 500), null, null);

        History saved = savedHistory();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getSourceName()).isEqualTo("短剧优选");
        assertThat(saved.getUpdatedAt()).isEqualTo(500);
    }

    @Test
    void xiaoYaBrowseAliasKeepsConcreteTvBoxSiteKey() {
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "site", "csp_XiaoYa", "v1")).thenReturn(List.of());

        service.apply(id(UID),Map.of("sourceKind", "browse", "sourceKey", "csp_XiaoYa",
                "vodId", "v1", "updatedAt", 500), null, null);

        assertThat(savedHistory().getSourceKey()).isEqualTo("csp_XiaoYa");
    }

    // ── 拉取:游标 ──────────────────────────────────────────────────────────

    @Test
    void cursorStaysBehindUndeliveredUpdates() {
        List<History> rows = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            rows.add(history("abc", "v" + i, i));
        }
        when(historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(
                eq(UID), eq(0L), any())).thenReturn(rows);
        when(tombstoneRepository.findByUidAndChangeSeqGreaterThan(eq(UID), eq(0L), any()))
                .thenReturn(List.of(tombstone("abc", "gone", 102)));

        PlaybackSyncPage first = service.pull(UID, 0, 0, null);

        // 101 条更新 + 1 条更新的墓碑:游标不能越过第 101 条,否则它永远发不出去
        assertThat(first.getItems()).hasSize(100);
        assertThat(first.getDeleted()).isEmpty();
        assertThat(first.getNextSince()).isEqualTo("100");

        when(historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(
                eq(UID), eq(100L), any()))
                .thenReturn(List.of(rows.get(100)));
        when(tombstoneRepository.findByUidAndChangeSeqGreaterThan(eq(UID), eq(100L), any()))
                .thenReturn(List.of(tombstone("abc", "gone", 102)));

        PlaybackSyncPage second = service.pull(UID, 100, 0, null);

        assertThat(second.getItems()).extracting(PlaybackSyncInput::getVodId).containsExactly("v101");
        assertThat(second.getDeleted()).hasSize(1);
        assertThat(second.getNextSince()).isEqualTo("102");
    }

    @Test
    void initialLatestPullReturnsNewestRecordsAndAdvancesToCurrentWatermark() {
        List<History> rows = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            rows.add(history("abc", "v" + i, i));
        }
        when(historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(
                eq(UID), eq(0L), any())).thenReturn(rows);

        PlaybackSyncPage page = service.pull(UID, 0, 100, null, true);

        assertThat(page.getItems()).hasSize(100);
        assertThat(page.getItems()).extracting(PlaybackSyncInput::getVodId)
                .contains("v101").doesNotContain("v1");
        assertThat(page.getNextSince()).isEqualTo("101");
    }

    @Test
    void syncHistoryWindowDiscardsRecordsOlderThanLatest100() {
        List<History> rows = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            rows.add(history("abc", "v" + i, i));
        }
        when(historyRepository.findAllByUidAndSourceKindIsNotNull(eq(UID), any())).thenReturn(rows);

        service.applyAll(id(UID),List.of());

        verify(historyRepository).deleteAll(argThat(removed -> {
            var iterator = removed.iterator();
            return iterator.hasNext()
                    && "v1".equals(iterator.next().getVodId())
                    && !iterator.hasNext();
        }));
    }

    @Test
    void duplicateIdentityDoesNotEvictAUniqueRecordFromLatest100() {
        List<History> rows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            rows.add(history("abc", "v" + i, i));
        }
        History olderDuplicate = rows.get(99);
        History duplicate = history("abc", "v100", 101);
        rows.add(duplicate);
        when(historyRepository.findAllByUidAndSourceKindIsNotNull(eq(UID), any())).thenReturn(rows);

        service.applyAll(id(UID),List.of());

        assertThat(deletedRows()).containsExactly(olderDuplicate);
    }

    @Test
    void initialLatestPullDoesNotReturnDuplicateIdentities() {
        History older = history("abc", "v1", 100);
        History newer = history("abc", "v1", 200);
        when(historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(
                eq(UID), eq(0L), any())).thenReturn(List.of(older, newer));

        PlaybackSyncPage page = service.pull(UID, 0, 100, null, true);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().getFirst().getUpdatedAt()).isEqualTo(200);
    }

    @Test
    void diagnosticListReturnsAllStoredRowsWithoutLatest100Limit() {
        List<History> rows = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            rows.add(history("abc", "v" + i, i));
        }
        when(historyRepository.findAllByUidAndSourceKindIsNotNull(eq(UID), any())).thenReturn(rows);

        List<PlaybackSyncInput> records = service.listAll(UID);

        assertThat(records).hasSize(101);
        assertThat(records).extracting(PlaybackSyncInput::getVodId).contains("v1", "v101");
    }

    @Test
    void diagnosticListSupportsPageAndPageSize() {
        History row = history("abc", "v2", 200);
        PageRequest pageable = PageRequest.of(1, 25,
                Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        when(historyRepository.findPageByUidAndSourceKindIsNotNull(UID, pageable))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 26));

        Page<PlaybackSyncInput> records = service.list(UID, 1, 25);

        assertThat(records.getNumber()).isEqualTo(1);
        assertThat(records.getSize()).isEqualTo(25);
        assertThat(records.getTotalElements()).isEqualTo(26);
        assertThat(records.getContent()).extracting(PlaybackSyncInput::getVodId).containsExactly("v2");
    }

    @Test
    void webPlayableListFiltersByWebPlayableSourceKeys() {
        History row = history("csp_TgDouBan", "v9", 300);
        PageRequest pageable = PageRequest.of(0, 100,
                Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        when(historyRepository.findPageByUidAndSourceKeyIn(eq(UID), keys.capture(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1));

        Page<PlaybackSyncInput> records = service.list(UID, 0, 100, true);

        assertThat(records.getContent()).extracting(PlaybackSyncInput::getVodId).containsExactly("v9");
        assertThat(keys.getValue())
                .contains("TvBox", "csp_TgChannel", "csp_AList", "csp_XiaoYa", "csp_TgDouBan")
                .doesNotContain("csp_BiliBili", "csp_Emby");
    }

    @Test
    void duplicateSyncIdentityIsCollapsedBeforeUpsert() {
        History older = history("abc", "v1", 100);
        older.setId(10);
        History newer = history("abc", "v1", 200);
        newer.setId(11);
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "site", "abc", "v1")).thenReturn(List.of(older, newer));

        service.apply(id(UID),Map.of("sourceKey", "abc", "vodId", "v1", "updatedAt", 300), null, null);

        assertThat(savedHistory().getId()).isEqualTo(11);
        assertThat(deletedRows()).containsExactly(older);
    }

    @Test
    void tiedTimestampsAreDeliveredAsOneGroup() {
        // 同一时间戳跨越 limit 边界时整组下发:GreaterThan 游标无法在组内断点续传
        when(historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(
                eq(UID), eq(0L), any()))
                .thenReturn(List.of(history("abc", "v1", 10), history("abc", "v2", 20), history("abc", "v3", 20)));

        PlaybackSyncPage page = service.pull(UID, 0, 2, null);

        assertThat(page.getItems()).extracting(PlaybackSyncInput::getVodId).containsExactly("v1", "v2", "v3");
        assertThat(page.getNextSince()).isEqualTo("20");
    }

    @Test
    void lateClientTimestampIsDeliveredByServerSequence() {
        History late = history("abc", "late", 150);
        late.setChangeSeq(201L);
        when(historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(
                eq(UID), eq(200L), any()))
                .thenReturn(List.of(late));

        PlaybackSyncPage page = service.pull(UID, 200, 100, null);

        assertThat(page.getItems()).extracting(PlaybackSyncInput::getVodId).containsExactly("late");
        assertThat(page.getItems().getFirst().getUpdatedAt()).isEqualTo(150);
        assertThat(page.getNextSince()).isEqualTo("201");
    }

    // ── 拉取:分源过滤 ──────────────────────────────────────────────────────

    @Test
    void sourceScopedPullFiltersTombstonesByKind() {
        when(tombstoneRepository.findByUidAndSourceKindAndChangeSeqGreaterThan(eq(UID), eq("pan"), eq(0L), any()))
                .thenReturn(List.of(tombstone("pan", "v1", 10)));
        // all 作用域的墓碑不带 sourceKind,对所有来源生效,必须照发
        when(tombstoneRepository.findByUidAndSourceKindIsNullAndChangeSeqGreaterThan(eq(UID), eq(0L), any()))
                .thenReturn(List.of(tombstone(null, null, 20)));

        PlaybackSyncPage page = service.pull(UID, 0, 0, "pan");

        assertThat(page.getDeleted()).extracting(PlaybackDeleteInput::getSourceKind)
                .containsExactly("pan", null);
        // 未过滤的全量墓碑查询会把别的来源的删除下发给该客户端
        verify(tombstoneRepository, never()).findByUidAndChangeSeqGreaterThan(anyInt(), anyLong(), any());
    }

    @Test
    void pullAcceptsMultipleSourceKinds() {
        when(historyRepository.findByUidAndSourceKindInAndChangeSeqGreaterThan(
                eq(UID), eq(List.of("site", "spider_plugin")), eq(0L), any()))
                .thenReturn(List.of());
        when(tombstoneRepository.findByUidAndSourceKindInAndChangeSeqGreaterThan(
                eq(UID), eq(List.of("site", "spider_plugin")), eq(0L), any()))
                .thenReturn(List.of());

        service.pull(UID, 0, 100, "site, spider_plugin");

        verify(historyRepository).findByUidAndSourceKindInAndChangeSeqGreaterThan(
                eq(UID), eq(List.of("site", "spider_plugin")), eq(0L), any());
        verify(tombstoneRepository).findByUidAndSourceKindInAndChangeSeqGreaterThan(
                eq(UID), eq(List.of("site", "spider_plugin")), eq(0L), any());
    }

    @Test
    void siteKeyFilterSkipsUnownedSitesAndAdvancesCursor() {
        History owned = history("csp_AList", "owned", 10);
        History unrelated = history("csp_Other", "other", 20);
        when(historyRepository.findByUidAndSourceKindAndChangeSeqGreaterThan(
                eq(UID), eq("site"), eq(0L), any()))
                .thenReturn(List.of(owned, unrelated));
        when(tombstoneRepository.findByUidAndSourceKindAndChangeSeqGreaterThan(
                eq(UID), eq("site"), eq(0L), any())).thenReturn(List.of());

        PlaybackSyncPage page = service.pull(
                UID, 0, 100, "site", "csp_AList,csp_TgWeb", false);

        assertThat(page.getItems()).extracting(PlaybackSyncInput::getVodId)
                .containsExactly("owned");
        assertThat(page.getNextSince()).isEqualTo("20");
    }

    @Test
    void sourceKeyFilterAlsoLimitsSpiderPlugins() {
        History owned = history("plugin-owned", "owned", 10);
        owned.setSourceKind("spider_plugin");
        History unrelated = history("plugin-other", "other", 20);
        unrelated.setSourceKind("spider_plugin");
        when(historyRepository.findByUidAndSourceKindAndChangeSeqGreaterThan(
                eq(UID), eq("spider_plugin"), eq(0L), any()))
                .thenReturn(List.of(owned, unrelated));
        when(tombstoneRepository.findByUidAndSourceKindAndChangeSeqGreaterThan(
                eq(UID), eq("spider_plugin"), eq(0L), any())).thenReturn(List.of());

        PlaybackSyncPage page = service.pull(
                UID, 0, 100, "spider_plugin", "plugin-owned", false);

        assertThat(page.getItems()).extracting(PlaybackSyncInput::getVodId)
                .containsExactly("owned");
        assertThat(page.getNextSince()).isEqualTo("20");
    }

    @Test
    void embyIdentityIsSharedWithTvBoxSiteAndCanBeLookedUp() {
        History existing = history("csp_Emby", "emby-1", 100);
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "site", "csp_Emby", "emby-1")).thenReturn(List.of(existing));

        PlaybackSyncInput record = service.getRecord(UID, "emby", "", "emby-1");

        assertThat(record.getSourceKind()).isEqualTo("site");
        assertThat(record.getSourceKey()).isEqualTo("csp_Emby");
    }

    @Test
    void spiderPluginTombstoneUsesStableSourceKeyInsteadOfDisplayName() {
        String stableId = "02544b320a6d45de997bc0bd3975d0c060b8";
        History existing = history(stableId, "v1", 100);
        existing.setSourceKind("spider_plugin");
        existing.setSourceName("已重命名的木偶");
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "spider_plugin", stableId, "v1")).thenReturn(List.of(existing));

        service.delete(UID, null,deleteInput(Map.of(
                "scope", "item", "sourceKind", "spider_plugin", "sourceKey", stableId,
                "vodId", "v1", "deletedAt", 200)));

        assertThat(savedTombstone().getHistoryKey()).isEqualTo(stableId + "@@@v1@@@0");
    }

    // ── 列宽 ────────────────────────────────────────────────────────────────

    @Test
    void longIdentityIsKeptWhileDisplayFieldsAreClamped() {
        // 网盘源的 vod_id 是 URL 编码的 JSON,数百字符;身份不能截断,否则不同条目会并成一条
        String vodId = "奇异@" + "%7B%22title%22%3A%22".repeat(30);
        String longText = "标题".repeat(300);

        service.apply(id(UID),Map.of("sourceKind", "pan", "sourceKey", "abc", "vodId", vodId,
                "vodName", longText, "vodFlag", longText, "episodeName", longText,
                "clientKey", longText, "positionMs", 10, "updatedAt", 500), null, null);

        History saved = savedHistory();
        assertThat(saved.getVodId()).isEqualTo(vodId);
        // 展示字段按列宽截断:截断好过整条上报以 22001 失败丢记录
        assertThat(saved.getVodName()).hasSize(255);
        assertThat(saved.getVodFlag()).hasSize(255);
        assertThat(saved.getVodRemarks()).hasSize(255);
        assertThat(saved.getClientKey()).hasSize(64);
    }

    @Test
    void sourceNameAndZeroEpisodeRoundTrip() {
        service.apply(id(UID),Map.of("sourceKind", "emby", "sourceKey", "server", "sourceName", "客厅 Emby",
                "vodId", "v1", "episode", 0, "updatedAt", 500), null, null);

        History saved = savedHistory();
        assertThat(saved.getSourceName()).isEqualTo("客厅 Emby");
        assertThat(saved.getEpisode()).isZero();

        when(historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(
                eq(UID), eq(0L), any()))
                .thenReturn(List.of(saved));
        PlaybackSyncInput pulled = service.pull(UID, 0, 100, null).getItems().getFirst();
        assertThat(pulled.getSourceName()).isEqualTo("客厅 Emby");
        assertThat(pulled.getEpisode()).isZero();
    }

    @Test
    void playbackSelectionContextRoundTrips() {
        service.apply(id(UID),Map.ofEntries(
                Map.entry("sourceKind", "spider_plugin"),
                Map.entry("sourceKey", "02544b320a6d45de997bc0bd3975d0c060b8"),
                Map.entry("vodId", "173"),
                Map.entry("episodeUrl", "1@185535@6@1"),
                Map.entry("playlistIndex", 0),
                Map.entry("sourceGroupIndex", 2),
                Map.entry("sourceIndex", 0),
                Map.entry("sourceSubgroupIndex", 6),
                Map.entry("sourceSubgroupName", "07外海风云"),
                Map.entry("driveDirId", "stable-drive-directory"),
                Map.entry("updatedAt", 500))
                , null, null);

        History saved = savedHistory();
        assertThat(saved.getEpisodeUrl()).isEqualTo("1@185535@6@1");
        assertThat(saved.getPlaylistIndex()).isZero();
        assertThat(saved.getSourceGroupIndex()).isEqualTo(2);
        assertThat(saved.getSourceIndex()).isZero();
        assertThat(saved.getSourceSubgroupIndex()).isEqualTo(6);
        assertThat(saved.getSourceSubgroupName()).isEqualTo("07外海风云");
        assertThat(saved.getDriveDirId()).isEqualTo("stable-drive-directory");

        when(historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(
                eq(UID), eq(0L), any())).thenReturn(List.of(saved));
        PlaybackSyncInput pulled = service.pull(UID, 0, 100, null).getItems().getFirst();
        assertThat(pulled.getEpisodeUrl()).isEqualTo("1@185535@6@1");
        assertThat(pulled.getPlaylistIndex()).isZero();
        assertThat(pulled.getSourceGroupIndex()).isEqualTo(2);
        assertThat(pulled.getSourceIndex()).isZero();
        assertThat(pulled.getSourceSubgroupIndex()).isEqualTo(6);
        assertThat(pulled.getSourceSubgroupName()).isEqualTo("07外海风云");
        assertThat(pulled.getDriveDirId()).isEqualTo("stable-drive-directory");
    }

    private History spiderHistory(String episodeUrl, long updatedAt) {
        History h = new History();
        h.setUid(UID);
        h.setSourceKind("spider_plugin");
        h.setSourceKey("bbb01514f3d54e27bb48a80f50f2e39d5db0");
        h.setVodId("gy_tv_58kD");
        h.setEpisodeUrl(episodeUrl);
        h.setSourceGroupIndex(1);
        h.setSourceIndex(1);
        h.setDriveDirId("L-quark-S02");
        h.setUpdatedAt(updatedAt);
        h.setCreateTime(updatedAt);
        h.setChangeSeq(updatedAt);
        return h;
    }

    @Test
    void crossClientPushWithNewEpisodeUrlDropsStaleNavigationFields() {
        // atv-player 写入的记录带导航坐标(夸克 S02E03);安卓端续播上报的是另一个资源
        History atv = spiderHistory("1@188323@1@2", 500);
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "spider_plugin", "bbb01514f3d54e27bb48a80f50f2e39d5db0", "gy_tv_58kD"))
                .thenReturn(List.of(atv));

        service.apply(id(UID), Map.ofEntries(
                Map.entry("sourceKind", "spider_plugin"),
                Map.entry("sourceKey", "bbb01514f3d54e27bb48a80f50f2e39d5db0"),
                Map.entry("vodId", "gy_tv_58kD"),
                Map.entry("episodeUrl", "1@188076@0@2"),
                Map.entry("sourceSubgroupIndex", 0),
                Map.entry("sourceSubgroupName", "1"),
                Map.entry("updatedAt", 600)), null, null);

        // 不得把"夸克S02的坐标 + 百度S01的内容"拼进同一条记录
        History merged = savedHistory();
        assertThat(merged.getEpisodeUrl()).isEqualTo("1@188076@0@2");
        assertThat(merged.getSourceGroupIndex()).isNull();
        assertThat(merged.getSourceIndex()).isNull();
        assertThat(merged.getDriveDirId()).isNull();
        assertThat(merged.getSourceSubgroupIndex()).isZero();
        assertThat(merged.getSourceSubgroupName()).isEqualTo("1");
    }

    @Test
    void sameEpisodeReplayKeepsNavigationFields() {
        // 同一文件重播(episodeUrl 不变):坐标仍然有效,必须保留
        History atv = spiderHistory("1@188323@1@2", 500);
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "spider_plugin", "bbb01514f3d54e27bb48a80f50f2e39d5db0", "gy_tv_58kD"))
                .thenReturn(List.of(atv));

        service.apply(id(UID), Map.of(
                "sourceKind", "spider_plugin",
                "sourceKey", "bbb01514f3d54e27bb48a80f50f2e39d5db0",
                "vodId", "gy_tv_58kD",
                "episodeUrl", "1@188323@1@2",
                "positionMs", 900000,
                "updatedAt", 600), null, null);

        History merged = savedHistory();
        assertThat(merged.getEpisodeUrl()).isEqualTo("1@188323@1@2");
        assertThat(merged.getSourceGroupIndex()).isEqualTo(1);
        assertThat(merged.getSourceIndex()).isEqualTo(1);
        assertThat(merged.getDriveDirId()).isEqualTo("L-quark-S02");
    }

    @Test
    void drivePlayIdIsCanonicalizedToShareKeyAndRelativePath() {
        when(proxyService.getPath(188323)).thenReturn(
                "/我的夸克分享/temp/quark@2b3682416f78@/C 菜鸟老警S01~S06【1080P】/S02【2019】/S02E03.mp4");

        service.apply(id(UID), Map.of(
                "sourceKind", "spider_plugin",
                "sourceKey", "bbb01514f3d54e27bb48a80f50f2e39d5db0",
                "vodId", "gy_tv_58kD",
                "episodeUrl", "1@188323@1@2",
                "updatedAt", 500), null, null);

        History saved = savedHistory();
        assertThat(saved.getDriveShareKey()).isEqualTo("quark@2b3682416f78@");
        assertThat(saved.getDrivePath())
                .isEqualTo("/C 菜鸟老警S01~S06【1080P】/S02【2019】/S02E03.mp4");

        when(historyRepository.findByUidAndSourceKindIsNotNullAndChangeSeqGreaterThan(eq(UID), eq(0L), any()))
                .thenReturn(List.of(saved));
        PlaybackSyncInput pulled = service.pull(UID, 0, 100, null).getItems().getFirst();
        assertThat(pulled.getDriveShareKey()).isEqualTo("quark@2b3682416f78@");
        assertThat(pulled.getDrivePath())
                .isEqualTo("/C 菜鸟老警S01~S06【1080P】/S02【2019】/S02E03.mp4");
    }

    @Test
    void sameFileWithDifferentProxyIdKeepsNavigationFields() {
        // 同一文件重新解析会产生新 proxyId,episodeUrl 字符串不同但规范路径相同
        History atv = spiderHistory("1@188323@1@2", 500);
        atv.setDriveShareKey("baidu@1fFDWZTTtXy8aTPjKJ2F0uA@f1z9");
        atv.setDrivePath("/C 菜鸟老警 全8季 1080P/S02/S02E03.mp4");
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "spider_plugin", "bbb01514f3d54e27bb48a80f50f2e39d5db0", "gy_tv_58kD"))
                .thenReturn(List.of(atv));
        when(proxyService.getPath(188076)).thenReturn(
                "/我的百度分享/temp/baidu@1fFDWZTTtXy8aTPjKJ2F0uA@f1z9/C 菜鸟老警 全8季 1080P/S02/S02E03.mp4");

        service.apply(id(UID), Map.of(
                "sourceKind", "spider_plugin",
                "sourceKey", "bbb01514f3d54e27bb48a80f50f2e39d5db0",
                "vodId", "gy_tv_58kD",
                "episodeUrl", "1@188076@1@2",
                "positionMs", 900000,
                "updatedAt", 600), null, null);

        History merged = savedHistory();
        assertThat(merged.getEpisodeUrl()).isEqualTo("1@188076@1@2");
        assertThat(merged.getSourceGroupIndex()).isEqualTo(1);
        assertThat(merged.getSourceIndex()).isEqualTo(1);
        assertThat(merged.getDriveDirId()).isEqualTo("L-quark-S02");
        assertThat(merged.getDriveShareKey()).isEqualTo("baidu@1fFDWZTTtXy8aTPjKJ2F0uA@f1z9");
        assertThat(merged.getDrivePath()).isEqualTo("/C 菜鸟老警 全8季 1080P/S02/S02E03.mp4");
    }

    @Test
    void staleRowIsBackfilledAndComparedByCanonicalPath() {
        // 旧行没有规范路径(升级前的存量数据):按其 episodeUrl 惰性回填后再比较
        History atv = spiderHistory("1@188323@1@2", 500);
        when(historyRepository.findAllByUidAndSourceKindAndSourceKeyAndVodId(
                UID, "spider_plugin", "bbb01514f3d54e27bb48a80f50f2e39d5db0", "gy_tv_58kD"))
                .thenReturn(List.of(atv));
        when(proxyService.getPath(188323)).thenReturn(
                "/我的夸克分享/temp/quark@2b3682416f78@/C 菜鸟老警S01~S06/S02/S02E03.mp4");
        when(proxyService.getPath(188076)).thenReturn(
                "/我的百度分享/temp/baidu@1fFDWZTTtXy8aTPjKJ2F0uA@f1z9/C 菜鸟老警/S01/S01E03.mp4");

        service.apply(id(UID), Map.ofEntries(
                Map.entry("sourceKind", "spider_plugin"),
                Map.entry("sourceKey", "bbb01514f3d54e27bb48a80f50f2e39d5db0"),
                Map.entry("vodId", "gy_tv_58kD"),
                Map.entry("episodeUrl", "1@188076@0@2"),
                Map.entry("sourceSubgroupIndex", 0),
                Map.entry("sourceSubgroupName", "1"),
                Map.entry("updatedAt", 600)), null, null);

        History merged = savedHistory();
        assertThat(merged.getSourceGroupIndex()).isNull();
        assertThat(merged.getSourceIndex()).isNull();
        assertThat(merged.getDriveDirId()).isNull();
        assertThat(merged.getDriveShareKey()).isEqualTo("baidu@1fFDWZTTtXy8aTPjKJ2F0uA@f1z9");
        assertThat(merged.getDrivePath()).isEqualTo("/C 菜鸟老警/S01/S01E03.mp4");
    }

    @Test
    void nonDriveEpisodeUrlIsNotCanonicalized() {
        service.apply(id(UID), Map.of(
                "sourceKind", "site",
                "sourceKey", "csp_AList",
                "vodId", "v1",
                "episodeUrl", "https://example.com/video.mp4",
                "updatedAt", 500), null, null);

        History saved = savedHistory();
        assertThat(saved.getDriveShareKey()).isNull();
        assertThat(saved.getDrivePath()).isNull();
        verify(proxyService, never()).getPath(anyInt());
    }

    @Test
    void upsertLocksSequenceBeforeReadingConflictState() {
        service.apply(id(UID),Map.of("sourceKind", "pan", "sourceKey", "abc", "vodId", "v1",
                "updatedAt", 500), null, null);

        var order = inOrder(changeSequenceRepository, tombstoneRepository, historyRepository);
        order.verify(changeSequenceRepository).findByIdForUpdate(1);
        order.verify(tombstoneRepository)
                .findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "pan", "abc", "v1");
        order.verify(historyRepository)
                .findAllByUidAndSourceKindAndSourceKeyAndVodId(UID, "pan", "abc", "v1");
    }

    // ── 令牌 ────────────────────────────────────────────────────────────────

    @Test
    void resolveUidAcceptsPlaybackToken() {
        PlaybackToken token = new PlaybackToken();
        token.setUid(7);
        token.setToken("tk-1");
        when(tokenRepository.findByToken("tk-1")).thenReturn(java.util.Optional.of(token));

        assertThat(service.resolveUid("tk-1")).isEqualTo(7);
    }

    // ── 分区:不同订阅互不同步 ──────────────────────────────────────────────

    @Test
    void scopedPullOnlyReturnsSameScopeRecords() {
        History haroldRow = history("csp_TgDouBan", "v1", 500);
        haroldRow.setSyncScope("Harold");
        when(historyRepository.findSyncByCursor(eq(UID), eq("Harold"), eq(0L), any()))
                .thenReturn(List.of(haroldRow));
        when(tombstoneRepository.findSyncByCursor(eq(UID), eq("Harold"), eq(0L), any()))
                .thenReturn(List.of());

        PlaybackSyncPage page = service.pull(UID, "Harold", 0L, 100, null, null, false);

        // Harold 分区的拉取只见本分区记录;web 订阅的进度不会串过来
        assertThat(page.getItems()).extracting(PlaybackSyncInput::getVodId).containsExactly("v1");
    }

    @Test
    void scopedUpsertStampsSyncScopeAndDoesNotMergeAcrossScopes() {
        // web 分区写同身份记录时,不得命中 Harold 分区的既有行
        when(historyRepository.findSyncByIdentity(eq(UID), eq("web"), eq("site"), eq("abc"), eq("v1")))
                .thenReturn(List.of());

        service.apply(new PlaybackSyncService.TokenIdentity(UID, "web"),
                Map.of("sourceKey", "abc", "vodId", "v1", "updatedAt", 400), null, null);

        History saved = savedHistory();
        assertThat(saved.getSyncScope()).isEqualTo("web");
    }

    // ── 分区:uid 全局墓碑须下达 scoped 客户端 ──────────────────────────────

    @Test
    void scopedPullDeliversUidGlobalTombstone() {
        // scoped 客户端(Harold)拉取时,uid 全局墓碑必须一并下发——否则管理端删除对其不可见
        PlaybackTombstone uidGlobal = tombstone("site", "v1", 50);
        uidGlobal.setSourceKey("abc");
        when(historyRepository.findSyncByCursor(eq(UID), eq("Harold"), eq(0L), any()))
                .thenReturn(List.of());
        when(tombstoneRepository.findSyncByCursor(eq(UID), eq("Harold"), eq(0L), any()))
                .thenReturn(List.of(uidGlobal));

        PlaybackSyncPage page = service.pull(UID, "Harold", 0L, 100, null, null, false);

        assertThat(page.getDeleted()).hasSize(1);
        assertThat(page.getDeleted().getFirst().getSourceKey()).isEqualTo("abc");
    }

    @Test
    void scopedUpsertBlockedByUidGlobalTombstone() {
        // scoped 客户端(Harold)的 PUSH 必须被 uid 全局墓碑挡住,否则管理端删除后记录被复活(原缺陷)
        PlaybackTombstone uidGlobal = new PlaybackTombstone();
        uidGlobal.setScope("item");
        uidGlobal.setDeletedAt(1000L);
        when(tombstoneRepository.findSyncByIdentity(eq(UID), eq("Harold"), eq("site"), eq("abc"), eq("v1")))
                .thenReturn(List.of(uidGlobal));

        service.apply(new PlaybackSyncService.TokenIdentity(UID, "Harold"),
                Map.of("sourceKey", "abc", "vodId", "v1", "positionMs", 10, "updatedAt", 500), null, null);

        verify(historyRepository, never()).save(any());
    }

    @Test
    void managementClearAllDemotesScopedTombstoneToUidGlobal() {
        // 既有 scoped 'all' 墓碑(Harold 分区):管理端“清空全部”复用它时必须降级回 uid 全局分区,
        // 否则只有 Harold 客户端收到删除,其他 scoped 客户端的记录会被下次 PUSH 复活
        PlaybackTombstone scopedAll = new PlaybackTombstone();
        scopedAll.setScope("all");
        scopedAll.setSyncScope("Harold");
        scopedAll.setDeletedAt(100L);
        when(tombstoneRepository.findFirstByUidAndScopeOrderByDeletedAtDesc(UID, "all"))
                .thenReturn(scopedAll);
        when(historyRepository.findAllByUidAndSourceKindIsNotNull(eq(UID), any()))
                .thenReturn(List.of());

        service.deleteAllRecords(UID);

        PlaybackTombstone saved = savedTombstone();
        assertThat(saved.getSyncScope())
                .as("管理端清空全部必须落到 uid 全局分区")
                .isNull();
        assertThat(saved.getScope()).isEqualTo("all");
        assertThat(saved.getDeletedAt()).isGreaterThan(100L);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private History history(String sourceKey, String vodId, long updatedAt) {
        History h = new History();
        h.setUid(UID);
        h.setSourceKind("site");
        h.setSourceKey(sourceKey);
        h.setVodId(vodId);
        h.setUpdatedAt(updatedAt);
        h.setChangeSeq(updatedAt);
        h.setCreateTime(updatedAt);
        return h;
    }

    private PlaybackTombstone tombstone(String sourceKind, String vodId, long deletedAt) {
        PlaybackTombstone t = new PlaybackTombstone();
        t.setUid(UID);
        t.setScope(vodId == null ? "all" : "item");
        t.setSourceKind(sourceKind);
        t.setVodId(vodId);
        t.setDeletedAt(deletedAt);
        t.setChangeSeq(deletedAt);
        return t;
    }

    private PlaybackDeleteInput deleteInput(Map<String, Object> map) {
        return PlaybackDeleteInput.fromMap(new LinkedHashMap<>(map));
    }

    private PlaybackTombstone savedTombstone() {
        ArgumentCaptor<PlaybackTombstone> captor = ArgumentCaptor.forClass(PlaybackTombstone.class);
        verify(tombstoneRepository).save(captor.capture());
        return captor.getValue();
    }

    private History savedHistory() {
        ArgumentCaptor<History> captor = ArgumentCaptor.forClass(History.class);
        verify(historyRepository).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<History> deletedRows() {
        ArgumentCaptor<Iterable<History>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(historyRepository).deleteAll(captor.capture());
        List<History> rows = new ArrayList<>();
        captor.getValue().forEach(rows::add);
        return rows;
    }
}
