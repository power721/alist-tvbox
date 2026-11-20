package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairExportNamingTest {
    @Test
    void modelSiteNameAndPublishedCorrectionNameDownloadsWithoutRewritingProposal() {
        var mapper = new ObjectMapper();
        var repairs = mock(RepairStore.class);
        var sources = mock(SourceService.class);
        var publications = mock(AtPublicationStore.class);
        var proposal = mapper.createObjectNode().put("deliveryName", "艾克斯视频");
        when(repairs.candidate("candidate")).thenReturn(new RepairCandidateSummary("candidate", "repair", 1,
                "a".repeat(64), "proposal", "RUNTIME_OBSERVED", null, Instant.now(), Instant.now(),
                proposal, mapper.createObjectNode().put("repairSuccessful", true), null, null, null));
        when(repairs.get("repair")).thenReturn(new RepairSummary("repair", "source", "run", "baseline", "fault",
                "SUCCEEDED", null, 1, 3, "candidate", Instant.now(), mapper.createObjectNode(), "current", List.of()));
        when(repairs.deliveryIteration("candidate")).thenReturn(1);
        when(sources.get("source")).thenReturn(new SourceSummary("source", "Xvideos_TuT", null,
                "at-530.py", null, "baseline", 0, Instant.now(), "profile", null));
        when(publications.list("candidate")).thenReturn(List.of());
        var exports = new RepairExportService(repairs, sources, null, mapper, null, publications);
        assertEquals("艾克斯视频_v1.0.1.py", exports.delivery("candidate").filename());
        when(publications.list("candidate")).thenReturn(List.of(new AtPublicationSummary("publication", "candidate",
                "incremental", "PUBLISHED", 530, 569, "中文站名_v1.0.1", "1.0.1",
                "a".repeat(64), "baseline", null, Instant.now())));
        assertEquals("中文站名_v1.0.1_已验证.zip", exports.delivery("candidate").archiveFilename());
        assertEquals("艾克斯视频", proposal.path("deliveryName").asText());
        assertEquals("a".repeat(64), exports.delivery("candidate").candidateSha256());
    }
}
