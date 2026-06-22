package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.Index115File;
import cn.har01d.alist_tvbox.dto.Index115SearchData;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Index115TvBoxAdapterTest {
    @Mock Index115Client client;
    @Mock ProxyService proxyService;
    @Mock DriverAccountRepository driverAccountRepository;
    Index115TvBoxAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new Index115TvBoxAdapter(client, proxyService, driverAccountRepository);
    }

    private Site site() {
        Site s = new Site();
        s.setId(9);
        s.setStorageVersion(1);
        return s;
    }

    @Test
    void listRootBuildsShareCategories() {
        Site s = site();
        when(client.browse(s, "", "", "")).thenReturn(List.of(
                file("", "sw1", "6666", "Lib", true),
                file("", "sw2", "7777", "Films", true)));
        when(proxyService.generatePath(eq(s), anyString())).thenReturn(101, 102);

        var ml = adapter.list(s, "/", 1, 100);

        assertEquals(2, ml.getList().size());
        assertEquals("Lib", ml.getList().get(0).getVod_name());
        assertEquals("9$101$1", ml.getList().get(0).getVod_id());
        verify(proxyService).generatePath(s, Index115PathCodec.shareRoot("sw1", "6666"));
    }

    @Test
    void listShareRootUsesParentZero() {
        Site s = site();
        when(client.browse(s, "sw1", "6666", "0")).thenReturn(List.of(
                file("d1", "sw1", "6666", "Dir", true),
                file("f1", "sw1", "6666", "a.mkv", false)));
        when(proxyService.generatePath(eq(s), anyString())).thenReturn(1, 2);

        var ml = adapter.list(s, Index115PathCodec.shareRoot("sw1", "6666"), 1, 100);

        verify(client).browse(s, "sw1", "6666", "0");
        assertEquals(2, ml.getList().size());
    }

    @Test
    void playResolvesLinkWithMasterPan115Cookie() {
        Site s = site();
        DriverAccount acc = new DriverAccount();
        acc.setCookie("CK");
        when(driverAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115)).thenReturn(Optional.of(acc));
        when(client.resolveLink(s, "CK", "sw1", "6666", "f1")).thenReturn("http://play/x");

        Map<String, Object> result = adapter.play(s, Index115PathCodec.child("sw1", "6666", "f1"));

        assertEquals("http://play/x", result.get("url"));
        assertEquals(DriverType.PAN115, result.get("type"));
        assertEquals(0, result.get("parse"));
    }

    @Test
    void searchMapsItems() {
        Site s = site();
        Index115SearchData data = new Index115SearchData();
        data.setTotal(1);
        data.setItems(List.of(file("f1", "sw1", "6666", "a.mkv", false)));
        when(client.search(s, "foo", 1, anyInt())).thenReturn(data);
        when(proxyService.generatePath(eq(s), anyString())).thenReturn(5);

        List<MovieDetail> list = adapter.search(s, "foo");

        assertEquals(1, list.size());
        assertEquals("a.mkv", list.get(0).getVod_name());
        assertEquals("9$5$1", list.get(0).getVod_id());
    }

    private Index115File file(String fileId, String sc, String rc, String name, boolean dir) {
        Index115File f = new Index115File();
        f.setFileId(fileId);
        f.setShareCode(sc);
        f.setReceiveCode(rc);
        f.setName(name);
        f.setDir(dir);
        return f;
    }
}
