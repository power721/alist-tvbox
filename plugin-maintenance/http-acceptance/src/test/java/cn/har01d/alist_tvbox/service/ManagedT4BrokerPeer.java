package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Shared authored Unix peer. It never imports or executes the supplied source bytes. */
public class ManagedT4BrokerPeer {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String INSTANCE = "00000000-0000-4000-8000-000000000001";
    static final String IMAGE = "sha256:" + "a".repeat(64), BUNDLE = "b".repeat(64);
    static final String RUNTIME = "c".repeat(64), POLICY = "d".repeat(64), EGRESS = "e".repeat(64);
    static final String PARENT = "00000000-0000-4000-8000-000000000002", PROFILE = "f".repeat(64);
    static final byte[] SOURCE = "\uFEFF# fixture bytes are never executed\n".getBytes(StandardCharsets.UTF_8);
    public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    public final AtomicReference<String> fault = new AtomicReference<>("");
    public final AtomicReference<JsonNode> proxyOutput = new AtomicReference<>();
    public final AtomicReference<JsonNode> proxyManifestTargets = new AtomicReference<>();
    public java.util.function.BiFunction<JsonNode, ObjectNode, ObjectNode> outputTransform = (call, output) -> output;
    public volatile CountDownLatch takeEntered = new CountDownLatch(1), takeClosed = new CountDownLatch(1);
    final Map<String, JsonNode> rows = new ConcurrentHashMap<>(), results = new ConcurrentHashMap<>();
    final Map<String, JsonNode> sessionCalls = new ConcurrentHashMap<>();
    final Map<String, Integer> sessionOrdinals = new ConcurrentHashMap<>();
    final Map<String, com.fasterxml.jackson.databind.node.ArrayNode> sessionActions = new ConcurrentHashMap<>();
    final AtomicReference<Throwable> peerFailure = new AtomicReference<>();
    final Set<SocketChannel> channels = ConcurrentHashMap.newKeySet();
    final AtomicBoolean stopping = new AtomicBoolean();
    final ExecutorService tasks = Executors.newCachedThreadPool();
    final List<ManagedT4BrokerClient> clients = new CopyOnWriteArrayList<>();
    final byte[] source;
    private String acceptanceParent = PARENT, acceptanceProfile = PROFILE;
    ServerSocketChannel server;
    Future<?> acceptor;
    Path directory, socket;

    public ManagedT4BrokerPeer() { this(SOURCE); }
    public ManagedT4BrokerPeer(byte[] source) { this.source = source.clone(); }

    public void bindAcceptanceParent(String jobId, String profileJson) throws Exception {
        acceptanceParent = jobId;
        acceptanceProfile = sha(profileJson.getBytes(StandardCharsets.UTF_8));
    }

    public Path socketPath() { return socket; }

    public ObjectNode plan(String profileJson) throws Exception {
        bindAcceptanceParent(PARENT, profileJson);
        return configuration().put("sourceImageId", IMAGE).put("parentJobId", PARENT)
                .put("runtimeSha256", RUNTIME).put("executionPolicySha256", POLICY)
                .put("sourceBase64", Base64.getEncoder().encodeToString(source))
                .put("sourceSha256", sha(source)).put("loaderProfileJson", profileJson);
    }

