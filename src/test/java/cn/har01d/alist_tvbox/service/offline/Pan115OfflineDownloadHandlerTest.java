package cn.har01d.alist_tvbox.service.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class Pan115OfflineDownloadHandlerTest {
    private static final String HEX = "c140e4eaf4fd88decf40ed52156c209c9ca88a8b";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractInfoHashLowercasesHexBtih() {
        assertEquals(HEX, Pan115OfflineDownloadHandler.extractInfoHash(
                "magnet:?xt=urn:btih:C140E4EAF4FD88DECF40ED52156C209C9CA88A8B"));
    }

    @Test
    void extractInfoHashIgnoresExtraMagnetParams() {
        assertEquals(HEX, Pan115OfflineDownloadHandler.extractInfoHash(
                "magnet:?xt=urn:btih:C140E4EAF4FD88DECF40ED52156C209C9CA88A8B&dn=movie&tr=udp://tracker"));
    }

    @Test
    void extractInfoHashReturnsEmptyWhenAbsent() {
        assertEquals("", Pan115OfflineDownloadHandler.extractInfoHash("https://example.com/file.mp4"));
        assertEquals("", Pan115OfflineDownloadHandler.extractInfoHash("magnet:?xt=urn:btih:"));
        assertEquals("", Pan115OfflineDownloadHandler.extractInfoHash(""));
    }

    @Test
    void findTaskMatchesByInfoHashWhenUrlStringDiffers() throws Exception {
        // submitted magnet uses uppercase btih + extra params; 115 stored lowercase url without params
        String submitted = "magnet:?xt=urn:btih:C140E4EAF4FD88DECF40ED52156C209C9CA88A8B&dn=movie&tr=udp://tracker";
        ObjectNode taskList = (ObjectNode) objectMapper.readTree(
                "{\"tasks\":[{\"url\":\"magnet:?xt=urn:btih:" + HEX + "\","
                        + "\"info_hash\":\"" + HEX + "\",\"status\":2,\"name\":\"movie.mkv\"}]}");

        ObjectNode task = Pan115OfflineDownloadHandler.findTaskInPage(taskList, submitted);

        assertNotNull(task);
        assertEquals(HEX, task.path("info_hash").asText());
    }

    @Test
    void findTaskFallsBackToExactUrlWhenInfoHashAbsent() throws Exception {
        String url = "https://example.com/file.mp4";
        ObjectNode taskList = (ObjectNode) objectMapper.readTree(
                "{\"tasks\":[{\"url\":\"https://example.com/file.mp4\",\"info_hash\":\"\","
                        + "\"status\":2,\"name\":\"file.mp4\"}]}");

        ObjectNode task = Pan115OfflineDownloadHandler.findTaskInPage(taskList, url);

        assertNotNull(task);
    }

    @Test
    void findTaskReturnsNullWhenNothingMatches() throws Exception {
        ObjectNode taskList = (ObjectNode) objectMapper.readTree(
                "{\"tasks\":[{\"url\":\"magnet:?xt=urn:btih:1111111111111111111111111111111111111111\","
                        + "\"info_hash\":\"1111111111111111111111111111111111111111\",\"status\":2}]}");

        ObjectNode task = Pan115OfflineDownloadHandler.findTaskInPage(taskList,
                "magnet:?xt=urn:btih:cccccccccccccccccccccccccccccccccccccccc");

        assertNull(task);
    }
}
