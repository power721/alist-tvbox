package cn.har01d.alist_tvbox.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliGaiaFingerprintUtilsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generateDeviceUuidMatchesBrowserShape() {
        for (int i = 0; i < 100; i++) {
            String uuid = BiliGaiaFingerprintUtils.generateDeviceUuid();
            assertTrue(uuid.matches("[0-9A-F]{9}-[0-9A-F]{4}-[0-9A-F]{5}-[0-9A-F]{4}-[0-9A-F]{12}\\d{6}infoc"), uuid);
        }
    }

    @Test
    void buildPayloadEmbedsDeviceUuidAndStaysDoubleEncoded() throws Exception {
        String uuid = BiliGaiaFingerprintUtils.generateDeviceUuid();
        String body = BiliGaiaFingerprintUtils.buildPayload(uuid);

        JsonNode outer = mapper.readTree(body);
        assertTrue(outer.has("payload") && outer.size() == 1, body);
        JsonNode payload = mapper.readTree(outer.get("payload").asText());
        assertEquals(uuid, payload.path("df35").asText());
        assertEquals("https%3A%2F%2Fwww.bilibili.com%2F", payload.path("03bf").asText());
        assertEquals("333.1007.fp.risk", payload.path("39c8").asText());
        assertTrue(payload.path("5062").asLong() > 0);
        // 设备特征快照原样保留:UA 字段与模板常量一致,54ef 双层 JSON 完整可解析
        assertEquals(BiliGaiaFingerprintUtils.USER_AGENT, payload.path("3c43").path("b8ce").asText());
        JsonNode abConfig = mapper.readTree(payload.path("54ef").asText());
        assertEquals("V8", abConfig.path("home_version").asText());
        assertEquals(1, payload.path("3064").asInt());
    }
}
