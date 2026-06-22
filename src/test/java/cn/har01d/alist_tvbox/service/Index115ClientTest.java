package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Site;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Index115ClientTest {
    private RestTemplate rt;
    private Index115Client client;
    private MockRestServiceServer server;
    private final Site site = site();

    @BeforeEach
    void setup() {
        rt = new RestTemplate();
        server = MockRestServiceServer.createServer(rt);
        client = new Index115Client(new RestTemplateBuilder().detectRequestFactory(false).requestFactory(() -> rt.getRequestFactory()));
    }

    @Test
    void browseRootReturnsShares() {
        server.expect(requestTo("http://p/api/index115/browse?share_code=&receive_code=&parent_id="))
                .andRespond(withSuccess("{\"code\":200,\"data\":[{\"FileID\":\"\",\"ShareCode\":\"sw1\",\"ReceiveCode\":\"6666\",\"Name\":\"Lib\",\"IsDir\":true}]}", MediaType.APPLICATION_JSON));
        var items = client.browse(site, "", "", "");
        server.verify();
        assertEquals(1, items.size());
        assertEquals("sw1", items.get(0).getShareCode());
        assertEquals("Lib", items.get(0).getName());
    }

    @Test
    void searchReturnsItems() {
        server.expect(requestTo("http://p/api/index115/search?q=foo&page=1&per_page=20"))
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"total\":1,\"items\":[{\"FileID\":\"f1\",\"ShareCode\":\"sw1\",\"ReceiveCode\":\"6666\",\"Name\":\"a.mkv\",\"IsDir\":false}]}}", MediaType.APPLICATION_JSON));
        var data = client.search(site, "foo", 1, 20);
        assertEquals(1, data.getTotal());
        assertEquals("f1", data.getItems().get(0).getFileId());
    }

    @Test
    void resolveLinkReturnsUrl() {
        server.expect(requestTo("http://p/api/index115/link"))
                .andExpect(jsonPath("$.share_code").value("sw1"))
                .andExpect(jsonPath("$.file_id").value("f1"))
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"url\":\"http://play/x\",\"expired_in\":600}}", MediaType.APPLICATION_JSON));
        assertEquals("http://play/x", client.resolveLink(site, "CK", "sw1", "6666", "f1"));
    }

    private Site site() {
        Site s = new Site();
        s.setUrl("http://p");
        s.setToken("TKN");
        return s;
    }
}
