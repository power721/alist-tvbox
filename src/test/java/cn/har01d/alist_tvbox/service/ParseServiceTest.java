package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.ParseRequest;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParseServiceTest {
    @Test
    void passesPluginTitleToMountedShareDetail() {
        TvBoxService tvBoxService = mock(TvBoxService.class);
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        ShareService shareService = mock(ShareService.class);
        when(shareService.add(argThat(share -> "https://pan.quark.cn/s/demo".equals(share.getLink()))))
                .thenReturn("/temp/quark@demo@");
        // resolveShareTitle echoes a known title (real impl persists + returns it)
        when(shareService.resolveShareTitle("https://pan.quark.cn/s/demo", "测试剧名"))
                .thenReturn("测试剧名");

        ParseService service = new ParseService(tvBoxService, offlineDownloadService, shareService);
        service.parse(new ParseRequest("https://pan.quark.cn/s/demo", "测试剧名"), "play");

        verify(tvBoxService).getDetail("play", "1$/temp/quark@demo@/~playlist", "测试剧名", null, 0);
    }

    @Test
    void keepsLegacyUrlOnlyRequestsCompatible() {
        TvBoxService tvBoxService = mock(TvBoxService.class);
        OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
        ShareService shareService = mock(ShareService.class);
        when(shareService.add(argThat(share -> "https://pan.quark.cn/s/demo".equals(share.getLink()))))
                .thenReturn("/temp/quark@demo@");

        ParseService service = new ParseService(tvBoxService, offlineDownloadService, shareService);
        service.parse(new ParseRequest("https://pan.quark.cn/s/demo"), "play");

        verify(tvBoxService).getDetail("play", "1$/temp/quark@demo@/~playlist", null, null, 0);
    }
}
