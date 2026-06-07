package cn.har01d.alist_tvbox.nativeimage;

import cn.har01d.alist_tvbox.dto.tg.TelegramLoginRequest;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccount;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccountChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannel;
import cn.har01d.alist_tvbox.dto.tg.TgProviderChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderLink;
import cn.har01d.alist_tvbox.dto.tg.TgProviderLoginResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSearchItem;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSearchResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderStatus;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResult;
import cn.har01d.alist_tvbox.dto.tg.TgProviderWebAccessCheckItem;
import cn.har01d.alist_tvbox.dto.tg.TgWatchRule;
import cn.har01d.alist_tvbox.dto.tg.TgWatchRuleRequest;
import cn.har01d.alist_tvbox.dto.tg.TgWatchRulesResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NativeReflectConfigTest {
    private static final String BUILTIN_SOURCE_STATE_CLASS =
            "cn.har01d.alist_tvbox.model.BuiltinSubscriptionSourceState";
    private static final List<String> TG_PROVIDER_DTO_CLASSES = Arrays.asList(
            TgProviderStatus.class.getName(),
            TgProviderAccount.class.getName(),
            TgProviderLoginResponse.class.getName(),
            TgProviderSearchResponse.class.getName(),
            TgProviderSearchItem.class.getName(),
            TgProviderLink.class.getName(),
            TgProviderChannel.class.getName(),
            TgPrivateChannel.class.getName(),
            TgPrivateChannelSelectionRequest.class.getName(),
            TgProviderAccountChannelSyncResponse.class.getName(),
            TgProviderChannelSyncResponse.class.getName(),
            TgProviderSyncResponse.class.getName(),
            TgProviderSyncResult.class.getName(),
            TgProviderWebAccessCheckItem.class.getName(),
            TgWatchRule.class.getName(),
            TgWatchRuleRequest.class.getName(),
            TgWatchRulesResponse.class.getName(),
            TelegramLoginRequest.class.getName());

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void nativeReflectConfigShouldIncludeBuiltinSubscriptionSourceState() throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream("/META-INF/native-image/reflect-config.json")) {
            assertThat(inputStream).isNotNull();
            List<Map<String, Object>> entries = objectMapper.readValue(inputStream, new TypeReference<>() {
            });

            assertThat(entries)
                    .extracting(entry -> entry.get("name"))
                    .contains(BUILTIN_SOURCE_STATE_CLASS);
        }
    }

    @Test
    void nativeReflectConfigShouldIncludeTelegramProviderDtos() throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream("/META-INF/native-image/reflect-config.json")) {
            assertThat(inputStream).isNotNull();
            List<Map<String, Object>> entries = objectMapper.readValue(inputStream, new TypeReference<>() {
            });

            assertThat(entries)
                    .extracting(entry -> entry.get("name"))
                    .containsAll(TG_PROVIDER_DTO_CLASSES);
        }
    }
}
