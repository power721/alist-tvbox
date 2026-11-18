package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.entity.RepairCandidate;
import cn.har01d.atmaintenance.entity.RepairSession;
import cn.har01d.atmaintenance.repository.RepairCandidateRepository;
import cn.har01d.atmaintenance.repository.RepairRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpiderDeliveryNamesTest {
    @Test
    void chineseNamesCarryVersionsAndCandidateStateWithoutRewritingSource() {
        var delivery = SpiderDeliveryNames.describe("观影V", "source_v2.3.9.py", "a".repeat(64), 2, true);
        assertEquals("观影V", delivery.pluginName());
        assertEquals("2.3.11", delivery.version());
        assertEquals("观影V_v2.3.11.py", delivery.filename());
        assertEquals("交付/观影V_v2.3.11.py", delivery.entryPath());
        assertEquals("观影V_v2.3.11_已验证.zip", delivery.archiveFilename());
        var draft = SpiderDeliveryNames.describe("freeok/../bad:*", null, "b".repeat(64), 1, false);
        assertTrue(draft.pluginName().startsWith("影视源_"));
        assertFalse(draft.filename().contains(".."));
        assertFalse(draft.filename().contains("/"));
        assertEquals("1.0.1", draft.version());
        assertTrue(draft.entryPath().startsWith("候选/"));
        assertTrue(draft.archiveFilename().endsWith("_候选.zip"));
        assertEquals("14.0.1", SpiderDeliveryNames.describe("测试", "spider_v14.py", "", 1, false).version());
    }

    @Test
    void repeatedBytesAndRepeatedDownloadsKeepTheSameIteration() {
        var repository = mock(RepairCandidateRepository.class);
        var repairs = mock(RepairRepository.class);
        var session = new RepairSession();
        session.setSourceId("source");
        var first = candidate("first", "a");
        var second = candidate("second", "b");
        var repeated = candidate("repeated", "a");
        when(repository.findChangedCandidates("source")).thenReturn(List.of(first, second, repeated));
        when(repository.findById("first")).thenReturn(Optional.of(first));
        when(repository.findById("second")).thenReturn(Optional.of(second));
        when(repository.findById("repeated")).thenReturn(Optional.of(repeated));
        when(repairs.findById("repair")).thenReturn(Optional.of(session));
        var store = new RepairStore(repairs, repository, null, null, null, null, null, null, null, null, null, null);
        assertEquals(1, store.deliveryIteration("first"));
        assertEquals(2, store.deliveryIteration("second"));
        assertEquals(1, store.deliveryIteration("repeated"));
        assertEquals(2, store.deliveryIteration("second"));
    }

    @Test
    void localizedNamesKeepTheVerifiedVersionAndArtifactIdentity() {
        var old = SpiderDeliveryNames.describe("Xvideos_TuT", "source_v2.3.9.py", "a".repeat(64), 2, true);
        var renamed = SpiderDeliveryNames.renamed(old, "艾克斯视频");
        assertEquals("艾克斯视频", renamed.pluginName());
        assertEquals("2.3.11", renamed.version());
        assertEquals("艾克斯视频_v2.3.11.py", renamed.filename());
        assertEquals("艾克斯视频_v2.3.11_已验证.zip", renamed.archiveFilename());
        assertEquals(old.candidateSha256(), renamed.candidateSha256());
        assertEquals(old.iteration(), renamed.iteration());
        assertTrue(renamed.verified());
    }

    private RepairCandidate candidate(String id, String sha) {
        var row = new RepairCandidate();
        row.setId(id);
        row.setRepairId("repair");
        row.setSha256(sha);
        return row;
    }
}
