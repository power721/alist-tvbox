package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.PanLinkCheckService;
import cn.har01d.alist_tvbox.service.PanSouClient;
import cn.har01d.alist_tvbox.service.RemoteSearchService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网页盘搜后端化端点:移植页(玩偶/nostr)以 /pan-search/{token} 为 apiBase,同路径同体
 * 透传上游 PanSou(登录态由 PanSouClient 承担);token 闸门与其它 /{token} 端点同款。
 */
class RemoteSearchControllerTest {

    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final PanSouClient panSouClient = mock(PanSouClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RemoteSearchController controller = new RemoteSearchController(
            subscriptionService, mock(RemoteSearchService.class), mock(PanLinkCheckService.class),
            panSouClient, objectMapper);

    @Test
    void panSearchForwardsBodyVerbatimAndChecksToken() {
        ObjectNode body = objectMapper.createObjectNode().put("kw", "测试").put("res", "merge");
        ObjectNode upstream = objectMapper.createObjectNode().put("quota", 1);
        when(panSouClient.postJson("/api/search", body)).thenReturn(upstream);

        assertSame(upstream, controller.panSearch("tok1", body));
        verify(subscriptionService).checkToken("tok1");
    }

    @Test
    void panSearchLoginAlsoTokenGated() {
        ObjectNode body = objectMapper.createObjectNode().put("username", "u");
        ObjectNode upstream = objectMapper.createObjectNode().put("token", "t");
        when(panSouClient.postJson("/api/auth/login", body)).thenReturn(upstream);

        assertSame(upstream, controller.panSearchLogin("tok2", body));
        verify(subscriptionService).checkToken("tok2");
    }
}
