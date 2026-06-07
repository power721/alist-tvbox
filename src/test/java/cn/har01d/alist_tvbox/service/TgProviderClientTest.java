package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccount;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannel;
import cn.har01d.alist_tvbox.dto.tg.TgProviderLoginResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TgProviderClientTest {
    private MockRestServiceServer server;
    private TgProviderClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new TgProviderClient(restTemplate, new ObjectMapper(), "http://127.0.0.1:6000");
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
                        {"items":[{"id":7,"account_id":1,"telegram_channel_id":1001,"access_hash":2002,"title":"VIP","username":"vip_share","type":"channel","last_message_id":88,"last_sync_time":"2026-06-07T12:00:00Z","created_at":"2026-06-07T06:00:00Z","updated_at":"2026-06-07T12:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));

        List<TgProviderChannel> channels = client.channels();

        assertThat(channels).hasSize(1);
        assertThat(channels.getFirst().id()).isEqualTo(7);
        assertThat(channels.getFirst().accountId()).isEqualTo(1);
        assertThat(channels.getFirst().title()).isEqualTo("VIP");
        assertThat(channels.getFirst().username()).isEqualTo("vip_share");
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
}
