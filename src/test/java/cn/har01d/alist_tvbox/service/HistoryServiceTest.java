package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.DeviceRepository;
import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.entity.HistoryRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {
    private static final int UID = 1;

    @Mock
    private HistoryRepository historyRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private AppProperties appProperties;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private RestTemplateBuilder restTemplateBuilder;
    @Mock
    private RestTemplate restTemplate;

    private HistoryService service;

    @BeforeEach
    void setUp() {
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        service = new HistoryService(historyRepository, deviceRepository, settingRepository, appProperties,
                subscriptionService, new ObjectMapper(), restTemplateBuilder);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("user", "password");
        authentication.setDetails(UID);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void webListOnlyReturnsLegacyAlistHistory() {
        Pageable pageable = Pageable.ofSize(20);
        when(historyRepository.findByUidAndSourceKindIsNull(UID, pageable)).thenReturn(Page.empty(pageable));

        assertThat(service.list(pageable)).isEmpty();

        verify(historyRepository).findByUidAndSourceKindIsNull(UID, pageable);
    }

    @Test
    void duplicateLegacyKeyReturnsNewestWithoutSingleResultQuery() {
        History older = history(10, 100);
        History newer = history(11, 200);
        when(historyRepository.findAllByUidAndSourceKindIsNullAndKey(UID, "movie"))
                .thenReturn(List.of(older, newer));

        assertThat(service.findById("movie")).isSameAs(newer);
    }

    @Test
    void savingLegacyHistoryCollapsesExistingDuplicateKeys() {
        History older = history(10, 100);
        History newer = history(11, 200);
        History incoming = history(null, 300);
        incoming.setSourceKind("spider_plugin");
        when(historyRepository.findAllByUidAndSourceKindIsNullAndKey(UID, "movie"))
                .thenReturn(List.of(older, newer));
        when(historyRepository.save(any(History.class))).thenAnswer(invocation -> invocation.getArgument(0));

        History saved = service.save(incoming);

        assertThat(saved.getId()).isEqualTo(11);
        assertThat(saved.getSourceKind()).isNull();
        verify(historyRepository).deleteAll(List.of(older));
    }

    private History history(Integer id, long createTime) {
        History history = new History();
        history.setId(id);
        history.setUid(UID);
        history.setKey("movie");
        history.setCreateTime(createTime);
        return history;
    }
}
