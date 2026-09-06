package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.LiveFollowRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class TelemetryServiceTest {

    private AppProperties appProperties;
    private SettingRepository repository;
    private JdbcTemplate alistJdbcTemplate;
    private MediaSubscriptionRepository mediaSubscriptionRepository;
    private LiveFollowRepository liveFollowRepository;
    private UserRepository userRepository;
    private MockEnvironment environment;
    private TelemetryService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setSystemId("0f1e2d3c-4b5a-6978-8776-655443332211");
        repository = mock(SettingRepository.class);
        when(repository.save(any(Setting.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        alistJdbcTemplate = mock(JdbcTemplate.class);
        mediaSubscriptionRepository = mock(MediaSubscriptionRepository.class);
        liveFollowRepository = mock(LiveFollowRepository.class);
        userRepository = mock(UserRepository.class);
        environment = new MockEnvironment()
                .withProperty("spring.datasource.jdbc-url", "jdbc:h2:file:/tmp/x/data")
                .withProperty("os.arch", "aarch64");
        service = new TelemetryService(appProperties, repository, alistJdbcTemplate,
                mediaSubscriptionRepository, liveFollowRepository, userRepository, environment);
        setField(service, "reportUrl", "http://127.0.0.1:1/telemetry/report");
        setField(service, "enabled", true);
        setField(service, "jitterMaxMinutes", 0);
    }

    private void setting(String key, String value) {
        when(repository.findById(key)).thenReturn(Optional.of(new Setting(key, value)));
    }

    @Test
    void payloadCarriesCoreAndTierAFields() throws Exception {
        setting("app_version", "2024.245");
        setting("install_mode", "host");
        setting("alist_version", "3.45.0");
        when(alistJdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("Quark", "BaiduPan", "local"));
        when(mediaSubscriptionRepository.count()).thenReturn(2L);
        when(liveFollowRepository.count()).thenReturn(5L);
        when(userRepository.count()).thenReturn(1L);

        JsonNode node = new ObjectMapper().readTree(service.buildPayload());

        assertThat(node.get("v").asInt()).isEqualTo(1);
        assertThat(node.get("systemId").asText()).isEqualTo(appProperties.getSystemId());
        assertThat(node.get("version").asText()).isEqualTo("2024.245");
        assertThat(node.get("mode").asText()).isEqualTo("host");
        assertThat(node.get("runtime").asText()).isEqualTo("jvm");
        assertThat(node.get("arch").asText()).isEqualTo("arm64");
        assertThat(node.get("db").asText()).isEqualTo("h2");
        assertThat(node.get("alist").asText()).isEqualTo("3.45.0");
        assertThat(node.get("drivers").asText()).isEqualTo("baidupan,local,quark");
        assertThat(node.get("features").asText()).isEqualTo("sub,live");
        assertThat(node.get("subs").asText()).isEqualTo("1-3");
    }

    @Test
    void payloadBucketsAndFlagsFallBackSafely() throws Exception {
        when(alistJdbcTemplate.queryForList(anyString(), eq(String.class))).thenThrow(new RuntimeException("db down"));
        when(mediaSubscriptionRepository.count()).thenReturn(60L);
        when(liveFollowRepository.count()).thenThrow(new RuntimeException("db down"));
        when(userRepository.count()).thenReturn(3L);

        JsonNode node = new ObjectMapper().readTree(service.buildPayload());

        assertThat(node.get("drivers").asText()).isEmpty();
        assertThat(node.get("subs").asText()).isEqualTo("50+");
        // 订阅数 60 同时点亮 sub 标志
        assertThat(node.get("features").asText()).isEqualTo("sub,multiuser");
        assertThat(node.get("db").asText()).isEqualTo("h2");
    }

    @Test
    void nativeRuntimeDetectedFromEnv() throws Exception {
        environment.setProperty("NATIVE", "true");
        JsonNode node = new ObjectMapper().readTree(service.buildPayload());
        assertThat(node.get("runtime").asText()).isEqualTo("native");
    }

    @Test
    void shouldReportFalseWhenUrlBlank() {
        // 源码自构建默认无 URL:完全静默
        setField(service, "reportUrl", "");
        assertThat(service.shouldReport()).isFalse();
        setField(service, "reportUrl", "  ");
        assertThat(service.shouldReport()).isFalse();
    }

    @Test
    void shouldReportFalseWhenSamePayloadAndRecentlyReported() {
        // 心跳路径:时间新鲜且载荷无变化 → 跳过(崩溃循环保护)
        setting("telemetry_report_time", String.valueOf(System.currentTimeMillis()));
        setting("telemetry_report_payload", service.buildPayload());
        assertThat(service.shouldReport()).isFalse();
    }

    @Test
    void shouldReportTrueWhenPayloadChangedEvenIfFresh() {
        // 版本更新旁路:时间新鲜但载荷变了 → 立即上报
        setting("telemetry_report_time", String.valueOf(System.currentTimeMillis()));
        setting("telemetry_report_payload", "{\"v\":1,\"systemId\":\"old\"}");
        assertThat(service.shouldReport()).isTrue();
    }

    @Test
    void shouldReportTrueWhenIntervalElapsed() {
        // 无任何记录:首启心跳
        assertThat(service.shouldReport()).isTrue();
    }

    @Test
    void shouldReportFalseWhenDisabledOrMissingSystemId() {
        setField(service, "enabled", false);
        assertThat(service.shouldReport()).isFalse();

        setField(service, "enabled", true);
        appProperties.setSystemId(null);
        assertThat(service.shouldReport()).isFalse();
    }

    @Test
    void reportRecordsTimestampAndPayloadOnlyOnSuccess() {
        // 指向不可达地址:失败路径只记时间戳,不记载荷(下轮因载荷"变化"自然重试)
        service.report();

        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Setting::getName)
                .containsExactly("telemetry_report_time");
    }

    @Test
    void tickDoesNothingWhenGated() {
        setField(service, "enabled", false);
        service.tick();
        verify(repository, never()).save(any(Setting.class));
    }
}
