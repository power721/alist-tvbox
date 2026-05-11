package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.service.PluginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PluginControllerTest {
    @Mock
    private PluginService pluginService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PluginController(pluginService))
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void findAllShouldReturnOrderedPlugins() throws Exception {
        Plugin plugin = new Plugin();
        plugin.setId(1);
        plugin.setName("4K指南");
        when(pluginService.findAll()).thenReturn(List.of(plugin));

        mockMvc.perform(get("/api/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("4K指南"));
    }

    @Test
    void reorderShouldForwardIdsToService() throws Exception {
        mockMvc.perform(post("/api/plugins/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(2, 1))))
                .andExpect(status().isOk());

        verify(pluginService).reorder(List.of(2, 1));
    }
}
