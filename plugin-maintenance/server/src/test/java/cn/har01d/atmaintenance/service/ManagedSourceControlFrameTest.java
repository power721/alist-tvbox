package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManagedSourceControlFrameTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void combinedReceiptStatusFramesAreReadWithoutChangingRpcLimits() throws Exception {
        byte[] reply = mapper.writeValueAsBytes(mapper.createObjectNode().put("ok", true)
                .set("result", mapper.createObjectNode().put("evidence", "x".repeat(70000))));
        for (String operation : List.of("status", "cancel", "cancel_acceptance", "start_http")) {
            assertEquals(70000, control(operation, reply, reply.length).path("evidence").asText().length());
        }
    }

    @Test
    void rpcAndOversizedStatusFramesRemainRejectedBeforeReadingBody() throws Exception {
        assertEquals("MANAGED_FRAME_LIMIT", assertThrows(IOException.class,
                () -> control("take", new byte[0], 70000)).getMessage());
        assertEquals("MANAGED_FRAME_LIMIT", assertThrows(IOException.class,
                () -> control("status", new byte[0], 525313)).getMessage());
    }

    private JsonNode control(String operation, byte[] reply, int declaredLength) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Thread peer = Thread.ofPlatform().start(() -> {
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream());
                     var output = new DataOutputStream(socket.getOutputStream())) {
                    input.readNBytes(input.readInt());
                    output.writeInt(declaredLength);
                    output.write(reply);
                    output.flush();
                } catch (IOException error) {
                    throw new AssertionError(error);
                }
            });
            try {
                var runtime = new ManagedSourceRuntime(mapper, new FixtureRuntime(mapper, "never-run"), true,
                        "tcp://127.0.0.1:" + server.getLocalPort(), "d0f4909a-5709-4e57-9a54-4ded3c19424a",
                        "sha256:" + "b".repeat(64), "", "");
                return runtime.control(mapper.createObjectNode().put("operation", operation));
            } finally {
                peer.join(5000);
                assertFalse(peer.isAlive());
            }
        }
    }
}
