package cn.har01d.alist_tvbox.maintenance;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.atmaintenance.service.UpstreamService;
import cn.har01d.atmaintenance.web.ApiErrors;
import cn.har01d.atmaintenance.web.UpstreamController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmbeddedApiErrorsTest {
    private final UpstreamService upstream = mock(UpstreamService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new UpstreamController(upstream), new NativeEndpoint())
                .setControllerAdvice(new RestErrorHandler(), new ApiErrors()).build();
    }

    @Test
    void maintenanceConflictKeepsItsCodeAheadOfHostAdvice() throws Exception {
        when(upstream.get("owned")).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT, "UPSTREAM_LOCAL_CHANGES"));

        mvc.perform(get("/api/plugin-maintenance/sources/owned/upstream"))
                .andExpect(status().isConflict())
                .andExpect(content().string("{\"code\":\"UPSTREAM_LOCAL_CHANGES\"}"));
    }

    @Test
    void maintenanceValidationDoesNotEchoSubmittedValues() throws Exception {
        mvc.perform(post("/api/plugin-maintenance/sources/owned/upstream/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"owned\",\"expectedRevision\":1,"
                                + "\"expectedArtifactSha256\":\"" + "a".repeat(64) + "\","
                                + "\"decision\":\"private-submitted-value\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"code\":\"INVALID_REQUEST\"}"));
        verifyNoInteractions(upstream);
    }

    @Test
    void nativeAtErrorsKeepTheirProblemDetailContract() throws Exception {
        mvc.perform(get("/api/native-error"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("NATIVE_CONFLICT"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @RestController
    static class NativeEndpoint {
        @GetMapping("/api/native-error")
        void conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "NATIVE_CONFLICT");
        }
    }
}
