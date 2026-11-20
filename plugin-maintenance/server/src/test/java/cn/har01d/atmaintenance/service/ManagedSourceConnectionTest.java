package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ManagedSourceConnectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FixtureRuntime fixture = new FixtureRuntime(mapper, "never-run");
    private final String instance = "d0f4909a-5709-4e57-9a54-4ded3c19424a";
    private final String image = "sha256:" + "b".repeat(64);

    ManagedSourceConnectionTest() throws IOException {}

    private ManagedSourceRuntime runtime(boolean enabled, String endpoint) throws IOException {
        return new ManagedSourceRuntime(mapper, fixture, enabled, endpoint, instance, image, "", "");
    }

    @Test
    void tunnelAcceptsOnlyLiteralLoopbackAndDoesNotExposeTheEndpoint() throws Exception {
        var runtime = runtime(true, "tcp://127.0.0.1:18991");
        assertNull(runtime.configurationBlockedReason());
        assertEquals("loopback_tunnel", runtime.snapshot().path("transport").asText());
        assertFalse(runtime.snapshot().toString().contains("18991"));
        for (String endpoint : List.of("tcp://example.com:18991", "tcp://localhost:18991",
                "tcp://192.168.1.2:18991", "tcp://0.0.0.0:18991", "tcp://127.0.0.1:0",
                "tcp://127.0.0.1:65536", "tcp://127.0.0.1:18991/path", "tcp://127.0.0.1:18991\n")) {
            assertEquals("MANAGED_LOCAL_SOCKET_REQUIRED",
                    runtime(true, endpoint).configurationBlockedReason(), endpoint);
        }
        var disabled = runtime(false, "tcp://127.0.0.1:18991").checkConnection();
        assertFalse(disabled.path("connected").asBoolean());
        assertFalse(disabled.path("sourceExecuted").asBoolean());
        assertEquals("MANAGED_EXECUTION_DISABLED", disabled.path("code").asText());
    }

    @Test
    void realTcpFramesCheckIdentityWithoutSendingSourceOrStartingJobs() throws Exception {
        for (String defect : List.of("none", "image", "recovery")) {
            try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                 var executor = Executors.newSingleThreadExecutor()) {
                listener.setSoTimeout(3000);
                var runtime = runtime(true, "tcp://127.0.0.1:" + listener.getLocalPort());
                var server = executor.submit(() -> {
                    try (var socket = listener.accept()) {
                        socket.setSoTimeout(3000);
                        var input = new DataInputStream(socket.getInputStream());
                        int size = input.readInt();
                        assertTrue(size > 0 && size < 256);
                        var request = mapper.readTree(input.readNBytes(size));
                        assertEquals(mapper.readTree("{\"schemaVersion\":1,\"operation\":\"identity\"}"), request);
                        var reply = mapper.createObjectNode().put("ok", true);
                        reply.putObject("result").put("authority", "managed_source_broker_configuration")
                                .put("instanceId", instance).put("imageId", defect.equals("image") ? "wrong" : image)
                                .put("bundleSha256", runtime.sha256()).put("recoveryRequired", defect.equals("recovery"))
                                .put("private", "never-project-this");
                        byte[] body = mapper.writeValueAsBytes(reply);
                        var output = new DataOutputStream(socket.getOutputStream());
                        output.writeInt(body.length);
                        output.write(body);
                        output.flush();
                    }
                    return true;
                });
                var observed = runtime.checkConnection();
                assertTrue(server.get(5, TimeUnit.SECONDS));
                assertTrue(observed.path("connected").asBoolean());
                assertEquals(defect.equals("none"), observed.path("ready").asBoolean());
                assertFalse(observed.path("sourceExecuted").asBoolean());
                assertFalse(observed.toString().contains("never-project-this"));
                assertEquals(defect.equals("none") ? "" : "MANAGED_RUNNER_IDENTITY_MISMATCH",
                        observed.path("code").asText());
            }
        }
    }

    @Test
    void closedTunnelAndInvalidFramesCannotReportReady() throws Exception {
        int port;
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = listener.getLocalPort();
        }
        var unavailable = runtime(true, "tcp://127.0.0.1:" + port).checkConnection();
        assertFalse(unavailable.path("ready").asBoolean());
        assertEquals("MANAGED_TRANSPORT_UNAVAILABLE", unavailable.path("code").asText());
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(3000);
            var server = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(input.readInt());
                    var output = new DataOutputStream(socket.getOutputStream());
                    byte[] body = "{\"ok\":true,\"ok\":false}".getBytes(StandardCharsets.UTF_8);
                    output.writeInt(body.length);
                    output.write(body);
                    output.flush();
                }
                return true;
            });
            assertFalse(runtime(true, "tcp://127.0.0.1:" + listener.getLocalPort())
                    .checkConnection().path("ready").asBoolean());
            assertTrue(server.get(5, TimeUnit.SECONDS));
        }
    }
}
