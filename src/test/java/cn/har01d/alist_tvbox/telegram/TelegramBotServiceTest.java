package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.telegram.BotMessage;
import cn.har01d.alist_tvbox.dto.telegram.BotUpdate;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.service.SettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮询生命周期:重启/换 token 的积压丢弃走队尾快照原子确认 —— 离线旧命令全弃、
 * 重启后用户发的第一条命令(落在旧实现被整批跳过的首批里)必须照常分发。
 */
class TelegramBotServiceTest {

    private final TelegramBotClient client = mock(TelegramBotClient.class);
    private final TelegramUpdateRouter router = mock(TelegramUpdateRouter.class);
    private final SettingService settingService = mock(SettingService.class);
    private TelegramBotService service;

    private BotUpdate update(int id) {
        BotUpdate update = new BotUpdate();
        update.setUpdateId(id);
        BotMessage message = new BotMessage();
        message.setText("/start");
        update.setMessage(message);
        return update;
    }

    private void startWithToken() {
        when(settingService.get(TelegramBotService.TOKEN_KEY))
                .thenReturn(new Setting(TelegramBotService.TOKEN_KEY, "T"));
        service = new TelegramBotService(client, router, settingService);
        service.start();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void firstCommandAfterRestartIsDispatched() throws Exception {
        // 重启后无积压:第一条命令(首轮 25s 长轮询返回的那条)必须被分发,不能当积压吞掉
        when(client.tailUpdates("T")).thenReturn(List.of());
        BotUpdate first = update(10);
        CountDownLatch dispatched = new CountDownLatch(1);
        doAnswer(inv -> {
            dispatched.countDown();
            return null;
        }).when(router).dispatch(anyString(), any());
        when(client.getUpdates("T", 0)).thenReturn(List.of(first));

        startWithToken();

        assertTrue(dispatched.await(5, TimeUnit.SECONDS), "重启后第一条命令未被分发");
        verify(router).dispatch(eq("T"), same(first));
    }

    @Test
    void offlineBacklogDroppedBeforeLivePolling() throws Exception {
        // 离线积压 update 1-5:队尾快照(offset=-1)确认到 6,活轮询从 offset 6 起,积压永不执行
        when(client.tailUpdates("T")).thenReturn(List.of(update(5)));
        BotUpdate fresh = update(6);
        CountDownLatch dispatched = new CountDownLatch(1);
        doAnswer(inv -> {
            dispatched.countDown();
            return null;
        }).when(router).dispatch(anyString(), any());
        when(client.getUpdates("T", 6)).thenReturn(List.of(fresh));

        startWithToken();

        assertTrue(dispatched.await(5, TimeUnit.SECONDS));
        verify(router).dispatch(eq("T"), same(fresh));
        verify(client, never()).getUpdates(eq("T"), longThat(offset -> offset <= 5));
    }
}