    @BeforeEach public void startPeer() throws Exception {
        directory = Files.createTempDirectory("t4b-");
        socket = directory.resolve("executor.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        acceptor = tasks.submit(() -> {
            while (!stopping.get()) {
                try {
                    SocketChannel connection = server.accept();
                    channels.add(connection);
                    tasks.submit(() -> serve(connection));
                } catch (IOException error) {
                    if (!stopping.get()) peerFailure.compareAndSet(null, error);
                    return;
                }
            }
        });
    }

    public ManagedT4BrokerClient client(Duration timeout) throws Exception {
        var value = new ManagedT4BrokerClient(JSON.copy().enable(
                com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT), socket, INSTANCE,
                IMAGE, BUNDLE, RUNTIME, POLICY, EGRESS, source, timeout);
        clients.add(value);
        return value;
    }

    ObjectNode call(String method, String playType, Map<String, Object> params) {
        var value = JSON.createObjectNode().put("method", method).put("playType", playType);
        value.set("params", JSON.valueToTree(params));
        return value;
    }

    void serve(SocketChannel channel) {
        try (channel) {
            var input = new DataInputStream(Channels.newInputStream(channel));
            int size = input.readInt();
            assertTrue(size > 0 && size <= 1_500_000);
            byte[] body = input.readNBytes(size);
            assertEquals(size, body.length);
            var request = JSON.readTree(body);
            requests.add(request);
            String operation = request.path("operation").asText(), mode = fault.get();
            JsonNode reply = respond(request);
            if (mode.equals("lost_" + operation)) return;
            if (Set.of("take", "session_take").contains(operation) && Set.of("hold", "timeout").contains(mode)
                    || operation.equals("session_close") && mode.equals("hold_session_close")) {
                takeEntered.countDown();
                assertEquals(-1, input.read(), "Client must close a timed-out/cancelled exchange");
                takeClosed.countDown();
                return;
            }
            byte[] encoded = JSON.writeValueAsBytes(Map.of("ok", true, "result", reply));
            var output = new DataOutputStream(Channels.newOutputStream(channel));
            if (mode.startsWith("frame_")) {
                if (mode.equals("frame_limit")) {
                    output.writeInt(65_537);
                    output.flush();
                    return;
                }
                String text = new String(encoded, StandardCharsets.UTF_8);
                if (mode.equals("frame_duplicate")) text = "{\"ok\":false," + text.substring(1);
                if (mode.equals("frame_trailing")) text += " {}";
                encoded = text.getBytes(StandardCharsets.UTF_8);
            }
            output.writeInt(encoded.length);
            for (int start = 0; start < encoded.length; start += 71)
                output.write(encoded, start, Math.min(71, encoded.length - start));
            output.flush();
        } catch (AsynchronousCloseException | java.net.SocketException error) {
            // This is test-owned transport shutdown, not a daemon cleanup observation.
        } catch (Throwable error) {
            if (!stopping.get()) peerFailure.compareAndSet(null, error);
        } finally {
            channels.remove(channel);
        }
    }

    JsonNode respond(JsonNode request) throws Exception {
        String operation = request.path("operation").asText(), job = request.path("jobId").asText();
        if (operation.equals("identity")) {
            assertEquals(Set.of("schemaVersion", "operation"), keys(request));
            var identity = configuration().put("schemaVersion", 1)
                    .put("authority", "managed_source_broker_configuration").put("recoveryRequired", false);
            if (fault.get().equals("identity")) identity.put("bundleSha256", "0".repeat(64));
            return identity;
        }
        assertEquals(INSTANCE, request.path("instanceId").asText());
        assertEquals(IMAGE, request.path("imageId").asText());
        assertEquals(BUNDLE, request.path("bundleSha256").asText());
        assertEquals(POLICY, request.path("executionPolicySha256").asText());
        assertEquals(EGRESS, request.path("egressPolicySha256").asText());
        if (operation.equals("session_call")) {
            assertTrue(sessionCalls.containsKey(job));
            assertEquals(sessionOrdinals.get(job) + 1, request.path("ordinal").asInt());
            sessionCalls.put(job, JSON.readTree(request.path("t4CallJson").asText()));
            sessionOrdinals.put(job, request.path("ordinal").asInt());
            return JSON.createObjectNode().put("accepted", true).put("ordinal", request.path("ordinal").asInt());
        }
        if (operation.equals("session_take")) {
            JsonNode call = sessionCalls.get(job);
            assertNotNull(call);
            int ordinal = sessionOrdinals.get(job);
            assertEquals(ordinal, request.path("ordinal").asInt());
            assertEquals(sha(ManagedT4BrokerClient.canonical(call).getBytes(StandardCharsets.US_ASCII)),
                    request.path("t4CallSha256").asText());
            var reply = JSON.createObjectNode().put("ready", true).put("schemaVersion", 1)
                    .put("authority", "host_t4_session_observed").put("jobId", job)
                    .put("sourceSha256", sha(source)).put("runtimeSha256", RUNTIME)
                    .put("ordinal", ordinal).put("method", call.path("method").asText())
                    .put("t4CallSha256", request.path("t4CallSha256").asText())
                    .put("paramsSha256", sha(ManagedT4BrokerClient.canonical(call.path("params")).getBytes(StandardCharsets.US_ASCII)))
                    .put("resultSha256", "a".repeat(64)).put("workerCallId", ordinal + 1)
                    .put("cleanupPending", true).put("businessChainVerified", false).put("publishEligible", false);
            var output = reply.putObject("result").put("transportFixtureOnly", true).put("method", call.path("method").asText())
                    .put("url", fault.get().equals("local_proxy") ? "http://127.0.0.1:12345/stream"
                            : fault.get().equals("session_proxy") ? "http://managed.invalid/local?type=video"
                            : "https://media.example.invalid/fixture.m3u8");
            if (call.path("method").asText().equals("proxy")) {
                byte[] body = new byte[564];
                body[0] = body[188] = body[376] = 0x47;
                output.removeAll();
                output.put("status", 206).put("mime", "video/mp2t").put("bytes", body.length)
                        .put("body", Base64.getEncoder().encodeToString(call.path("params").path("head").asBoolean() ? new byte[0] : body))
                        .put("sha256", sha(call.path("params").path("head").asBoolean() ? new byte[0] : body));
                output.putObject("headers").put("content-range", "bytes 0-563/564").put("accept-ranges", "bytes");
                if (proxyOutput.get() != null) {
                    output = (ObjectNode) proxyOutput.get().deepCopy();
                    if (call.path("params").path("head").asBoolean())
                        output.put("body", "").put("sha256", sha(new byte[0]));
                }
                if (proxyManifestTargets.get() != null && !call.path("params").path("head").asBoolean())
                    reply.set("proxyManifestTargets", proxyManifestTargets.get().deepCopy());
            }
            output = outputTransform.apply(call, output);
            String resultJson = JSON.writeValueAsString(output);
            reply.remove("result");
            reply.put("resultJson", resultJson).put("resultSha256", sha(resultJson.getBytes(StandardCharsets.UTF_8)));
            var action = sessionActions.get(job).addObject();
            for (String key : List.of("ordinal", "method", "t4CallSha256", "paramsSha256", "resultSha256", "workerCallId"))
                action.set(key, reply.get(key).deepCopy());
            JsonNode row = rows.get(job);
            if (row.has("parentJobId")) {
                reply.set("parentJobId", row.path("parentJobId"));
                reply.set("parentProfileSha256", row.path("parentProfileSha256"));
            }
            switch (fault.get()) {
                case "take_runtime" -> reply.put("runtimeSha256", "0".repeat(64));
                case "take_hash" -> reply.put("t4CallSha256", "0".repeat(64));
                case "session_body" -> reply.put("resultJson", "{\"modified\":true}");
                case "take_parent" -> reply.put("parentJobId", "wrong");
                case "take_profile" -> reply.put("parentProfileSha256", "0".repeat(64));
            }
            return reply;
        }
        if (operation.equals("start") || operation.equals("start_session")) {
            assertArrayEquals(source, Base64.getDecoder().decode(request.path("sourceBase64").asText()));
            assertEquals(sha(source), request.path("sourceSha256").asText());
            assertFalse(request.has("extend"));
            assertFalse(request.has("loaderProfileJson"));
            JsonNode call = JSON.readTree(request.path("t4CallJson").asText());
            JsonNode output = JSON.valueToTree(Map.of("list", List.of(Map.of("vod_id", "fixture")),
                    "transportFixtureOnly", true, "method", call.path("method").asText(),
                    "url", fault.get().equals("local_proxy") ? "http://127.0.0.1:12345/stream"
                            : "https://media.example.invalid/fixture.m3u8"));
            results.put(job, output);
            var row = row(job).put("status", "RUNNING").put("sourceSha256", sha(source))
                    .put("t4CallSha256", sha(request.path("t4CallJson").asText().getBytes(StandardCharsets.UTF_8)));
            if (request.has("parentJobId")) {
                assertEquals(acceptanceParent, request.path("parentJobId").asText());
                row.put("parentJobId", acceptanceParent).put("parentProfileSha256", acceptanceProfile);
            }
            var result = JSON.createObjectNode().put("schemaVersion", 1)
                    .put("authority", "managed_source_host_observed").put("executionKind", "t4_single_action_rpc")
                    .put("rawOutputStored", false).put("businessChainVerified", false).put("publishEligible", false);
            String text = new String(source, StandardCharsets.UTF_8);
            var receipt = result.putObject("t4RpcValidation").put("schemaVersion", 1)
                    .put("authority", "host_t4_rpc_transport_observed").put("inputSourceSha256", sha(source))
                    .put("loadedTextSha256", sha((text.startsWith("\uFEFF") ? text.substring(1) : text)
                            .getBytes(StandardCharsets.UTF_8)))
                    .put("runtimeSha256", RUNTIME).put("resultSha256", sha(JSON.writeValueAsBytes(output)))
                    .put("actionReturned", true).put("destroyReturned", true)
                    .put("initializationKind", "credential_free_local_source_projection")
                    .put("sourceDataPresent", call.has("sourceData"))
                    .put("sourceDataSha256", sha(ManagedT4BrokerClient.canonical(call.has("sourceData")
                            ? call.get("sourceData") : JSON.createObjectNode()).getBytes(StandardCharsets.US_ASCII)))
                    .put("fullGeneratedExtForwarded", false).put("businessChainVerified", false)
                    .put("productNodePassesAdded", 0).put("publishEligible", false).put("rawOutputStored", false);
            receipt.set("method", call.get("method"));
            receipt.set("playType", call.get("playType"));
            String canonical = request.path("t4CallJson").asText();
            int begin = canonical.indexOf("\"params\":") + "\"params\":".length();
            int end = canonical.lastIndexOf(",\"playType\":");
            receipt.put("paramsSha256", sha(canonical.substring(begin, end).getBytes(StandardCharsets.UTF_8)));
            receipt.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
            row.set("result", result);
            rows.put(job, row);
            var response = row.deepCopy();
            if (operation.equals("start_session")) {
                sessionCalls.put(job, call);
                sessionOrdinals.put(job, 1);
                sessionActions.put(job, JSON.createArrayNode());
                row.put("executionKind", "t4_bounded_session");
                response.put("executionKind", "t4_bounded_session");
                switch (fault.get()) {
                    case "row_source" -> response.put("sourceSha256", "0".repeat(64));
                    case "row_call" -> response.put("t4CallSha256", "0".repeat(64));
                    case "row_job" -> response.put("jobId", "wrong");
                    case "row_parent" -> response.put("parentJobId", "wrong");
                    case "row_profile" -> response.put("parentProfileSha256", "0".repeat(64));
                }
            }
            return response;
        }
        if (operation.equals("session_close")) {
            var row = (ObjectNode) rows.get(job);
            assertNotNull(row);
            assertTrue(sessionCalls.containsKey(job));
            var result = JSON.createObjectNode().put("schemaVersion", 1)
                    .put("authority", "managed_source_host_observed").put("executionKind", "t4_bounded_session")
                    .put("rawOutputStored", false).put("businessChainVerified", false).put("publishEligible", false);
            String text = new String(source, StandardCharsets.UTF_8);
            var receipt = result.putObject("t4SessionValidation").put("schemaVersion", 1)
                    .put("authority", "host_t4_session_observed").put("inputSourceSha256", sha(source))
                    .put("loadedTextSha256", sha((text.startsWith("\uFEFF") ? text.substring(1) : text)
                            .getBytes(StandardCharsets.UTF_8)))
                    .put("runtimeSha256", RUNTIME).put("initCount", 1).put("destroyReturned", true)
                    .put("playType", sessionCalls.get(job).path("playType").asText())
                    .put("sourceDataPresent", sessionCalls.get(job).has("sourceData"))
                    .put("sourceDataSha256", sha(ManagedT4BrokerClient.canonical(sessionCalls.get(job).has("sourceData")
                            ? sessionCalls.get(job).get("sourceData") : JSON.createObjectNode())
                            .getBytes(StandardCharsets.US_ASCII)))
                    .put("initializationKind", "credential_free_local_source_projection")
                    .put("fullGeneratedExtForwarded", false)
                    .put("rawOutputStored", false).put("businessChainVerified", false).put("publishEligible", false);
            receipt.set("actions", sessionActions.get(job).deepCopy());
            receipt.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
            row.set("sessionActions", sessionActions.get(job).deepCopy());
            row.set("result", result);
            return row.deepCopy();
        }
        if (operation.equals("cancel")) {
            results.remove(job);
            sessionCalls.remove(job);
            sessionOrdinals.remove(job);
            sessionActions.remove(job);
            var row = (ObjectNode) rows.computeIfAbsent(job, ignored -> row(job));
            row.put("status", "CANCELLED").putObject("cleanup")
                    .put("confirmed", !fault.get().equals("cleanup"))
                    .put("containerAbsent", !fault.get().equals("cleanup"));
            return row.deepCopy();
        }
        ObjectNode row = (ObjectNode) rows.get(job);
        assertNotNull(row);
        if (operation.equals("status")) {
            row.put("status", "COMPLETED").putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
            ObjectNode response = row.deepCopy();
            switch (fault.get()) {
                case "row_job" -> response.put("jobId", "wrong");
                case "row_source" -> response.put("sourceSha256", "0".repeat(64));
                case "row_call" -> response.put("t4CallSha256", "0".repeat(64));
                case "row_policy" -> response.put("executionPolicySha256", "0".repeat(64));
                case "unclean" -> response.putObject("cleanup").put("confirmed", false).put("containerAbsent", false);
                case "verdict" -> ((ObjectNode) response.path("result").path("t4RpcValidation"))
                        .put("businessChainVerified", true);
                case "row_parent" -> response.put("parentJobId", "wrong");
                case "row_profile" -> response.put("parentProfileSha256", "0".repeat(64));
                case "row_data" -> ((ObjectNode) response.path("result").path("t4RpcValidation"))
                        .put("sourceDataSha256", "0".repeat(64));
                case "row_data_flag" -> ((ObjectNode) response.path("result").path("t4RpcValidation"))
                        .put("sourceDataPresent", !response.path("result").path("t4RpcValidation")
                                .path("sourceDataPresent").asBoolean());
                case "session_missing" -> ((ObjectNode) response.path("result")).remove("t4SessionValidation");
                case "session_actions" -> ((ObjectNode) response.path("result").path("t4SessionValidation"))
                        .putArray("actions");
                case "session_journal_actions" -> response.putArray("sessionActions");
                case "session_destroy" -> ((ObjectNode) response.path("result").path("t4SessionValidation"))
                        .put("destroyReturned", false);
                case "session_init" -> ((ObjectNode) response.path("result").path("t4SessionValidation"))
                        .put("initCount", 2);
                case "session_text" -> ((ObjectNode) response.path("result").path("t4SessionValidation"))
                        .put("loadedTextSha256", "0".repeat(64));
                case "session_receipt_cleanup" -> ((ObjectNode) response.path("result").path("t4SessionValidation"))
                        .putObject("cleanup").put("confirmed", false).put("containerAbsent", false);
                case "session_cancelled" -> response.put("status", "CANCELLED");
            }
            return response;
        }
        assertEquals("take", operation);
        assertEquals(row.path("sourceSha256"), request.path("sourceSha256"));
        assertEquals(row.path("t4CallSha256"), request.path("t4CallSha256"));
        var delivery = JSON.createObjectNode().put("schemaVersion", 1)
                .put("authority", "host_t4_rpc_transport_observed").put("jobId", job)
                .put("sourceSha256", sha(source)).put("runtimeSha256", RUNTIME)
                .put("t4CallSha256", row.path("t4CallSha256").asText())
                .put("resultSha256", row.path("result").path("t4RpcValidation").path("resultSha256").asText())
                .put("evidenceClass", "untrusted_source_output").put("businessChainVerified", false)
                .put("publishEligible", false);
        delivery.set("result", results.remove(job));
        if (row.has("parentJobId")) {
            delivery.set("parentJobId", row.path("parentJobId"));
            delivery.set("parentProfileSha256", row.path("parentProfileSha256"));
        }
        if (fault.get().equals("take_hash")) delivery.put("resultSha256", "0".repeat(64));
        if (fault.get().equals("take_runtime")) delivery.put("runtimeSha256", "0".repeat(64));
        if (fault.get().equals("take_parent")) delivery.put("parentJobId", "wrong");
        if (fault.get().equals("take_profile")) delivery.put("parentProfileSha256", "0".repeat(64));
        return delivery;
    }

    ObjectNode configuration() {
        return JSON.createObjectNode().put("instanceId", INSTANCE).put("imageId", IMAGE)
                .put("bundleSha256", BUNDLE).put("egressPolicySha256", EGRESS);
    }
    ObjectNode row(String job) {
        return configuration().put("schemaVersion", 1).put("authority", "managed_source_broker_observed")
                .put("jobId", job).put("executionPolicySha256", POLICY).put("businessChainVerified", false)
                .put("publishEligible", false).put("status", "CANCELLED");
    }
    static Set<String> keys(JsonNode value) {
        var keys = new HashSet<String>();
        value.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public long count(String operation) {
        return requests.stream().filter(row -> row.path("operation").asText().equals(operation)).count();
    }

    @AfterEach public void stopPeer() throws Exception {
        try {
            for (var client : clients) client.close();
        } finally {
            stopping.set(true);
            if (server != null) server.close();
            for (var channel : channels) channel.close();
            if (acceptor != null) acceptor.get(3, TimeUnit.SECONDS);
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(3, TimeUnit.SECONDS));
            Files.deleteIfExists(socket);
            Files.delete(directory);
        }
        assertNull(peerFailure.get(), "Authored Unix peer failed");
    }
}
