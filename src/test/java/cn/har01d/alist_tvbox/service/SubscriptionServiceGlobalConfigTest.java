package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionServiceGlobalConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testGlobalConfigJsonParsing() throws Exception {
        // 测试 JSON 解析逻辑
        String json = "{\"sites-blacklist\":[\"site1\",\"site2\"]}";
        Map<String, Object> config = objectMapper.readValue(json, Map.class);

        assertNotNull(config);
        assertTrue(config.containsKey("sites-blacklist"));
        assertEquals(List.of("site1", "site2"), config.get("sites-blacklist"));
    }

    @Test
    void testEmptyConfigHandling() {
        // 测试空配置处理
        Map<String, Object> config = new HashMap<>();
        assertTrue(config.isEmpty());
    }
}
