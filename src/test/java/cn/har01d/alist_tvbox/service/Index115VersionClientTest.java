package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.Index115ShareRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Index115VersionClientTest {
    private RestTemplate restTemplate;
    private Index115VersionClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setup() {
        restTemplate = new RestTemplate();
        client = new Index115VersionClient(restTemplate, "https://example.test/115.version.txt");
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void fetchParsesShareRef() {
        server.expect(requestTo("https://example.test/115.version.txt"))
                .andRespond(withSuccess("swf01d43zby:6666\n", MediaType.TEXT_PLAIN));
        Index115ShareRef ref = client.fetch();
        server.verify();
        assertEquals("swf01d43zby", ref.shareCode());
        assertEquals("6666", ref.receiveCode());
    }

    @Test
    void parseRejectsMalformed() {
        assertNull(Index115VersionClient.parse(null));
        assertNull(Index115VersionClient.parse("nope"));
        assertNull(Index115VersionClient.parse(":6666"));
        assertNull(Index115VersionClient.parse("sw1:"));
    }
}
