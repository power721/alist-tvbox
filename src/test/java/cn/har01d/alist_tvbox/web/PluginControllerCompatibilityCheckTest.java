package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.service.PluginCompilerService;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.SecspiderKeyService;
import cn.har01d.alist_tvbox.service.SelfPluginFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PluginControllerCompatibilityCheckTest {
    @Mock
    private PluginService pluginService;
    @Mock
    private SecspiderKeyService secspiderKeyService;
    @Mock
    private SelfPluginFileService selfPluginFileService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PluginController controller = new PluginController(
                pluginService,
                new PluginCompilerService(),
                secspiderKeyService,
                selfPluginFileService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void checkSecspiderCompatibilityShouldReturnReport() throws Exception {
        mockMvc.perform(post("/api/plugins/compatibility-check/secspider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "name", "GateDemo",
                                "version", 1,
                                "remark", "",
                                "source", "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateName").value("磁力爬虫门禁"))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.summary").value(containsString("不合规")))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].status").value(not("")))
                .andExpect(jsonPath("$.aiRepairExportText").value(containsString("AI修复导出")));
    }
}
