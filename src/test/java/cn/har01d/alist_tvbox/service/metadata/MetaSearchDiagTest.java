package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.TmdbEndpoint;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 诊断(可重复执行,真实网络):逐 provider 搜索"凡人修仙传",定位"只有豆瓣有结果"的原因。
 * Setting mock 为空 → TMDB 走内置公共 key。
 */
@ExtendWith(MockitoExtension.class)
class MetaSearchDiagTest {

    @Mock
    private SettingRepository settingRepository;

    @Test
    @Disabled("真实网络诊断,手动执行:mvn test -Dtest=MetaSearchDiagTest -Dsurefire.failIfNoSpecifiedTests=false -Dtest=... 加 -DskipTests=false 与 @Disabled 移除")
    void searchFanrenXiuxian() {
        when(settingRepository.findById(anyString())).thenReturn(Optional.empty());
        String keyword = "凡人修仙传";

        try {
            List<MetadataSearchItem> bangumi = new BangumiMetadataProvider(new MetadataHttp(null), new MetadataHealth(), null, null, null).search(keyword);
            System.out.println("DIAG bangumi: " + bangumi.size() + (bangumi.isEmpty() ? "" : " 首条=" + bangumi.get(0).getName()));
        } catch (Exception e) {
            System.out.println("DIAG bangumi EX: " + e);
        }
        try {
            List<MetadataSearchItem> tmdb = new TmdbMetadataProvider(new TmdbEndpoint(settingRepository), new MetadataHttp(null), new MetadataHealth(), null, null, null, null).search(keyword);
            System.out.println("DIAG tmdb: " + tmdb.size() + (tmdb.isEmpty() ? "" : " 首条=" + tmdb.get(0).getName()));
        } catch (Exception e) {
            System.out.println("DIAG tmdb EX: " + e);
        }
        try {
            List<MetadataSearchItem> official = new OfficialSiteMetadataProvider(settingRepository, new MetadataHttp(null), new MetadataHealth()).search(keyword);
            System.out.println("DIAG official: " + official.size() + (official.isEmpty() ? "" : " 首条=" + official.get(0).getName()));
        } catch (Exception e) {
            System.out.println("DIAG official EX: " + e);
        }
        try {
            List<MetadataSearchItem> douban = new DoubanMetadataProvider(null, new MetadataHttp(null), new MetadataHealth(), null, null, null, null, null).search(keyword);
            System.out.println("DIAG douban: " + douban.size());
        } catch (Exception e) {
            System.out.println("DIAG douban EX: " + e);
        }
    }
}
