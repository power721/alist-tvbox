package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.auth.TokenService;
import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.config.WebSecurityConfiguration;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.PluginCompilerService;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.SecspiderKeyService;
import cn.har01d.alist_tvbox.service.SelfPluginFileService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig
@ContextConfiguration(classes = PluginControllerSecurityTest.TestConfig.class)
class PluginControllerSecurityTest {
    private static final String API_KEY = "compile-api-key";

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TokenFilter tokenFilter;

    @Autowired
    private SecspiderKeyService secspiderKeyService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @BeforeEach
    void setUp() {
        tokenFilter.setApiKey(API_KEY);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void compileSecspiderShouldRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/plugins/compile/secspider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void compileSecspiderShouldWorkThroughApiKeySecurityChain() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String privateKey = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String publicKey = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        when(secspiderKeyService.compilerKeyMaterial())
                .thenReturn(new SecspiderKeyService.CompilerKeyMaterial(privateKey, publicKey, "secured-http-master-secret"));

        mockMvc.perform(post("/api/plugins/compile/secspider")
                        .header("X-API-KEY", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "name", "SecuredHttpInterop",
                                "version", 1,
                                "remark", "",
                                "source", "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("secspider/1"))
                .andExpect(jsonPath("$.kid").value("self"))
                .andExpect(jsonPath("$.packageText").value(containsString("//@format:secspider/1")))
                .andExpect(jsonPath("$.packageText").value(not(containsString(privateKey))))
                .andExpect(jsonPath("$.publicKeyChunks").isArray())
                .andExpect(jsonPath("$.masterSecretChunks").isArray());
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(NamedParameterSpec.ED25519);
        return generator.generateKeyPair();
    }

    private String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(bytes)
                + "\n-----END " + type + "-----";
    }

    @Configuration
    @EnableWebMvc
    @Import(WebSecurityConfiguration.class)
    static class TestConfig {
        @Bean
        PluginController pluginController(PluginService pluginService,
                                          PluginCompilerService pluginCompilerService,
                                          SecspiderKeyService secspiderKeyService,
                                          SelfPluginFileService selfPluginFileService) {
            return new PluginController(pluginService, pluginCompilerService, secspiderKeyService, selfPluginFileService);
        }

        @Bean
        PluginCompilerService pluginCompilerService() {
            return new PluginCompilerService();
        }

        @Bean
        PluginService pluginService() {
            return mock(PluginService.class);
        }

        @Bean
        SecspiderKeyService secspiderKeyService() {
            return mock(SecspiderKeyService.class);
        }

        @Bean
        SelfPluginFileService selfPluginFileService() {
            return mock(SelfPluginFileService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        RestErrorHandler restErrorHandler() {
            return new RestErrorHandler();
        }

        @Bean
        TokenFilter tokenFilter(TokenService tokenService, SettingRepository settingRepository) {
            return new TokenFilter(tokenService, settingRepository);
        }

        @Bean
        TokenService tokenService() {
            return mock(TokenService.class);
        }

        @Bean
        SubscriptionService subscriptionService() {
            return mock(SubscriptionService.class);
        }

        @Bean
        SettingRepository settingRepository() {
            SettingRepository repository = mock(SettingRepository.class);
            when(repository.findById("api_key")).thenReturn(Optional.empty());
            when(repository.findById("basic_auth_username")).thenReturn(Optional.empty());
            when(repository.findById("basic_auth_password")).thenReturn(Optional.empty());
            return repository;
        }
    }
}
