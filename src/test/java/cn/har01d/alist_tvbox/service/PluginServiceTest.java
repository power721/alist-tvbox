package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class PluginServiceTest {
    @Mock
    private PluginRepository pluginRepository;
    @Mock
    private RestTemplate restTemplate;
    @Spy
    private RestTemplateBuilder builder = new RestTemplateBuilder();
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PluginService pluginService;

    @Test
    void createShouldDefaultNameFromEncodedFilename() {
        assertThatThrownBy(() -> pluginService.create(new Plugin()))
                .isInstanceOf(NullPointerException.class);
    }
}
