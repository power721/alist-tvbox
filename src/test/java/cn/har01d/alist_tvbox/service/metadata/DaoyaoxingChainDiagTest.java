package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.TmdbEndpoint;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 诊断(可重复执行,真实网络):盗妖行(tmdb 315088,B站独播 周二/四 9:00 更新)全链端到端 ——
 * TMDB 占位标题 → RatingBridge 桥接 Bangumi(608049)→ 分集标题回填/补行 → B站时刻校正(9:00)
 * → bilibili 条目外链。Setting mock 为空 → TMDB 走内置公共 key。
 */
@ExtendWith(MockitoExtension.class)
class DaoyaoxingChainDiagTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private SettingRepository settingRepository;

    @Test
    @Disabled("真实网络诊断,手动执行:临时移除 @Disabled 后 mvn test -Dtest=DaoyaoxingChainDiagTest")
    void fullChainForDaoyaoxing() {
        when(settingRepository.findById(anyString())).thenReturn(Optional.empty());
        MetadataHttp http = new MetadataHttp(null);
        TmdbMetadataProvider provider = new TmdbMetadataProvider(new TmdbEndpoint(settingRepository), http, new MetadataHealth(),
                new RatingBridge(http), new PlayScheduleBridge(http),
                new BilibiliScheduleRefiner(http), new BangumiEpisodeBridge(http));

        MetadataDetails details = provider.details("315088", 1);

        System.out.println("DIAG total=" + details.getTotalEpisodes() + " aired=" + details.getAiredEpisodes()
                + " episodes=" + (details.getEpisodes() == null ? 0 : details.getEpisodes().size())
                + " status=" + details.getStatus());
        System.out.println("DIAG externalIds=" + details.getExternalIds());
        System.out.println("DIAG ratings=" + details.getRatings());
        System.out.println("DIAG nextAirTime=" + zoned(details.getNextAirTime()));
        for (int number : new int[]{1, 41, 42, 57, 60}) {
            details.getEpisodes().stream().filter(info -> info.getNumber() == number).findFirst()
                    .ifPresent(info -> System.out.println("DIAG ep" + number + " title=[" + info.getTitle()
                            + "] air=" + zoned(info.getAirTime())));
        }

        assertNotNull(details.getExternalIds().get("bangumi"), "RatingBridge 应桥接出 Bangumi 条目");
        assertEquals("ss148433", details.getExternalIds().get("bilibili"), "B站条目 id 已登记(详情页 links 用)");
        assertTrue(details.getEpisodes().size() >= 60, "Bangumi 补齐后分集列表覆盖全季");
        assertEquals("来世，你可以找我报仇", titleOf(details, 1), "占位标题被 Bangumi 真实标题回填");
        assertNotNull(titleOf(details, 42), "TMDB 滞后未建的 42+ 集补入");
        ZonedDateTime next = ZonedDateTime.ofInstant(Instant.ofEpochMilli(details.getNextAirTime()), ZONE);
        assertEquals(9, next.getHour(), "下集播出时刻校正为 B站官方 9:00");
    }

    private static String titleOf(MetadataDetails details, int number) {
        return details.getEpisodes().stream().filter(info -> info.getNumber() == number)
                .map(info -> info.getTitle()).findFirst().orElse(null);
    }

    private static String zoned(Long epochMilli) {
        return epochMilli == null ? null : ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZONE).toString();
    }
}
