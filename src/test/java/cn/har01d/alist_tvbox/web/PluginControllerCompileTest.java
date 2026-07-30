package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.entity.Plugin;
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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PluginControllerCompileTest {
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
    void compileSecspiderShouldReturnPackageWithoutPrivateKey() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String privateKey = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String publicKey = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());

        mockMvc.perform(post("/api/plugins/compile/secspider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "name", "HttpInterop",
                                "version", 1,
                                "remark", "",
                                "id", "http-interop",
                                "kid", "http-kid",
                                "source", "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n",
                                "privateKey", privateKey,
                                "publicKey", publicKey,
                                "masterSecret", "http-master-secret"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("secspider/1"))
                .andExpect(jsonPath("$.packageText").value(containsString("//@format:secspider/1")))
                .andExpect(jsonPath("$.packageText").value(not(containsString(privateKey))))
                .andExpect(jsonPath("$.publicKeyChunks").isArray())
                .andExpect(jsonPath("$.masterSecretChunks").isArray());
    }

    @Test
    void compileSecspiderShouldUseManagedKeyAndAutoImportWithoutManualFields() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String privateKey = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String publicKey = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        when(secspiderKeyService.compilerKeyMaterial())
                .thenReturn(new SecspiderKeyService.CompilerKeyMaterial(privateKey, publicKey, "managed-master-secret"));
        when(selfPluginFileService.store(eq("manageddemo"), eq(2), anyString(), anyString()))
                .thenReturn(new SelfPluginFileService.StoredPlugin(
                        "/static/self-plugins/py/manageddemo.txt",
                        "/static/self-plugins/spiders_v2.json",
                        "http://localhost/static/self-plugins/py/manageddemo.txt",
                        "http://localhost/static/self-plugins/spiders_v2.json",
                        "/www/static/self-plugins/py/manageddemo.txt"
                ));
        Plugin plugin = new Plugin();
        plugin.setId(77);
        plugin.setName("ManagedDemo");
        when(pluginService.upsertCompiledPlugin(
                eq("http://localhost/static/self-plugins/py/manageddemo.txt"),
                eq("/www/static/self-plugins/py/manageddemo.txt"),
                eq("manageddemo"),
                eq("ManagedDemo"),
                eq(2),
                anyString()
        )).thenReturn(plugin);

        mockMvc.perform(post("/api/plugins/compile/secspider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "name", "ManagedDemo",
                                "version", 2,
                                "remark", "",
                                "source", "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n",
                                "autoImport", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("secspider/1"))
                .andExpect(jsonPath("$.kid").value("self"))
                .andExpect(jsonPath("$.packageText").value(not(containsString(privateKey))))
                .andExpect(jsonPath("$.importedPluginId").value(77))
                .andExpect(jsonPath("$.importedPluginName").value("ManagedDemo"))
                .andExpect(jsonPath("$.pluginUrl").value("http://localhost/static/self-plugins/py/manageddemo.txt"))
                .andExpect(jsonPath("$.repositoryUrl").value("http://localhost/static/self-plugins/spiders_v2.json"));
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
}
