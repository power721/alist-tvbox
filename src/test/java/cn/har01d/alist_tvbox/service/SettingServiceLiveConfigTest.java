package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/** 平台管理保存链路(live_platform_order/live_hidden_platforms):空值(全部平台可见)不得抛错(#1083)。 */
@ExtendWith(MockitoExtension.class)
class SettingServiceLiveConfigTest {
    @Mock SettingRepository settingRepository;

    private SettingService service;
    private AppProperties appProperties;
    private final Map<String, String> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        service = new SettingService(null, null, appProperties, null, null,
                settingRepository, null, new ObjectMapper(), null, null);
        lenient().when(settingRepository.findById(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return store.containsKey(key) ? Optional.of(new Setting(key, store.get(key))) : Optional.empty();
        });
        lenient().when(settingRepository.save(any(Setting.class))).thenAnswer(inv -> {
            Setting setting = inv.getArgument(0);
            store.put(setting.getName(), setting.getValue());
            return setting;
        });
    }

    @Test
    void blankHiddenPlatformsMeansNoneHidden() {
        // #1083:全部平台开启显示时 hidden=[] 落库空串,曾因 parseList 空白返回 null 击穿 String.join 抛 NPE→500
        service.update(new Setting("live_hidden_platforms", ""));

        assertEquals("", store.get("live_hidden_platforms"));
        assertTrue(appProperties.getLiveHiddenPlatforms().isEmpty());
    }

    @Test
    void hiddenPlatformsNormalizedAndApplied() {
        service.update(new Setting("live_hidden_platforms", " huya , ,douyu "));

        assertEquals("huya,douyu", store.get("live_hidden_platforms"));
        assertEquals(List.of("huya", "douyu"), appProperties.getLiveHiddenPlatforms());
    }

    @Test
    void blankPlatformOrderKeepsRegistrationOrder() {
        service.update(new Setting("live_platform_order", ""));

        assertEquals("", store.get("live_platform_order"));
        assertTrue(appProperties.getLivePlatformOrder().isEmpty());
    }
}
