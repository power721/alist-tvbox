package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.domain.TaskType;
import cn.har01d.alist_tvbox.dto.Index115ShareRef;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Index115ServiceTest {
    @Mock TaskService taskService;
    @Mock SettingRepository settingRepository;
    @Mock DriverAccountRepository driverAccountRepository;
    @Mock Index115VersionClient versionClient;
    @Mock Index115Downloader downloader;
    @Mock Index115Extractor extractor;
    @InjectMocks Index115Service service;

    private Task task;

    @BeforeEach
    void setup() {
        task = new Task();
        task.setId(7);
        lenient().when(taskService.addIndex115Task()).thenReturn(task);
        lenient().when(taskService.isTaskRunning(TaskType.INDEX115)).thenReturn(false);
    }

    @Test
    void skipsWhenShareCodeUnchanged() throws Exception {
        when(versionClient.fetch()).thenReturn(new Index115ShareRef("sw1", "6666"));
        when(settingRepository.findById("index115.share_code")).thenReturn(Optional.of(setting("sw1")));

        service.update();

        verify(downloader, never()).download(anyString(), anyString(), any());
        verify(taskService).completeTask(eq(7), contains("已是最新"), any());
    }

    @Test
    void downloadsExtractsAndPersistsWhenChanged() throws Exception {
        when(versionClient.fetch()).thenReturn(new Index115ShareRef("sw2", "7777"));
        when(settingRepository.findById("index115.share_code")).thenReturn(Optional.empty());

        service.update();

        verify(downloader).download(eq("sw2"), eq("7777"), any(Path.class));
        verify(extractor).extractAndSwap(any(Path.class), any(Path.class));
        verify(settingRepository).save(argThat(s -> "sw2".equals(s.getValue())));
        verify(taskService).completeTask(eq(7), contains("sw2"), any());
    }

    @Test
    void failsTaskWhenVersionMalformed() throws Exception {
        when(versionClient.fetch()).thenReturn(null);

        service.update();

        verify(taskService).failTask(eq(7), anyString());
        verify(downloader, never()).download(anyString(), anyString(), any());
    }

    @Test
    void failsTaskWhenDownloadThrows() throws Exception {
        when(versionClient.fetch()).thenReturn(new Index115ShareRef("sw2", "7777"));
        when(settingRepository.findById("index115.share_code")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("boom")).when(downloader).download(anyString(), anyString(), any());

        service.update();

        verify(extractor, never()).extractAndSwap(any(Path.class), any(Path.class));
        verify(taskService).failTask(eq(7), contains("boom"));
    }

    @Test
    void has115AccountTrueWhenPan115MasterExists() {
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.of(new DriverAccount()));
        assertTrue(service.has115Account());
    }

    @Test
    void has115AccountFalseWhenNone() {
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.empty());
        assertFalse(service.has115Account());
    }

    private Setting setting(String value) {
        Setting s = new Setting();
        s.setName("index115.share_code");
        s.setValue(value);
        return s;
    }
}
