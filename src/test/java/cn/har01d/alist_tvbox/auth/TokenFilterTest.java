package cn.har01d.alist_tvbox.auth;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenFilterTest {
    @Mock
    private TokenService tokenService;
    @Mock
    private SettingRepository settingRepository;

    private final AtomicReference<String> apiKey = new AtomicReference<>("old-key");
    private final AtomicReference<String> alistLogin = new AtomicReference<>("true");
    private final AtomicReference<String> alistUsername = new AtomicReference<>("dav");
    private final AtomicReference<String> alistPassword = new AtomicReference<>("secret");
    private TokenFilter tokenFilter;

    @BeforeEach
    void setUp() {
        Map<String, AtomicReference<String>> settings = new HashMap<>();
        settings.put("api_key", apiKey);
        settings.put("alist_login", alistLogin);
        settings.put("alist_username", alistUsername);
        settings.put("alist_password", alistPassword);
        settings.put("basic_auth_username", new AtomicReference<>("catuser"));
        settings.put("basic_auth_password", new AtomicReference<>("catpass"));
        when(settingRepository.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0, String.class);
                    AtomicReference<String> value = settings.get(key);
                    if (value == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new Setting(key, value.get()));
                });
        tokenFilter = new TokenFilter(tokenService, settingRepository);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldUseLatestApiKeyAndClearSecurityContextAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-KEY", "old-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        tokenFilter.doFilter(request, response, (req, resp) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertEquals("client", authentication.getPrincipal());
        });

        assertNull(SecurityContextHolder.getContext().getAuthentication());

        apiKey.set("new-key");
        MockHttpServletRequest nextRequest = new MockHttpServletRequest();
        nextRequest.addHeader("X-API-KEY", "new-key");
        MockHttpServletResponse nextResponse = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);

        tokenFilter.doFilter(nextRequest, nextResponse, chain);

        assertEquals(200, nextResponse.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void openEndpointShouldRequireBasicAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/open");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);

        tokenFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Basic realm=\"alist\"", response.getHeader("Www-Authenticate"));
        org.mockito.Mockito.verifyNoInteractions(chain);
    }

    @Test
    void openEndpointShouldPassWithValidBasicAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/open");
        request.addHeader("Authorization", basic("catuser", "catpass"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);

        tokenFilter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
    }

    @Test
    void catEndpointShouldRejectWrongBasicAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/cat/index.config.js");
        request.addHeader("Authorization", basic("catuser", "wrong"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);

        tokenFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        org.mockito.Mockito.verifyNoInteractions(chain);
    }

    private String basic(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
