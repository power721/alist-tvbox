package cn.har01d.alist_tvbox.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 追剧搜索定向集(docs/msub-search-drive-targeting.md)的口径真值表。 */
class SearchTargetsTest {

    @Test
    void strictGateFollowsWhitelistAndOfflineSwitch() {
        SearchTargets targets = SearchTargets.of(Set.of("quark", "115"), false);
        assertTrue(targets.allowsType("5"), "夸克(数字串)在白名单");
        assertTrue(targets.allowsType("quark"), "盘 key 同权");
        assertTrue(targets.allowsType("8"), "115 在白名单");
        assertFalse(targets.allowsType("10"), "百度不在白名单");
        assertFalse(targets.allowsType("magnet"), "兜底未开:磁力剔除");
        assertFalse(targets.allowsType("ed2k"));
    }

    @Test
    void globalGateReplacesGlobalDrivesOnlyWhenWhitelistConfigured() {
        SearchTargets targets = SearchTargets.of(Set.of("quark"), false);
        // 白名单非空:替换全局口径 —— 全局放行的百度也被拒(全局配置不得误杀扩展盘的反向:不得放进域外盘)
        assertFalse(targets.allowsType("10", true));
        // 磁力即使全局 tg.drivers 配了也不放行:白名单模式下以订阅开关为准
        assertFalse(targets.allowsType("magnet", true));

        SearchTargets unrestricted = SearchTargets.UNRESTRICTED;
        assertTrue(unrestricted.allowsType("10", true), "白名单空:网盘完全跟随全局口径(放行则放行)");
        assertFalse(unrestricted.allowsType("10", false), "全局未放行则拒");
        assertTrue(unrestricted.allowsType("magnet", true), "白名单空:磁力保留全局既有放行,不收窄现状");
        assertFalse(unrestricted.allowsType("magnet", false), "全局也没放行且未并入:剔除");
    }

    @Test
    void offlineIncludedKeepsMagnetAndEd2k() {
        SearchTargets targets = SearchTargets.of(Set.of("quark"), true);
        assertTrue(targets.allowsType("magnet"));
        assertTrue(targets.allowsType("ed2k"));
        assertTrue(targets.allowsType("magnet", false), "并入开关优先于全局口径");
        assertFalse(targets.allowsType("10", true));
    }

    @Test
    void unknownTypeValuesFailClosed() {
        SearchTargets targets = SearchTargets.of(Set.of("quark"), false);
        assertFalse(targets.allowsType("video"), "白名单模式下未知类型拒收");
        assertFalse(targets.allowsType(""), "空类型(盘 key 不可识别)拒收");
        assertNull(DriveId.toTypeLeniently("bogus"), "宽松映射坏值返 null 不抛");
        assertEquals(5, DriveId.toTypeLeniently("quark"));
        assertEquals(9, DriveId.toTypeLeniently("189"), "天翼按数字码 9 映射");
    }
}
