package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccount;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccountChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannel;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderLoginResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderWebAccessCheckItem;
import cn.har01d.alist_tvbox.dto.tg.TgWatchRule;
import cn.har01d.alist_tvbox.dto.tg.TgWatchRuleRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TgProviderClientTest {
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private TgProviderClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new TgProviderClient(restTemplate, new AppProperties(), new ObjectMapper(), "http://127.0.0.1:6000");
    }

    @Test
    void shouldSendCodeWithPhoneBody() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/login/send-code"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"phone\":\"+8613800000000\"}"))
                .andRespond(withSuccess("{\"status\":\"LOGIN_REQUIRED\"}", MediaType.APPLICATION_JSON));

        TgProviderLoginResponse response = client.sendCode("+8613800000000");

        assertThat(response.status()).isEqualTo("LOGIN_REQUIRED");
        server.verify();
    }

    @Test
    void shouldParseFirstAccount() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/accounts"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"id":3,"username":"u","first_name":"First","last_name":"Last","phone":"+86138"}]
                        """, MediaType.APPLICATION_JSON));

        List<TgProviderAccount> accounts = client.accounts();

        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().id()).isEqualTo(3);
        assertThat(accounts.getFirst().username()).isEqualTo("u");
        assertThat(accounts.getFirst().phone()).isEqualTo("+86138");
        server.verify();
    }

    @Test
    void shouldParseAccountsFromItemsEnvelope() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/accounts"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"items":[{"id":1,"phone":"+8619113152564","telegram_user_id":1734222246,"first_name":"Harold","last_name":"Finch","username":"power721","status":"ONLINE","created_at":"2026-06-07T06:10:11.41198197Z","updated_at":"2026-06-07T06:17:24.592484439Z"}]}
                        """, MediaType.APPLICATION_JSON));

        List<TgProviderAccount> accounts = client.accounts();

        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().id()).isEqualTo(1);
        assertThat(accounts.getFirst().username()).isEqualTo("power721");
        assertThat(accounts.getFirst().firstName()).isEqualTo("Harold");
        assertThat(accounts.getFirst().phone()).isEqualTo("+8619113152564");
        server.verify();
    }

    @Test
    void shouldRefreshLoginStateFromProviderStatus() {
        AppProperties appProperties = new AppProperties();
        TgProviderClient providerClient = new TgProviderClient(restTemplate, appProperties, new ObjectMapper(), "http://127.0.0.1:6000");
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/status"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"service":"tg-provider","accounts":1,"channels":3,"messages":5,"links":8}
                        """, MediaType.APPLICATION_JSON));

        boolean login = providerClient.refreshLoginState();

        assertThat(login).isTrue();
        assertThat(appProperties.isTgLogin()).isTrue();
        server.verify();
    }

    @Test
    void shouldDeleteAccountById() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/accounts/3"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());

        client.deleteAccount(3);

        server.verify();
    }

    @Test
    void shouldMapProviderSearchItemsToMessages() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/search?q=%E7%9F%AD%E5%89%A7&limit=100"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"items":[{"id":1,"telegram_message_id":123,"text":"名称：短剧合集","date":"2026-06-07T12:00:00Z","channel_title":"热心用户","channel_username":"Quark_Share_Group","links":[{"type":"quark","url":"https://pan.quark.cn/s/abc","password":""},{"type":"baidu","url":"https://pan.baidu.com/s/1abc?pwd=ruub","password":"ruub"}]}]}
                        """, MediaType.APPLICATION_JSON));

        List<Message> messages = client.searchMessages("短剧", 100);

        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst().getId()).isEqualTo(123);
        assertThat(messages.getFirst().getChannel()).isEqualTo("Quark_Share_Group");
        assertThat(messages.getFirst().getType()).isEqualTo("5");
        assertThat(messages.get(1).getType()).isEqualTo("10");
        server.verify();
    }

    @Test
    void shouldParseChannelsFromItemsEnvelope() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/channels"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"items":[{"id":7,"account_id":1,"telegram_channel_id":1001,"access_hash":2002,"title":"VIP","username":"vip_share","type":"channel","last_message_id":88,"last_sync_time":"2026-06-07T12:00:00Z","web_access":true,"web_access_checked_at":"2026-06-07T12:05:00Z","created_at":"2026-06-07T06:00:00Z","updated_at":"2026-06-07T12:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));

        List<TgProviderChannel> channels = client.channels();

        assertThat(channels).hasSize(1);
        assertThat(channels.getFirst().id()).isEqualTo(7);
        assertThat(channels.getFirst().accountId()).isEqualTo(1);
        assertThat(channels.getFirst().title()).isEqualTo("VIP");
        assertThat(channels.getFirst().username()).isEqualTo("vip_share");
        assertThat(channels.getFirst().webAccess()).isTrue();
        assertThat(channels.getFirst().webAccessCheckedAt()).isEqualTo("2026-06-07T12:05:00Z");
        server.verify();
    }

    @Test
    void shouldLoadSingleChannelById() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/channels/7"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"id":7,"account_id":1,"telegram_channel_id":1001,"access_hash":2002,"title":"VIP","username":"vip_share","type":"channel","last_message_id":88,"last_sync_time":"2026-06-07T12:00:00Z","web_access":true,"web_access_checked_at":"2026-06-07T12:05:00Z","created_at":"2026-06-07T06:00:00Z","updated_at":"2026-06-07T12:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        TgProviderChannel channel = client.channel(7L);

        assertThat(channel.id()).isEqualTo(7);
        assertThat(channel.webAccess()).isTrue();
        assertThat(channel.webAccessCheckedAt()).isEqualTo("2026-06-07T12:05:00Z");
        server.verify();
    }

    @Test
    void shouldTreatNullItemsEnvelopeAsEmptyList() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/channels"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"items":null}
                        """, MediaType.APPLICATION_JSON));

        List<TgProviderChannel> channels = client.channels();

        assertThat(channels).isEmpty();
        server.verify();
    }

    @Test
    void shouldSurfaceProviderErrorEnvelopeWhenChannelsFail() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/channels"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"error":{"code":"internal_error","message":"database is locked"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.channels())
                .isInstanceOf(TgProviderClient.TgProviderException.class)
                .hasMessageContaining("channels")
                .hasMessageContaining("internal_error")
                .hasMessageContaining("database is locked");
        server.verify();
    }

    @Test
    void shouldSyncAccountChannelsWithProvider() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/accounts/1/channels/sync"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"job_id":"3","status":"queued"}
                        """, MediaType.APPLICATION_JSON));

        TgProviderAccountChannelSyncResponse response = client.syncAccountChannels(1);

        assertThat(response.jobId()).isEqualTo("3");
        assertThat(response.status()).isEqualTo("queued");
        server.verify();
    }

    @Test
    void shouldSearchMessagesByChannelId() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/search?q=%E7%9F%AD%E5%89%A7&limit=30&channel_id=7"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"items":[{"id":1,"telegram_message_id":123,"text":"名称：私密短剧","date":"2026-06-07T12:00:00Z","channel_title":"VIP","channel_username":"vip_share","links":[{"type":"quark","url":"https://pan.quark.cn/s/private","password":""}]}]}
                        """, MediaType.APPLICATION_JSON));

        List<Message> messages = client.searchMessages("短剧", 30, 7L);

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getChannel()).isEqualTo("vip_share");
        assertThat(messages.getFirst().getLink()).isEqualTo("https://pan.quark.cn/s/private");
        server.verify();
    }

    @Test
    void shouldLoadLatestMessagesByChannelId() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/messages/latest?limit=20&channel_id=7"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"items":[{"id":2,"telegram_message_id":124,"text":"名称：最新资源","date":"2026-06-07T13:00:00Z","channel_title":"VIP","channel_username":"vip_share","links":[{"type":"baidu","url":"https://pan.baidu.com/s/1abc?pwd=ruub","password":"ruub"}]}]}
                        """, MediaType.APPLICATION_JSON));

        List<Message> messages = client.latestMessages(20, 7L);

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getType()).isEqualTo("10");
        server.verify();
    }

    @Test
    void shouldSyncChannelsWithProviderRequestBody() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/channels/sync"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"channel_ids\":[7,9]}"))
                .andRespond(withSuccess("""
                        {"queued":2,"skipped":0,"results":{"7":{"messages":3,"links":2},"9":{"messages":1,"links":1}},"failures":{}}
                        """, MediaType.APPLICATION_JSON));

        TgProviderSyncResponse response = client.syncChannels(List.of(7L, 9L));

        assertThat(response.queued()).isEqualTo(2);
        assertThat(response.results().get(7L).links()).isEqualTo(2);
        server.verify();
    }

    @Test
    void shouldSyncSingleChannelWithProviderPath() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/channels/7/sync"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"messages":3,"links":2}
                        """, MediaType.APPLICATION_JSON));

        TgProviderChannelSyncResponse response = client.syncChannel(7L);

        assertThat(response.messages()).isEqualTo(3);
        assertThat(response.links()).isEqualTo(2);
        server.verify();
    }

    @Test
    void shouldCheckWebAccessWithProviderRequestBody() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/channels/web-access/check"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"channel_ids\":[7,9]}"))
                .andRespond(withSuccess("""
                        {"items":[{"channel_id":7,"web_access":true,"checked_at":"2026-06-07T12:05:00Z"},{"channel_id":9,"web_access":false,"checked_at":"2026-06-07T12:05:01Z"}]}
                        """, MediaType.APPLICATION_JSON));

        List<TgProviderWebAccessCheckItem> items = client.checkChannelWebAccess(List.of(7L, 9L));

        assertThat(items).hasSize(2);
        assertThat(items.getFirst().channelId()).isEqualTo(7);
        assertThat(items.getFirst().webAccess()).isTrue();
        assertThat(items.getFirst().checkedAt()).isEqualTo("2026-06-07T12:05:00Z");
        server.verify();
    }

    @Test
    void shouldUseDedicatedClientForWebAccessCheck() {
        RestTemplate regularRestTemplate = new RestTemplate();
        RestTemplate webAccessRestTemplate = new RestTemplate();
        MockRestServiceServer regularServer = MockRestServiceServer.createServer(regularRestTemplate);
        MockRestServiceServer webAccessServer = MockRestServiceServer.createServer(webAccessRestTemplate);
        TgProviderClient providerClient = new TgProviderClient(
                regularRestTemplate,
                webAccessRestTemplate,
                new AppProperties(),
                new ObjectMapper(),
                "http://127.0.0.1:6000");
        webAccessServer.expect(once(), requestTo("http://127.0.0.1:6000/api/channels/web-access/check"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"channel_ids\":[7]}"))
                .andRespond(withSuccess("""
                        {"items":[{"channel_id":7,"web_access":true,"checked_at":"2026-06-07T12:05:00Z"}]}
                        """, MediaType.APPLICATION_JSON));

        List<TgProviderWebAccessCheckItem> items = providerClient.checkChannelWebAccess(List.of(7L));

        assertThat(items.getFirst().webAccess()).isTrue();
        regularServer.verify();
        webAccessServer.verify();
    }

    @Test
    void shouldListWatchRulesFromProviderEnvelope() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/watch-rules"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"items":[{"id":1,"channel_id":7,"enabled":true,"includes":["庆余年"],"excludes":["预告"],"created_at":"2026-06-07T12:00:00Z","updated_at":"2026-06-07T12:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));

        List<TgWatchRule> rules = client.watchRules();

        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst().id()).isEqualTo(1);
        assertThat(rules.getFirst().channelId()).isEqualTo(7);
        assertThat(rules.getFirst().includes()).containsExactly("庆余年");
        assertThat(rules.getFirst().excludes()).containsExactly("预告");
        server.verify();
    }

    @Test
    void shouldLoadSingleWatchRuleById() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/watch-rules/1"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"id":1,"channel_id":7,"enabled":true,"includes":["庆余年"],"excludes":["预告"],"created_at":"2026-06-07T12:00:00Z","updated_at":"2026-06-07T12:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        TgWatchRule rule = client.watchRule(1L);

        assertThat(rule.id()).isEqualTo(1);
        assertThat(rule.channelId()).isEqualTo(7);
        server.verify();
    }

    @Test
    void shouldCreateWatchRuleWithProviderRequestBody() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/watch-rules"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"channel_id":7,"enabled":true,"includes":["庆余年"],"excludes":["预告"]}
                        """))
                .andRespond(withSuccess("""
                        {"id":1,"channel_id":7,"enabled":true,"includes":["庆余年"],"excludes":["预告"],"created_at":"2026-06-07T12:00:00Z","updated_at":"2026-06-07T12:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        TgWatchRule rule = client.createWatchRule(new TgWatchRuleRequest(7L, true, List.of("庆余年"), List.of("预告")));

        assertThat(rule.id()).isEqualTo(1);
        assertThat(rule.channelId()).isEqualTo(7);
        assertThat(rule.enabled()).isTrue();
        server.verify();
    }

    @Test
    void shouldUpdateWatchRuleWithProviderRequestBody() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/watch-rules/1"))
                .andExpect(method(PUT))
                .andExpect(content().json("""
                        {"channel_id":7,"enabled":false,"includes":["庆余年"],"excludes":[]}
                        """))
                .andRespond(withSuccess("""
                        {"id":1,"channel_id":7,"enabled":false,"includes":["庆余年"],"excludes":[],"created_at":"2026-06-07T12:00:00Z","updated_at":"2026-06-07T13:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        TgWatchRule rule = client.updateWatchRule(1L, new TgWatchRuleRequest(7L, false, List.of("庆余年"), List.of()));

        assertThat(rule.id()).isEqualTo(1);
        assertThat(rule.enabled()).isFalse();
        assertThat(rule.updatedAt()).isEqualTo("2026-06-07T13:00:00Z");
        server.verify();
    }

    @Test
    void shouldDeleteWatchRuleById() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/watch-rules/1"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());

        client.deleteWatchRule(1L);

        server.verify();
    }

    @Test
    void shouldPreserveProviderHttpStatusOnWatchRuleConflict() {
        server.expect(once(), requestTo("http://127.0.0.1:6000/api/watch-rules"))
                .andExpect(method(POST))
                .andRespond(withStatus(CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"watch rule already exists\"}"));

        assertThatThrownBy(() -> client.createWatchRule(new TgWatchRuleRequest(7L, true, List.of(), List.of())))
                .isInstanceOf(TgProviderClient.TgProviderException.class)
                .extracting("statusCode")
                .isEqualTo(CONFLICT);
        server.verify();
    }
}
