package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SourceConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsBoundedPublicGatewayConfigAndRejectsCredentials() throws Exception {
        var config = mapper.readTree("""
                {"proxy_gateway":"https://gateway.example.test",
                 "gateway_timeout":20,"gateway_retries":2,"subtitle_enabled":true}
                """);
        SourceConfig.validate(config);
        assertEquals(Hashes.sha256(
                "{\"gateway_retries\":2,\"gateway_timeout\":20,\"proxy_gateway\":\"https://gateway.example.test\",\"subtitle_enabled\":true}"),
                SourceConfig.sha256(config));
        assertEquals(SourceConfig.sha256(config),
                SourceConfig.sha256(mapper.readTree("""
                        {"subtitle_enabled":true,"gateway_retries":2,
                         "gateway_timeout":20,"proxy_gateway":"https://gateway.example.test"}
                        """)));
        for (var invalid : new String[]{"{\"token\":\"x\"}", "{\"cookie\":\"x\"}",
                "{\"gateway_timeout\":true}", "{\"subtitle_enabled\":1}",
                "{\"subtitle_enabled\":\"true\"}", "{\"proxy_base\":\"https://callback.example.test\"}",
                "{\"proxy_gateway\":\"https://gateway.example.test/path?x=1\"}"}) {
            assertThrows(IOException.class, () -> SourceConfig.validate(mapper.readTree(invalid)));
        }
    }
}
