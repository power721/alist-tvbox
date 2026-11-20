package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.time.Duration;

/** Internal same-host setup IPC. No SSH credentials or Docker socket enter the workbench. */
final class LocalSourceSetupClient {
    private LocalSourceSetupClient() { }

    static JsonNode call(ObjectMapper mapper, String endpoint, JsonNode request) throws IOException, InterruptedException {
        if (!endpoint.matches("/[A-Za-z0-9_./-]+/setup\\.sock") || endpoint.length() > 100
                || java.util.List.of(endpoint.split("/")).contains(".."))
            throw new IOException("SOURCE_SETUP_CONFIGURATION_INVALID");
        byte[] body = mapper.writeValueAsBytes(request);
        if (body.length > 1_500_000) throw new IOException("SOURCE_SETUP_REQUEST_INVALID");
        long deadline = System.nanoTime() + Duration.ofSeconds(
                "identity".equals(request.path("operation").asText()) ? 8 : 120).toNanos();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            if (!channel.connect(UnixDomainSocketAddress.of(endpoint)))
                while (!channel.finishConnect()) pause(deadline);
            ByteBuffer output = ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).flip();
            while (output.hasRemaining()) {
                if (channel.write(output) == 0) pause(deadline);
                check(deadline);
            }
            ByteBuffer length = ByteBuffer.allocate(4);
            read(channel, length, deadline);
            int size = length.flip().getInt();
            if (size <= 0 || size > 65_536) throw new IOException("SOURCE_SETUP_RECEIPT_INVALID");
            ByteBuffer input = ByteBuffer.allocate(size);
            read(channel, input, deadline);
            try (JsonParser parser = mapper.getFactory().createParser(input.array())) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode result = mapper.readTree(parser);
                if (result == null || !result.isObject() || !result.path("ok").isBoolean()
                        || parser.nextToken() != null) throw new IOException("SOURCE_SETUP_RECEIPT_INVALID");
                return result;
            }
        } catch (UnsupportedOperationException | java.nio.file.InvalidPathException error) {
            throw new IOException("SOURCE_SETUP_LOCAL_SOCKET_UNAVAILABLE", error);
        }
    }

    private static void read(SocketChannel channel, ByteBuffer input, long deadline)
            throws IOException, InterruptedException {
        while (input.hasRemaining()) {
            int count = channel.read(input);
            if (count == -1) throw new IOException("SOURCE_SETUP_RECEIPT_INVALID");
            if (count == 0) pause(deadline);
            check(deadline);
        }
    }

    private static void pause(long deadline) throws IOException, InterruptedException {
        check(deadline);
        Thread.sleep(10);
    }

    private static void check(long deadline) throws IOException, InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        if (System.nanoTime() >= deadline) throw new IOException("SOURCE_SETUP_LOCAL_TIMEOUT");
    }
}
