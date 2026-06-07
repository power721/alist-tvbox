package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccount;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccountChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannel;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TgPrivateChannelServiceTest {
    @Mock
    private TgProviderClient tgProviderClient;
    @Mock
    private SettingRepository settingRepository;

    private TgPrivateChannelService service;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.setTgDrivers(List.of("5", "10"));
        service = new TgPrivateChannelService(tgProviderClient, settingRepository, appProperties);
    }

    @Test
    void shouldMergeProviderChannelsWithEnabledSetting() {
        when(settingRepository.findById("tg_private_channel_ids")).thenReturn(Optional.of(new Setting("tg_private_channel_ids", "7,9")));
        when(tgProviderClient.channels()).thenReturn(List.of(
                channel(7, "VIP 1"),
                channel(8, "VIP 2")));

        List<TgPrivateChannel> channels = service.channels();

        assertThat(channels).extracting(TgPrivateChannel::id).containsExactly(7L, 8L);
        assertThat(channels.getFirst().enabled()).isTrue();
        assertThat(channels.get(1).enabled()).isFalse();
    }

    @Test
    void shouldSaveNormalizedEnabledChannelIds() {
        when(tgProviderClient.channels()).thenReturn(List.of(channel(7, "VIP 1"), channel(9, "VIP 2")));
        when(settingRepository.findById("tg_private_channel_ids")).thenReturn(Optional.of(new Setting("tg_private_channel_ids", "7,9")));

        service.saveChannels(new TgPrivateChannelSelectionRequest(List.of(9L, 7L, 9L, 0L)));

        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("tg_private_channel_ids");
        assertThat(captor.getValue().getValue()).isEqualTo("7,9");
    }

    @Test
    void shouldReturnEmptySearchWhenNoPrivateChannelsAreSelected() {
        when(settingRepository.findById("tg_private_channel_ids")).thenReturn(Optional.empty());

        assertThat(service.search("短剧", 20)).isEmpty();
    }

    @Test
    void shouldSearchEverySelectedPrivateChannelAndMergeResults() {
        when(settingRepository.findById("tg_private_channel_ids")).thenReturn(Optional.of(new Setting("tg_private_channel_ids", "7,9")));
        Message one = Message.fromProvider(1, "vip1", "名称：A", "https://pan.quark.cn/s/a", "2026-06-07T12:00:00Z");
        Message two = Message.fromProvider(2, "vip2", "名称：B", "https://pan.baidu.com/s/1b", "2026-06-07T13:00:00Z");
        when(tgProviderClient.searchMessages("短剧", 20, 7L)).thenReturn(List.of(one));
        when(tgProviderClient.searchMessages("短剧", 20, 9L)).thenReturn(List.of(two));

        List<Message> messages = service.search("短剧", 20);

        assertThat(messages).extracting(Message::getName).containsExactly("B", "A");
    }

    @Test
    void shouldSyncAccountChannelsForEveryProviderAccount() {
        TgProviderAccount account = new TgProviderAccount(1, "tester", "Test", "User", "+86138");
        TgProviderAccountChannelSyncResponse response = new TgProviderAccountChannelSyncResponse("3", "queued", List.of());
        when(tgProviderClient.accounts()).thenReturn(List.of(account));
        when(tgProviderClient.syncAccountChannels(1)).thenReturn(response);

        List<TgProviderAccountChannelSyncResponse> responses = service.syncAccountChannels();

        assertThat(responses).containsExactly(response);
        verify(tgProviderClient).syncAccountChannels(1);
    }

    private TgProviderChannel channel(long id, String title) {
        return new TgProviderChannel(id, 1, 1000 + id, 2000 + id, title, "vip" + id, "channel", 0, null, null, null);
    }
}
