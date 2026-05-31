package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.Share;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuangYaPanStorageTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void guangYaPanAccountBuildsExpectedAddition() throws Exception {
        DriverAccount account = new DriverAccount();
        account.setId(7);
        account.setType(DriverType.GUANGYA);
        account.setName("main");
        account.setToken("access-token");
        account.setFolder("root-folder");
        account.setAddition("{\"refresh_token\":\"refresh-token\",\"device_id\":\"0123456789abcdef0123456789abcdef\"}");

        GuangYaPan storage = new GuangYaPan(account);
        JsonNode addition = objectMapper.readTree(storage.getAddition());

        assertEquals(4007, storage.getId());
        assertEquals("GuangYaPan", storage.getDriver());
        assertEquals("/我的光鸭云盘/main", storage.getPath());
        assertEquals("root-folder", addition.get("root_folder_id").asText());
        assertEquals("access-token", addition.get("access_token").asText());
        assertEquals("refresh-token", addition.get("refresh_token").asText());
        assertEquals("0123456789abcdef0123456789abcdef", addition.get("device_id").asText());
        assertEquals(100, addition.get("page_size").asInt());
        assertEquals(3, addition.get("order_by").asInt());
        assertEquals(1, addition.get("sort_type").asInt());
    }

    @Test
    void guangYaPanAccountFallsBackToAdditionAccessToken() throws Exception {
        DriverAccount account = new DriverAccount();
        account.setId(8);
        account.setType(DriverType.GUANGYA);
        account.setName("fallback");
        account.setFolder("0");
        account.setAddition("{\"access_token\":\"addition-access\",\"refresh_token\":\"refresh-token\"}");

        GuangYaPan storage = new GuangYaPan(account);
        JsonNode addition = objectMapper.readTree(storage.getAddition());

        assertEquals("addition-access", addition.get("access_token").asText());
        assertEquals("refresh-token", addition.get("refresh_token").asText());
    }

    @Test
    void guangYaPanShareBuildsExpectedAddition() throws Exception {
        Share share = new Share();
        share.setId(20001);
        share.setType(12);
        share.setPath("Movies");
        share.setShareId("1894369771769081942_aeWVzywV3ZOZly47");
        share.setCookie("0123456789abcdef0123456789abcdef");

        GuangYaPanShare storage = new GuangYaPanShare(share);
        JsonNode addition = objectMapper.readTree(storage.getAddition());

        assertEquals(20001, storage.getId());
        assertEquals("GuangYaPanShare", storage.getDriver());
        assertEquals("/我的光鸭分享/Movies", storage.getPath());
        assertEquals("1894369771769081942_aeWVzywV3ZOZly47", addition.get("share_id").asText());
        assertEquals("0123456789abcdef0123456789abcdef", addition.get("device_id").asText());
        assertEquals(200, addition.get("page_size").asInt());
        assertEquals(0, addition.get("order_by").asInt());
        assertEquals(0, addition.get("sort_type").asInt());
    }
}
