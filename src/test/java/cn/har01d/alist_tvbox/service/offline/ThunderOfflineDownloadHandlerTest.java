package cn.har01d.alist_tvbox.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThunderOfflineDownloadHandlerTest {
    private static final String HEX = "c140e4eaf4fd88decf40ed52156c209c9ca88a8b";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractInfoHashLowercasesBtih() {
        assertEquals(HEX, ThunderOfflineDownloadHandler.extractInfoHash(
                "magnet:?xt=urn:btih:C140E4EAF4FD88DECF40ED52156C209C9CA88A8B&dn=movie&tr=udp://tracker"));
    }

    @Test
    void extractInfoHashAcceptsBase32() {
        assertEquals("bc4qzcnfo3m3f7gkcxstjrxqe7g5dnp4", ThunderOfflineDownloadHandler.extractInfoHash(
                "magnet:?xt=urn:btih:BC4QZCNFO3M3F7GKCXSTJRXQE7G5DNP4"));
    }

    @Test
    void extractInfoHashReturnsEmptyWhenAbsentOrTooShort() {
        assertEquals("", ThunderOfflineDownloadHandler.extractInfoHash("https://example.com/file.torrent"));
        assertEquals("", ThunderOfflineDownloadHandler.extractInfoHash("magnet:?xt=urn:btih:short"));
        assertEquals("", ThunderOfflineDownloadHandler.extractInfoHash(""));
    }

    @Test
    void matchesTaskByExactUrl() throws Exception {
        ObjectNode task = task("{\"params\":{\"url\":\"https://example.com/file.torrent\"}}");
        assertTrue(ThunderOfflineDownloadHandler.matchesTask(task, "https://example.com/file.torrent", ""));
    }

    @Test
    void matchesTaskByInfoHashWhenTrackerParamsDiffer() throws Exception {
        // 提交磁力带 tracker 参数;网盘侧任务只存了裸磁力——infohash 桥接两种形态
        ObjectNode task = task("{\"params\":{\"url\":\"magnet:?xt=urn:btih:" + HEX + "\"}}");
        String submitted = "magnet:?xt=urn:btih:" + HEX.toUpperCase() + "&dn=movie&tr=udp://tracker";
        assertTrue(ThunderOfflineDownloadHandler.matchesTask(task, submitted, ThunderOfflineDownloadHandler.extractInfoHash(submitted)));
    }

    @Test
    void matchesTaskReturnsFalseWhenNoOverlap() throws Exception {
        ObjectNode task = task("{\"params\":{\"url\":\"magnet:?xt=urn:btih:" + "a".repeat(40) + "\"}}");
        assertFalse(ThunderOfflineDownloadHandler.matchesTask(task,
                "magnet:?xt=urn:btih:" + "b".repeat(40), "b".repeat(40)));
    }

    @Test
    void matchesTaskReturnsFalseWhenTaskUrlBlank() throws Exception {
        ObjectNode task = task("{\"id\":\"t1\"}");
        assertFalse(ThunderOfflineDownloadHandler.matchesTask(task, "https://example.com/file.torrent", ""));
    }

    @Test
    void buildTaskResultPrefersFileNameAndKeepsInfoHash() throws Exception {
        ObjectNode task = task("{\"file_name\":\"movie.mkv\",\"name\":\"fallback\",\"reference_resource\":{\"kind\":\"drive#file\"}}");
        OfflineDownloadHandler.TaskResult result = ThunderOfflineDownloadHandler.buildTaskResult(task, HEX);
        assertEquals("movie.mkv", result.taskName());
        assertEquals(HEX, result.infoHash());
        assertFalse(result.folder());
    }

    @Test
    void buildTaskResultFallsBackToNameAndDetectsFolder() throws Exception {
        ObjectNode task = task("{\"name\":\"season-01\",\"reference_resource\":{\"kind\":\"drive#folder\"}}");
        OfflineDownloadHandler.TaskResult result = ThunderOfflineDownloadHandler.buildTaskResult(task, "");
        assertEquals("season-01", result.taskName());
        assertTrue(result.folder());
    }

    @Test
    void buildTaskResultThrowsWhenNameMissing() throws Exception {
        ObjectNode task = task("{\"id\":\"t1\"}");
        assertThrows(Exception.class, () -> ThunderOfflineDownloadHandler.buildTaskResult(task, ""));
    }

    private ObjectNode task(String json) throws Exception {
        return (ObjectNode) objectMapper.readTree(json);
    }
}
