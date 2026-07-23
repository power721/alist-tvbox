package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.Index115CheckResult;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoUpdateServiceTest {
    @Mock AutoUpdateExecutor executor;
    @Mock SubscriptionService subscriptionService;
    @Mock IndexService indexService;
    @Mock DoubanService doubanService;
    @Mock Index115Service index115Service;
    @InjectMocks AutoUpdateService service;

    @Test
    void autoSyncCatDelegatesWithJitter() {
        service.autoSyncCat();
        verify(executor).scheduleWithJitter(any(Runnable.class));
    }

    @Test
    void autoIndexDelegatesWithJitter() {
        service.autoIndex();
        verify(executor).scheduleWithJitter(any(Runnable.class));
    }

    @Test
    void autoDoubanDelegatesWithJitter() {
        service.autoDouban();
        verify(executor).scheduleWithJitter(any(Runnable.class));
    }

    @Test
    void update115RunsUpdateWhenHasUpdate() {
        when(index115Service.check()).thenReturn(new Index115CheckResult(true, true, "a", "b", null));

        service.update115();

        verify(index115Service).update();
    }

    @Test
    void update115SkipsWhenNoUpdate() {
        when(index115Service.check()).thenReturn(new Index115CheckResult(true, false, "a", "a", null));

        service.update115();

        verify(index115Service, never()).update();
    }

    @Test
    void update115SwallowsBadRequest() {
        when(index115Service.check()).thenReturn(new Index115CheckResult(true, true, "a", "b", null));
        doThrow(new BadRequestException("running")).when(index115Service).update();

        assertDoesNotThrow(() -> service.update115());
    }
}
