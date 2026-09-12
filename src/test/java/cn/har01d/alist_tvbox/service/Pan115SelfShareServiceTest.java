package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.model.ShareCreateData;
import cn.har01d.alist_tvbox.entity.Site;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 115 自有分享执行器:目标账号解析(订阅目标优先、开放平台排除、master 兜底)、
 * 批次转存目录规格(与 TRANSFER 同根)、建分享空码上抛、删源逐文件提交。
 */
class Pan115SelfShareServiceTest {

    private final AListService aListService = mock(AListService.class);
    private final DriverAccountRepository accountRepository = mock(DriverAccountRepository.class);
    private final SettingRepository settingRepository = mock(SettingRepository.class);

    private Pan115SelfShareService service;

    @BeforeEach
    void setUp() {
        service = new Pan115SelfShareService(aListService, accountRepository, settingRepository, new ObjectMapper());
        when(settingRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    private static DriverAccount account(int id, DriverType type) {
        DriverAccount account = new DriverAccount();
        account.setId(id);
        account.setType(type);
        account.setName("号" + id);
        return account;
    }

    private static MediaSubscription subscription(String accountIds) {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(9);
        subscription.setName("如果奔跑是我的人生");
        subscription.setAccountIds(accountIds);
        return subscription;
    }

    /** 订阅转存目标里的 cookie 版 PAN115 优先(开放平台账号无分享 API,跳过)。 */
    @Test
    void resolveAccountPrefersCookiePan115FromTargets() {
        when(accountRepository.findById(7)).thenReturn(Optional.of(account(7, DriverType.OPEN115)));
        when(accountRepository.findById(5)).thenReturn(Optional.of(account(5, DriverType.PAN115)));
        DriverAccount resolved = service.resolveAccount(subscription("[\"pan:7\",\"pan:5\"]"));
        assertEquals(5, resolved.getId());
    }

    /** 订阅目标没有 115:回退 master PAN115。 */
    @Test
    void resolveAccountFallsBackToMaster() {
        when(accountRepository.findById(4)).thenReturn(Optional.of(account(4, DriverType.QUARK)));
        when(accountRepository.findByTypeAndMasterTrue(DriverType.PAN115))
                .thenReturn(Optional.of(account(11, DriverType.PAN115)));
        DriverAccount resolved = service.resolveAccount(subscription("[\"pan:4\"]"));
        assertEquals(11, resolved.getId());
    }

    /** 既无订阅目标也无 master:返回 null(调用方记事件跳过)。 */
    @Test
    void resolveAccountAbsent() {
        when(accountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.empty());
        assertNull(service.resolveAccount(subscription(null)));
    }

    /** 批次目录规格:{账号挂载根}/{我的追剧}/{剧名 季 tag};msub_transfer_root 可覆盖。 */
    @Test
    void targetDirLayout() {
        DriverAccount account = account(5, DriverType.PAN115);
        MediaSubscription subscription = subscription(null);
        subscription.setSeason(2);
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("84958");
        assertEquals("/115云盘/号5/我的追剧/如果奔跑是我的人生-第2季 [tmdbid-84958]",
                service.targetDir(subscription, account));

        when(settingRepository.findById("msub_transfer_root"))
                .thenReturn(Optional.of(new Setting("msub_transfer_root", "追剧固化")));
        assertEquals("/115云盘/号5/追剧固化/如果奔跑是我的人生-第2季 [tmdbid-84958]",
                service.targetDir(subscription, account));
    }

    /** link 格式:115.com/s/{code}?password={rc},与 ShareService 分享解析口径一致。 */
    @Test
    void shareCreatedLinkFormat() {
        var share = new Pan115SelfShareService.ShareCreated("swsexqo3hjs", "6666",
                "https://115cdn.com/s/swsexqo3hjs", "剧名");
        assertEquals("https://115.com/s/swsexqo3hjs?password=6666", share.link());
        var noCode = new Pan115SelfShareService.ShareCreated("abc", "", "u", "t");
        assertEquals("https://115.com/s/abc", noCode.link());
    }

    /** 建分享无 share_code 上抛(驱动端点异常形态)。 */
    @Test
    void createShareRequiresCode() {
        when(aListService.createShare(any(), anyString())).thenReturn(new ShareCreateData());
        assertThrows(IllegalStateException.class,
                () -> service.createShare(new Site(), "/115云盘/号5/我的追剧/剧名"));
    }

    /** 建分享成功透传字段。 */
    @Test
    void createSharePassthrough() {
        ShareCreateData data = new ShareCreateData();
        data.setShareCode("swsexqo3hjs");
        data.setReceiveCode("6666");
        data.setShareUrl("https://115cdn.com/s/swsexqo3hjs");
        data.setShareTitle("剧名 (2024)");
        when(aListService.createShare(any(), anyString())).thenReturn(data);
        var share = service.createShare(new Site(), "/dir");
        assertEquals("swsexqo3hjs", share.shareCode());
        assertEquals("6666", share.receiveCode());
        assertEquals("剧名 (2024)", share.shareTitle());
    }

    /** 转存分批:11 个对象拆 2 批(≤10/批,与 TRANSFER 同款)。 */
    @Test
    void transferObjectsBatches() {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            names.add("第" + i + "集.mkv");
        }
        service.transferObjects(new Site(), "/src", names, "/dst");
        verify(aListService, times(2)).shareSave(any(), eq("/src"), anyList(), eq("/dst"));
    }

    /** 删源:目录下全部文件逐个提交 remove。 */
    @Test
    void removeAllSubmitsPerFile() {
        service.removeAll(new Site(), "/115云盘/号5/我的追剧/剧名", List.of("第1集.mkv", "第2集.mkv"));
        verify(aListService).remove(any(), eq("/115云盘/号5/我的追剧/剧名/第1集.mkv"));
        verify(aListService).remove(any(), eq("/115云盘/号5/我的追剧/剧名/第2集.mkv"));
    }

    /** mkdir 已存在容忍(AList 500),listNames 空目录返回空。 */
    @Test
    void prepareDirToleratesExistingAndListNames() {
        org.mockito.Mockito.doThrow(new BadRequestException("already exists"))
                .when(aListService).mkdir(any(), anyString());
        when(aListService.listFiles(any(), anyString(), anyInt(), anyInt())).thenReturn(new FsResponse());
        Site site = new Site();
        service.prepareDir(site, "/dir");
        assertTrue(service.listNames(site, "/dir").isEmpty());
        verify(aListService, never()).remove(any(), anyString());
    }

    /** listNames 取目录对象名。 */
    @Test
    void listNamesReadsContent() {
        FsResponse response = new FsResponse();
        FsInfo info = new FsInfo();
        info.setName("第1集.mkv");
        response.getContent().add(info);
        when(aListService.listFiles(any(), anyString(), anyInt(), anyInt())).thenReturn(response);
        assertEquals(List.of("第1集.mkv"), service.listNames(new Site(), "/dir"));
    }
}
