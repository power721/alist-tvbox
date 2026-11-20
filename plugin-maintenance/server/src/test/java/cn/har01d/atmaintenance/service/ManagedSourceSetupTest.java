package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.SourceSummary;
import cn.har01d.atmaintenance.dto.ProfileSummary;
import cn.har01d.atmaintenance.dto.RepairSummary;
import cn.har01d.atmaintenance.entity.MaintenanceArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedSourceSetupTest {
    private final SourceService sources = mock(SourceService.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final ManagedSourceRuntime managed = mock(ManagedSourceRuntime.class);
    private final ManagedSourceRuntime selected = mock(ManagedSourceRuntime.class);
    private final FixtureRuntime fixture = mock(FixtureRuntime.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final String sourceId = "11111111-1111-4111-8111-111111111111";
    private final String sourceSha = "a".repeat(64);
    @TempDir Path directory;
    private Path script;
    private Path routes;
    private Path tunnels;
    private ManagedSourceSetup setup;

    private RepairSummary originRepair() {
        var frozen = mapper.createObjectNode();
        var repair = new RepairSummary("repair", sourceId, "run", sourceSha, "f".repeat(64),
                "BLOCKED", "EGRESS_URL_DENIED", 4, 4, null, java.time.Instant.now(), frozen, "current", java.util.List.of());
        frozen.set("reverseAnalysis", RepairEvidenceMaterialTest.originReceipt(mapper, repair));
        return repair;
    }

    @Test
    void verifiedOriginRefreshesAnExistingRouteAndBindsTheReceiptWithoutRunningSource() throws Exception {
        when(managed.hasSourceRoute(sourceId)).thenReturn(true);
        var repair = originRepair();
        helper("""
                {"ok": True, "sourceId":request["sourceId"], "sourceSha256":request["sourceSha256"],
                 "bundleSha256":request["bundleSha256"], "sourceExecuted":False, "ready":True,
                 "verifiedOriginRoute":request["verifiedOriginRoute"],
                 "approvedOrigins":[request["verifiedOriginRoute"]["candidateOrigin"]], "egressPolicySha256":"d"*64}
                """);
        when(selected.configurationBlockedReason()).thenReturn((String) null);
        when(selected.sampling()).thenReturn(true);
        when(selected.snapshot()).thenReturn(mapper.createObjectNode().put("egressPolicySha256", "d".repeat(64)));
        assertTrue(setup.ensureVerifiedOrigin(repair));
        verify(managed).configureRoutes(fixture, routes.toString());
        assertEquals(4, repair.usedRounds());
    }

    @Test
    void unboundEvidenceNeverRefreshesAndAnUnboundReceiptCannotBeInstalled() throws Exception {
        var repair = originRepair();
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) repair.frozen().path("reverseAnalysis");
        report.put("cleanupConfirmed", false);
        assertFalse(setup.ensureVerifiedOrigin(repair));
        verifyNoInteractions(sources, profiles);
        report.put("cleanupConfirmed", true);
        helper("""
                {"ok":True, "sourceId":request["sourceId"], "sourceSha256":request["sourceSha256"],
                 "bundleSha256":request["bundleSha256"], "sourceExecuted":False, "ready":True,
                 "approvedOrigins":["https://alternate.example.com"], "egressPolicySha256":"d"*64}
                """);
        var error = assertThrows(ResponseStatusException.class, () -> setup.ensureVerifiedOrigin(repair));
        assertEquals("SOURCE_SETUP_RECEIPT_INVALID", error.getReason());
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @BeforeEach
    void prepare() throws Exception {
        script = directory.resolve("trusted-helper.py");
        routes = directory.resolve("routes.json");
        tunnels = directory.resolve("tunnels.json");
        Files.writeString(routes, "{}");
        Files.writeString(tunnels, "{\"ssh\":{}}");
        when(managed.forSource(sourceId)).thenReturn(selected);
        when(selected.configurationBlockedReason()).thenReturn("MANAGED_LOCAL_SOCKET_REQUIRED");
        when(managed.sha256()).thenReturn("b".repeat(64));
        var source = mock(SourceSummary.class);
        when(source.artifactSha256()).thenReturn(sourceSha);
        when(sources.get(sourceId)).thenReturn(source);
        var artifact = new MaintenanceArtifact();
        artifact.setBody("raise RuntimeError('SOURCE_MUST_NOT_RUN')\n");
        when(sources.artifact(sourceId, sourceSha)).thenReturn(artifact);
        when(profiles.get(sourceId)).thenReturn(new ProfileSummary(null, sourceId, "e".repeat(64),
                mapper.createObjectNode(), null));
        String python = System.getenv("MAINTENANCE_PYTHON");
        assertNotNull(python, "Run with the project MAINTENANCE_PYTHON");
        setup = new ManagedSourceSetup(sources, profiles, managed, fixture, mapper, true, python,
                script.toString(), routes.toString(), tunnels.toString());
    }

    private void helper(String result) throws Exception {
        Files.writeString(script, "import json,os,sys\nrequest=json.load(sys.stdin.buffer)\n"
                + "result=" + result + "\nprint(json.dumps(result))\n");
    }

    @Test
    void sameHostSetupNeedsNoSshConfigurationAndBindsTheExistingRuntimeReceipt() throws Exception {
        setup = new ManagedSourceSetup(sources, profiles, managed, fixture, mapper, true,
                "unused", "", routes.toString(), "");
        setup.configureLocal("/run/maintenance/setup.sock");
        assertTrue(setup.availableFor(sourceId));
        when(selected.configurationBlockedReason()).thenReturn("MANAGED_LOCAL_SOCKET_REQUIRED", (String) null);
        when(selected.sampling()).thenReturn(true);
        when(selected.snapshot()).thenReturn(mapper.createObjectNode().put("egressPolicySha256", "d".repeat(64)));
        try (var ipc = mockStatic(LocalSourceSetupClient.class)) {
            ipc.when(() -> LocalSourceSetupClient.call(eq(mapper), eq("/run/maintenance/setup.sock"), any()))
                    .thenAnswer(call -> {
                        var request = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(2);
                        assertEquals(sourceId, request.path("sourceId").asText());
                        assertFalse(request.has("ssh"));
                        return mapper.createObjectNode().put("ok", true).put("sourceId", sourceId)
                                .put("sourceSha256", sourceSha).put("bundleSha256", "b".repeat(64))
                                .put("sourceExecuted", false).put("ready", true).put("egressPolicySha256", "d".repeat(64));
                    });
            setup.ensure(sourceId, sourceSha);
        }
        verify(managed).configureRoutes(fixture, routes.toString());
        assertTrue(setup.sameHost());
    }

    @Test
    void sameHostConnectionReportsInfrastructureOnlyAndNeverStartsACrawler() throws Exception {
        setup.configureLocal("/run/maintenance/setup.sock");
        try (var ipc = mockStatic(LocalSourceSetupClient.class)) {
            ipc.when(() -> LocalSourceSetupClient.call(eq(mapper), anyString(), any())).thenReturn(
                    mapper.createObjectNode().put("ok", true).put("ready", true)
                            .put("backend", "same_host_t4").put("sourceExecuted", false));
            var observed = setup.localConnection();
            assertTrue(observed.path("connected").asBoolean());
            assertEquals("source_setup_connection_observed", observed.path("authority").asText());
        }
        verifyNoInteractions(sources, profiles);
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @Test
    void sameHostSetupDoesNotInstallAnUnboundOrSourceExecutingReceipt() throws Exception {
        setup.configureLocal("/run/maintenance/setup.sock");
        try (var ipc = mockStatic(LocalSourceSetupClient.class)) {
            ipc.when(() -> LocalSourceSetupClient.call(eq(mapper), anyString(), any())).thenReturn(
                    mapper.createObjectNode().put("ok", true).put("sourceId", sourceId)
                            .put("sourceSha256", sourceSha).put("bundleSha256", "b".repeat(64))
                            .put("ready", true).put("sourceExecuted", true));
            assertEquals("SOURCE_SETUP_RECEIPT_INVALID", assertThrows(ResponseStatusException.class,
                    () -> setup.ensure(sourceId, sourceSha)).getReason());
        }
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @Test
    void explicitlyMappedSourcesNeverInvokeTheInfrastructureHelper() throws Exception {
        when(managed.hasSourceRoute(sourceId)).thenReturn(true);
        assertFalse(setup.availableFor(sourceId));
        setup.ensure(sourceId, sourceSha);
        verify(sources).requireMaintenanceAllowed(sourceId);
        verifyNoMoreInteractions(sources);
        verifyNoInteractions(profiles);
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @Test
    void changedSourceIsRejectedBeforeCredentialsOrInfrastructureAreRead() throws Exception {
        var error = assertThrows(ResponseStatusException.class, () -> setup.ensure(sourceId, "c".repeat(64)));
        assertEquals("MAINTENANCE_SOURCE_CHANGED", error.getReason());
        verify(sources, never()).artifact(anyString(), anyString());
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @Test
    void aValidInfrastructureReceiptLoadsTheRouteWithoutExecutingTheSource() throws Exception {
        helper("""
                {"ok": True, "sourceId": request["sourceId"], "sourceSha256": request["sourceSha256"],
                 "bundleSha256": request["bundleSha256"], "sourceExecuted": False, "ready": True,
                 "egressPolicySha256": "d"*64}
                """);
        when(selected.configurationBlockedReason()).thenReturn("MANAGED_LOCAL_SOCKET_REQUIRED", (String) null);
        when(selected.sampling()).thenReturn(true);
        when(selected.snapshot()).thenReturn(mapper.createObjectNode().put("egressPolicySha256", "d".repeat(64)));
        setup.ensure(sourceId, sourceSha);
        verify(managed).configureRoutes(fixture, routes.toString());
        verify(sources).artifact(sourceId, sourceSha);
        assertEquals("raise RuntimeError('SOURCE_MUST_NOT_RUN')\n", sources.artifact(sourceId, sourceSha).getBody());
    }

    @Test
    void publicConfigurationAndCanonicalHashReachTheHelperWithoutExecutingTheSource() throws Exception {
        var config = mapper.createObjectNode().put("base_url", "https://configured.example.test/catalog")
                .put("title", "\u914d\u7f6e\u6765\u6e90");
        var snapshot = mapper.createObjectNode();
        snapshot.set("sourceConfig", config);
        when(profiles.get(sourceId)).thenReturn(new ProfileSummary(null, sourceId, "e".repeat(64), snapshot, null));
        Files.writeString(script, """
                import hashlib,json,sys
                request=json.load(sys.stdin.buffer)
                config=request["sourceConfig"]
                assert config["base_url"] == "https://configured.example.test/catalog"
                canonical=json.dumps(config,ensure_ascii=True,sort_keys=True,separators=(",",":"))
                assert hashlib.sha256(canonical.encode()).hexdigest() == request["sourceConfigSha256"]
                print(json.dumps({"ok":True,"sourceId":request["sourceId"],
                    "sourceSha256":request["sourceSha256"],"bundleSha256":request["bundleSha256"],
                    "sourceConfigSha256":request["sourceConfigSha256"],"sourceExecuted":False,
                    "ready":True,"egressPolicySha256":"d"*64}))
                """);
        when(selected.configurationBlockedReason()).thenReturn("MANAGED_LOCAL_SOCKET_REQUIRED", (String) null);
        when(selected.sampling()).thenReturn(true);
        when(selected.snapshot()).thenReturn(mapper.createObjectNode().put("egressPolicySha256", "d".repeat(64)));
        setup.ensure(sourceId, sourceSha);
        verify(managed).configureRoutes(fixture, routes.toString());
    }

    @Test
    void mismatchedConfigurationReceiptCannotInstallTheRoute() throws Exception {
        var snapshot = mapper.createObjectNode();
        snapshot.putObject("sourceConfig").put("base_url", "https://configured.example.test");
        when(profiles.get(sourceId)).thenReturn(new ProfileSummary(null, sourceId, "e".repeat(64), snapshot, null));
        helper("""
                {"ok":True,"sourceId":request["sourceId"],"sourceSha256":request["sourceSha256"],
                 "bundleSha256":request["bundleSha256"],"sourceConfigSha256":"0"*64,
                 "sourceExecuted":False,"ready":True,"egressPolicySha256":"d"*64}
                """);
        var error = assertThrows(ResponseStatusException.class, () -> setup.ensure(sourceId, sourceSha));
        assertEquals("SOURCE_SETUP_RECEIPT_INVALID", error.getReason());
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @Test
    void invalidPublicConfigurationCannotReachTheHelper() throws Exception {
        var snapshot = mapper.createObjectNode();
        snapshot.putObject("sourceConfig").put("cookie", "PRIVATE_MARKER");
        when(profiles.get(sourceId)).thenReturn(new ProfileSummary(null, sourceId, "e".repeat(64), snapshot, null));
        var error = assertThrows(ResponseStatusException.class, () -> setup.ensure(sourceId, sourceSha));
        assertEquals("SOURCE_SETUP_SOURCE_CONFIG_INVALID", error.getReason());
        verify(sources, never()).artifact(anyString(), anyString());
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @Test
    void untrustedOrSourceExecutingReceiptsCannotInstallARoute() throws Exception {
        for (String field : new String[]{"sourceSha256", "sourceExecuted"}) {
            helper("""
                    {"ok": True, "sourceId": request["sourceId"], "sourceSha256": request["sourceSha256"],
                     "bundleSha256": request["bundleSha256"], "sourceExecuted": False, "ready": True,
                     "%s": %s}
                    """.formatted(field, field.equals("sourceExecuted") ? "True" : "'wrong'"));
            var error = assertThrows(ResponseStatusException.class, () -> setup.ensure(sourceId, sourceSha));
            assertEquals("SOURCE_SETUP_RECEIPT_INVALID", error.getReason());
        }
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @Test
    void failureCodesCannotExposeRawInfrastructureErrors() throws Exception {
        helper("{'ok':False,'code':'private endpoint and credential text'}");
        var error = assertThrows(ResponseStatusException.class, () -> setup.ensure(sourceId, sourceSha));
        assertEquals("SOURCE_SETUP_UNAVAILABLE", error.getReason());
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @Test
    void dnsPreparationFailuresKeepTheirReasonWithoutInstallingARoute() throws Exception {
        for (String code : new String[]{"SOURCE_SETUP_DNS_NO_ADDRESS", "SOURCE_SETUP_DNS_NONPUBLIC",
                "SOURCE_SETUP_DNS_UNAVAILABLE"}) {
            helper("{'ok':False,'code':'" + code + "','sourceExecuted':False}");
            var error = assertThrows(ResponseStatusException.class, () -> setup.ensure(sourceId, sourceSha));
            assertEquals(code, error.getReason());
        }
        verify(managed, never()).configureRoutes(any(), anyString());
    }

    @Test
    void trustedSshHelperRetainsTheExistingHostKeyHome() throws Exception {
        String name = System.getenv("USERPROFILE") != null ? "USERPROFILE" : "HOME";
        assertNotNull(System.getenv(name));
        helper("{'ok':False,'code':'SOURCE_SETUP_HOME_PRESERVED' if os.environ.get('"
                + name + "') and os.path.expanduser('~') != '~' else 'SOURCE_SETUP_HOME_MISSING'}");
        var error = assertThrows(ResponseStatusException.class, () -> setup.ensure(sourceId, sourceSha));
        assertEquals("SOURCE_SETUP_HOME_PRESERVED", error.getReason());
    }
}
