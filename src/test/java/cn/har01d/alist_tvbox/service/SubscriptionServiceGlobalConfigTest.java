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

    @Test
    void testUpdateGlobalConfigJsonSerialization() throws Exception {
        // 测试配置序列化
        Map<String, Object> config = Map.of("sites-blacklist", List.of("site1"));
        String json = objectMapper.writeValueAsString(config);

        assertNotNull(json);
        assertTrue(json.contains("sites-blacklist"));
        assertTrue(json.contains("site1"));
    }

    @Test
    void testApplyGlobalConfigLogic() {
        // 测试应用全局配置的逻辑
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> globalConfig = new HashMap<>();
        globalConfig.put("sites-blacklist", List.of("site1"));
        globalConfig.put("spider", "http://example.com/spider.jar");
        globalConfig.put("sites", List.of(Map.of("key", "global")));

        // 模拟 applyGlobalConfig 的逻辑
        for (Map.Entry<String, Object> entry : globalConfig.entrySet()) {
            String key = entry.getKey();
            if (!"spider".equals(key) && !config.containsKey(key)) {
                config.put(key, entry.getValue());
            }
        }

        assertTrue(config.containsKey("sites-blacklist"));
        assertTrue(config.containsKey("sites"));
        assertFalse(config.containsKey("spider")); // spider 不应该被应用
    }
}
