package cn.har01d.maintenance.lab;

import cn.har01d.alist_tvbox.service.ManagedT4BrokerPeer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Real AT HTTP/H2 and an authored broker peer, not a real site or source execution. */
class HttpAcceptanceDriverTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path work;

    private static String profile() {
        var profile = JSON.createObjectNode().put("schemaVersion", 1).put("runtime", "t4")
                .put("runtimeRevision", "c".repeat(64));
        profile.putArray("requiredNodes").add("R04");
        return profile.toString();
    }

    private static ObjectNode response(JsonNode call, ObjectNode ignored) {
        var value = JSON.createObjectNode();
        switch (call.path("method").asText()) {
            case "home" -> value.putArray("class").addObject()
                    .put("type_id", "fixture-category").put("type_name", "Fixture");
            case "category" -> {
                String page = call.path("params").path("pg").asText();
                value.put("page", page).putArray("list").addObject()
                        .put("vod_id", "fixture-" + page).put("vod_name", "Fixture");
            }
            case "detail" -> value.putArray("list").addObject()
                    .put("vod_id", call.path("params").path("ids").asText()).put("vod_name", "Fixture")
                    .put("vod_play_from", "fixture-line").put("vod_play_url", "Episode$fixture-episode");
            case "search" -> value.putArray("list");
            case "play" -> value.put("parse", 0).put("url", ignored.path("url").asText());
            case "proxy" -> { return ignored; }
            default -> throw new AssertionError("Unexpected fixture method");
        }
        return value;
    }

    @Test
    void currentAtOwnersServeAllHttpShapesWithoutLabConfigurationsOrHostSourceExecution() throws Exception {
        var peer = new ManagedT4BrokerPeer();
        peer.outputTransform = HttpAcceptanceDriverTest::response;
        var driver = new ManagedT4HttpAcceptance();
        peer.startPeer();
        try {
            driver.start(peer.plan(profile()), work, peer.socketPath());
            driver.runMatrix();
            var observation = driver.observation();
            assertEquals(36, observation.path("calls").size());
            assertEquals(2, observation.path("sessionReceipts").size());
            assertEquals(0, observation.path("proxyCalls").size());
            assertFalse(observation.path("publishEligible").asBoolean(true));
            assertEquals(2, peer.count("start_session"));
            assertEquals(2, peer.count("session_close"));
        } finally {
            try { driver.stop(); } finally { peer.stopPeer(); }
        }
        assertTrue(driver.cleanupConfirmed());
    }

    @Test
    void currentAtCallbackServesGetAndHeadInTheSameTwoSourceSessions() throws Exception {
        var peer = new ManagedT4BrokerPeer();
        peer.fault.set("session_proxy");
        peer.outputTransform = HttpAcceptanceDriverTest::response;
        var driver = new ManagedT4HttpAcceptance();
        peer.startPeer();
        try {
            driver.start(peer.plan(profile()), work, peer.socketPath());
            driver.runMatrix();
            var observation = driver.observation();
            assertEquals(36, observation.path("calls").size());
            assertEquals(12, observation.path("proxyCalls").size());
            for (var session : observation.path("sessionReceipts"))
                assertEquals(24, session.path("actions").size());
            for (int index = 0; index < 12; index++) {
                var proxy = observation.path("proxyCalls").get(index);
                assertEquals(index % 2 == 0 ? "GET" : "HEAD", proxy.path("httpMethod").asText());
                assertEquals(index % 2 == 0 ? 564 : 0, proxy.path("responseBytes").asInt());
            }
            assertEquals(2, peer.count("start_session"));
            assertEquals(2, peer.count("session_close"));
        } finally {
            try { driver.stop(); } finally { peer.stopPeer(); }
        }
        assertTrue(driver.cleanupConfirmed());
    }

    @Test
    void transportFixtureOutputIsNotAcceptedAsASourceResultAndItsSessionIsClosed() throws Exception {
        var peer = new ManagedT4BrokerPeer();
        var driver = new ManagedT4HttpAcceptance();
        peer.startPeer();
        try {
            driver.start(peer.plan(profile()), work, peer.socketPath());
            var failure = assertThrows(IllegalStateException.class, driver::runMatrix);
            assertEquals("HTTP_RESULT_INVALID", failure.getMessage());
        } finally {
            try { driver.stop(); } finally { peer.stopPeer(); }
        }
        assertTrue(driver.cleanupConfirmed());
    }
}
