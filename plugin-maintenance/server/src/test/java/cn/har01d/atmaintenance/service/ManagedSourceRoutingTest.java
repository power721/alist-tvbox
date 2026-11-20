package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Local protocol peers only: these tests never execute a source or use an Engine. */
class ManagedSourceRoutingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FixtureRuntime fixture = new FixtureRuntime(mapper, "never-run");
    private final String sourceA = "11111111-1111-4111-8111-111111111111";
    private final String sourceB = "22222222-2222-4222-8222-222222222222";
    private final String instanceA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private final String instanceB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private final String image = "sha256:" + "c".repeat(64);
    @TempDir Path directory;

    ManagedSourceRoutingTest() throws IOException {}

    private ManagedSourceRuntime root(boolean enabled) throws IOException {
        return new ManagedSourceRuntime(mapper, fixture, enabled, "/run/default/executor.sock",
                instanceA, image, "", "");
    }

    private ObjectNode definition(String id, String source, String instance, int port, String policy) {
        var value = mapper.createObjectNode().put("id", id).put("label", id + " channel")
                .put("socket", "tcp://127.0.0.1:" + port).put("instanceId", instance)
                .put("imageId", image).put("egressPolicySha256", policy).put("httpImageId", "");
        value.putArray("sourceIds").add(source);
        return value;
    }

    private Path config(ObjectNode... definitions) throws IOException {
        var value = mapper.createObjectNode().put("schemaVersion", 1);
        var runners = value.putArray("runners");
        for (var definition : definitions) runners.add(definition);
        Path path = directory.resolve("routes.json");
        Files.writeString(path, value.toString());
        return path;
    }

    @Test
    void noRoutesKeepsTheExistingDefaultSnapshotAndConfiguration() throws Exception {
        var root = root(true);
        String before = root.policySha256();
        root.configureRoutes(fixture, "");
        assertSame(root, root.forSource(sourceA));
        assertSame(root, root.forSource(null));
        assertEquals(before, root.policySha256());
        assertEquals("默认执行器", root.routeLabel(sourceA));
    }

    @Test
    void eachSourceGetsItsOwnPolicyWhileLabelsDoNotChangeExecutionIdentity() throws Exception {
        var root = root(true);
        String before = root.policySha256();
        var a = definition("image-api", sourceA, instanceA, 19001, "a".repeat(64));
        var b = definition("html-hls", sourceB, instanceB, 19002, "b".repeat(64));
        b.put("httpImageId", "sha256:" + "d".repeat(64));
        root.configureRoutes(fixture, config(a, b).toString());
        var selectedA = root.forSource(sourceA);
        var selectedB = root.forSource(sourceB);
        assertNotSame(selectedA, selectedB);
        assertEquals("a".repeat(64), selectedA.snapshot().path("egressPolicySha256").asText());
        assertEquals("b".repeat(64), selectedB.snapshot().path("egressPolicySha256").asText());
        assertFalse(selectedA.snapshot().has("httpImageId"));
        assertEquals(b.path("httpImageId"), selectedB.snapshot().path("httpImageId"));
        assertEquals(selectedA.sha256(), selectedB.sha256());
        assertNotEquals(selectedA.policySha256(), selectedB.policySha256());
        assertEquals(before, root.policySha256());
        String frozenA = selectedA.policySha256();
        a.put("label", "renamed channel");
        root.configureRoutes(fixture, config(a, b).toString());
        assertEquals(frozenA, root.forSource(sourceA).policySha256());
        assertEquals("renamed channel", root.routeLabel(sourceA));
        assertFalse(root.forSource(sourceA).snapshot().toString().contains("19001"));
    }

    @Test
    void registeredChannelsStillRespectTheGlobalExecutionSwitch() throws Exception {
        var root = root(false);
        root.configureRoutes(fixture, config(definition("disabled", sourceA, instanceA, 19001, "")).toString());
        assertEquals("MANAGED_EXECUTION_DISABLED", root.forSource(sourceA).configurationBlockedReason());
        assertFalse(root.forSource(sourceA).checkConnection().path("ready").asBoolean());
    }

    @Test
    void ambiguousSourcesAndNonlocalEndpointsRejectTheWholeConfiguration() throws Exception {
        var root = root(true);
        var a = definition("a", sourceA, instanceA, 19001, "");
        var duplicate = definition("b", sourceA, instanceB, 19002, "");
        assertThrows(IOException.class, () -> root.configureRoutes(fixture, config(a, duplicate).toString()));
        assertSame(root, root.forSource(sourceA));
        var remote = definition("b", sourceB, instanceB, 19002, "");
        remote.put("socket", "tcp://192.168.1.2:19002");
        assertThrows(IOException.class, () -> root.configureRoutes(fixture, config(a, remote).toString()));
        a.withArray("sourceIds").add(sourceA);
        assertThrows(IOException.class, () -> root.configureRoutes(fixture, config(a).toString()));
        Files.writeString(directory.resolve("duplicates.json"),
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"runners\":[]}");
        assertThrows(IOException.class,
                () -> root.configureRoutes(fixture, directory.resolve("duplicates.json").toString()));
    }

    @Test
    void startAndRecoveryUseTheFrozenChannelRatherThanTheDefaultOrAnotherSource() throws Exception {
        try (var a = new Peer(instanceA); var b = new Peer(instanceB)) {
            var root = root(true);
            root.configureRoutes(fixture, config(
                    definition("a", sourceA, instanceA, a.port(), ""),
                    definition("b", sourceB, instanceB, b.port(), "")).toString());
            for (String source : List.of(sourceA, sourceB)) {
                var selected = root.forSource(source);
                var peer = source.equals(sourceA) ? a : b;
                var profile = mapper.createObjectNode().put("runtime", "t4").putNull("extFingerprint");
                String job = java.util.UUID.randomUUID().toString();
                IOException failure = assertThrows(IOException.class, () -> root.inspect(job, "# never executed",
                        profile, selected.sha256(), selected.policySha256()));
                assertEquals("MANAGED_RUNNER_BUSY", failure.getMessage());
                root.cleanup(job, selected.snapshot());
                root.cancelAcceptance(job, selected.snapshot());
                assertEquals(List.of("identity", "start", "cancel_acceptance", "cancel", "cancel_acceptance"),
                        peer.operations);
                if (source.equals(sourceA)) assertTrue(b.operations.isEmpty());
                peer.assertFinished();
            }
        }
    }

    @Test
    void unknownFrozenPolicyCannotBeRedirectedToTheDefaultChannel() throws Exception {
        var root = root(true);
        root.configureRoutes(fixture, config(definition("a", sourceA, instanceA, 19001, "")).toString());
        String job = java.util.UUID.randomUUID().toString();
        var changed = (ObjectNode) root.forSource(sourceA).snapshot();
        changed.put("instanceId", instanceB);
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED",
                assertThrows(IOException.class, () -> root.cancelAcceptance(job, changed)).getMessage());
        assertEquals("MANAGED_INPUT_MISMATCH", assertThrows(IOException.class, () -> root.inspect(job, "# no run",
                mapper.createObjectNode(), root.sha256(), "0".repeat(64))).getMessage());
    }

    private class Peer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        final List<String> operations = new CopyOnWriteArrayList<>();
        final java.util.concurrent.Future<?> worker;

        Peer(String instance) throws IOException {
            listener.setSoTimeout(5000);
            worker = executor.submit(() -> {
                for (int i = 0; i < 5; i++) {
                    try (var socket = listener.accept()) {
                        socket.setSoTimeout(5000);
                        var input = new DataInputStream(socket.getInputStream());
                        int length = input.readInt();
                        assertTrue(length > 0 && length <= 1_500_000);
                        var request = mapper.readTree(input.readNBytes(length));
                        String operation = request.path("operation").asText();
                        operations.add(operation);
                        var reply = mapper.createObjectNode().put("ok", !operation.equals("start"));
                        if (operation.equals("identity")) {
                            reply.putObject("result").put("authority", "managed_source_broker_configuration")
                                    .put("instanceId", instance).put("imageId", image)
                                    .put("bundleSha256", root(true).sha256()).put("recoveryRequired", false);
                        } else {
                            assertEquals(instance, request.path("instanceId").asText());
                            if (operation.equals("start")) reply.put("code", "MANAGED_RUNNER_BUSY");
                            else {
                                var row = reply.putObject("result").put("schemaVersion", 1)
                                        .put("authority", "managed_source_broker_observed").put("status", "CANCELLED")
                                        .put("acceptanceCancelled", true);
                                for (String key : List.of("jobId", "instanceId", "imageId",
                                        "bundleSha256", "executionPolicySha256")) row.set(key, request.path(key));
                                row.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
                                row.putObject("acceptanceCleanup").put("confirmed", true)
                                        .put("recoveryRequired", false).put("childJobs", 0).put("pendingJobs", 0);
                            }
                        }
                        byte[] body = mapper.writeValueAsBytes(reply);
                        var output = new DataOutputStream(socket.getOutputStream());
                        output.writeInt(body.length);
                        output.write(body);
                        output.flush();
                    } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                }
            });
        }

        int port() { return listener.getLocalPort(); }
        void assertFinished() throws Exception { worker.get(5, TimeUnit.SECONDS); }
        @Override public void close() throws IOException {
            listener.close();
            executor.shutdownNow();
        }
    }
}
