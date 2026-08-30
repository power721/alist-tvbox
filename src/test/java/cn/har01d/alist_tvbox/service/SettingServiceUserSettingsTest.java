package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用户级设置键级命名空间({key}:u{uid})与「用户值→全局值」回退链(§3.1)。 */
@ExtendWith(MockitoExtension.class)
class SettingServiceUserSettingsTest {
    @Mock SettingRepository settingRepository;

    private SettingService service;
    private final Map<String, String> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new SettingService(null, null, new AppProperties(), null, null,
                settingRepository, null, new ObjectMapper(), null, null);
        lenient().when(settingRepository.findById(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return store.containsKey(key) ? Optional.of(new Setting(key, store.get(key))) : Optional.empty();
        });
        lenient().when(settingRepository.existsByName(any())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        lenient().when(settingRepository.save(any(Setting.class))).thenAnswer(inv -> {
            Setting setting = inv.getArgument(0);
            store.put(setting.getName(), setting.getValue());
            return setting;
        });
        lenient().doAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return null;
        }).when(settingRepository).deleteById(any());
    }

    @Test
    void userValueTakesPrecedenceAndFallsBackToGlobal() {
        store.put("msub_telegram_bot_token", "global-token");
        assertEquals("global-token", service.getUserSetting("msub_telegram_bot_token", 5));

        store.put("msub_telegram_bot_token:u5", "user-token");
        assertEquals("user-token", service.getUserSetting("msub_telegram_bot_token", 5));
        // 其它用户不受影响
        assertEquals("global-token", service.getUserSetting("msub_telegram_bot_token", 6));
        // uid<=0(全局口径)读全局键
        assertEquals("global-token", service.getUserSetting("msub_telegram_bot_token", 0));
    }

    @Test
    void blankUserValueFallsBackToGlobal() {
        store.put("msub_telegram_bot_token", "global-token");
        store.put("msub_telegram_bot_token:u5", "");
        assertEquals("global-token", service.getUserSetting("msub_telegram_bot_token", 5));
    }

    @Test
    void saveUserSettingOnlyWritesUserRow() {
        store.put("msub_telegram_bot_token", "global-token");

        service.saveUserSetting("msub_telegram_bot_token", 5, "user-token");

        assertEquals("user-token", store.get("msub_telegram_bot_token:u5"));
        assertEquals("global-token", store.get("msub_telegram_bot_token"), "全局键不得被用户级写入覆盖");
        assertTrue(service.hasUserSetting("msub_telegram_bot_token", 5));
        assertFalse(service.hasUserSetting("msub_telegram_bot_token", 6));
    }

    @Test
    void saveBlankUserSettingDeletesOverride() {
        store.put("msub_telegram_bot_token", "global-token");
        store.put("msub_telegram_bot_token:u5", "user-token");

        service.saveUserSetting("msub_telegram_bot_token", 5, "");

        assertFalse(store.containsKey("msub_telegram_bot_token:u5"));
        assertEquals(Optional.empty(), settingRepository.findById("msub_telegram_bot_token:u5"));
    }

    @Test
    void nonWhitelistedKeyRejected() {
        assertThrows(BadRequestException.class, () -> service.saveUserSetting("api_key", 5, "x"));
        verify(settingRepository, never()).save(any());
    }

    @Test
    void poolFilterUserValueIsNormalized() {
        String raw = "{\"minQuality\":\"uhd\",\"includeKeywords\":[\"国语\"],\"minEpisodeSizeMb\":200}";
        service.saveUserSetting("msub_pool_filter", 5, raw);
        String stored = store.get("msub_pool_filter:u5");
        assertTrue(stored.contains("excludeKeywords"), "归一化后写回完整钳位配置");
        assertTrue(stored.contains("uhd"));
    }
}
