package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManagedLoaderReceiptTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedLoaderReceipt receipts = new ManagedLoaderReceipt(mapper);
    private final String source = "a".repeat(64), worker = "b".repeat(64);

    @Test
    void projectsPlayerOutputShapesOnlyWhenBoundToSuccessfulDetailAndPlayCalls() throws Exception {
        var profile = profile();
        profile.withArray("requiredNodes").add("R01");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("R01");
        var node = input.withArray("nodes").addObject().put("id", "R01").put("status", "blocked")
                .put("reason", "PLAYER_URL_INVALID");
        var contract = node.putObject("playerContract").put("detailCallId", 2).put("playCallId", 3)
                .put("selectedIdentifierShape", "opaque").put("returnedUrlShape", "opaque")
                .put("returnedUrlType", "string").put("returnedUrlBytes", 55)
                .put("returnedUrlMatchesIdentifier", true).put("parseMode", "direct")
                .put("hasWhitespace", false).put("hasFragment", false).put("hasUserInfo", false)
                .put("hasHostname", false).put("rawUrl", "PRIVATE");
        var calls = input.withArray("calls");
        for (String method : List.of("detail", "play")) {
            int callId = calls.size() + 1;
            calls.addObject().put("id", callId).put("method", method).put("ok", true)
                    .put("inputSha256", "d".repeat(64)).put("outputSha256", "e".repeat(64)).put("elapsedMillis", 1);
        }
        input.put("callsSha256", Hashes.sha256(mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(mapper.convertValue(calls, Object.class))));
        var projected = receipts.project(input, profile, source, source, worker).path("nodes").get(4);
        assertEquals("blocked", projected.path("status").asText());
        assertEquals("opaque", projected.path("playerContract").path("returnedUrlShape").asText());
        assertEquals(12, projected.path("playerContract").size());
        assertFalse(projected.toString().contains("PRIVATE"));
        assertEquals(projected.path("playerContract"), ManagedLoaderReceipt.contractObservations(mapper, projected).path("playerContract"));
        for (String defect : List.of("wrongDetail", "wrongPlay", "badShape", "badType", "missingFlag", "negativeSize")) {
            var changed = input.deepCopy();
            var invalid = (ObjectNode) changed.path("nodes").get(4).path("playerContract");
            switch (defect) {
                case "wrongDetail" -> invalid.put("detailCallId", 1);
                case "wrongPlay" -> invalid.put("playCallId", 2);
                case "badShape" -> invalid.put("returnedUrlShape", "PRIVATE");
                case "badType" -> invalid.put("returnedUrlType", "PRIVATE");
                case "missingFlag" -> invalid.remove("hasHostname");
                default -> invalid.put("returnedUrlBytes", -1);
            }
            assertThrows(IOException.class, () -> receipts.project(changed, profile, source, source, worker), defect);
        }
        contract.remove("rawUrl");
    }

    @Test
    void projectsEpisodeFailureDetailsOnlyFromTheObservedDetailCallWithoutOpaqueValues() throws Exception {
        var profile = profile();
        profile.withArray("requiredNodes").add("C10");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("C10");
        var node = input.withArray("nodes").addObject().put("id", "C10").put("status", "fail")
                .put("reason", "PLAY_EPISODE_INVALID");
        var contract = node.putObject("playbackContract").put("detailCallId", 2).put("flagGroups", 2)
                .put("urlGroups", 2).put("groupIndex", 1).put("episodeIndex", 3).put("parsedEpisodes", 8)
                .put("groupsAligned", true).put("namePresent", true).put("separatorPresent", true)
                .put("identifierPresent", false).put("rawIdentifier", "PRIVATE");
        var calls = input.withArray("calls");
        calls.addObject().put("id", 2).put("method", "detail").put("ok", true)
                .put("inputSha256", "d".repeat(64)).put("outputSha256", "e".repeat(64)).put("elapsedMillis", 1);
        input.put("callsSha256", Hashes.sha256(mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(mapper.convertValue(calls, Object.class))));
        var projected = receipts.project(input, profile, source, source, worker).path("nodes").get(4);
        assertFalse(projected.path("playbackContract").path("identifierPresent").asBoolean(true));
        assertEquals(3, projected.path("playbackContract").path("episodeIndex").asInt());
        assertFalse(projected.toString().contains("PRIVATE"));
        assertEquals(projected.path("playbackContract"), ManagedLoaderReceipt.contractObservations(mapper, projected).path("playbackContract"));
        contract.put("detailCallId", 1);
        assertThrows(IOException.class, () -> receipts.project(input, profile, source, source, worker));
    }

    @Test
    void bindsHomeRecommendationVerdictToSuccessfulHomeAndDetailCalls() throws Exception {
        var profile = profile();
        profile.withArray("requiredNodes").add("C02");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("C02");
        var node = input.withArray("nodes").addObject().put("id", "C02").put("status", "pass")
                .put("reason", "HOME_RECOMMENDATION_TO_DETAIL_CONTRACT").put("items", 2)
                .put("homeCallId", 2).put("detailCallId", 3);
        node.putObject("identityEvidence").put("selectedIdSha256", source).put("returnedIdSha256", source)
                .put("exactMatch", true).put("guidCoverMatch", false).put("atvpDetailPrefixMatch", false)
                .put("nameMatch", true).put("coverMatch", false);
        var calls = input.withArray("calls");
        for (String method : List.of("home", "detail")) {
            int callId = calls.size() + 1;
            calls.addObject().put("id", callId).put("method", method).put("ok", true)
                    .put("inputSha256", "d".repeat(64)).put("outputSha256", "e".repeat(64)).put("elapsedMillis", 1);
        }
        input.put("callsSha256", Hashes.sha256(mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(mapper.convertValue(calls, Object.class))));
        var safe = receipts.project(input, profile, source, source, worker);
        assertEquals("pass", safe.path("nodes").get(4).path("status").asText());
        assertEquals(2, safe.path("nodes").get(4).path("items").asInt());
        assertEquals(2, safe.path("nodes").get(4).path("homeCallId").asInt());
        for (String defect : List.of("wrongHome", "wrongDetail", "reversed", "empty", "missingIdentity")) {
            var changed = input.deepCopy();
            var row = (ObjectNode) changed.path("nodes").get(4);
            switch (defect) {
                case "wrongHome" -> row.put("homeCallId", 1);
                case "wrongDetail" -> row.put("detailCallId", 2);
                case "reversed" -> row.put("homeCallId", 3);
                case "empty" -> row.put("items", 0);
                default -> row.remove("identityEvidence");
            }
            assertThrows(IOException.class, () -> receipts.project(changed, profile, source, source, worker), defect);
        }
    }

    @Test
    void projectsHlsResourcesAndEncryptedByteRangesWithoutRawKeysOrUrls() throws Exception {
        var profile = profile();
        profile.withArray("requiredNodes").add("M04").add("M05");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("M04").add("M05");
        var resources = input.withArray("nodes").addObject().put("id", "M04").put("status", "pass")
                .put("reason", "HLS_KEY_AND_INIT_RESOURCES_VERIFIED").put("keyBytesPersisted", false).putArray("resources");
        resources.addObject().put("kind", "key").put("declaration", 1).put("bytes", 16).put("status", 200)
                .put("urlSha256", "c".repeat(64)).put("responseSha256", "d".repeat(64)).put("key", "PRIVATE");
        resources.addObject().put("kind", "init").put("declaration", 1).put("bytes", 32).put("status", 206)
                .put("start", 100).put("end", 131).put("urlSha256", "e".repeat(64)).put("responseSha256", "f".repeat(64));
        var sample = input.withArray("nodes").addObject().put("id", "M05").put("status", "pass")
                .put("reason", "HLS_SEGMENT_SAMPLES_VERIFIED").put("fullSegments", false).put("clientPlaybackVerified", false)
                .putArray("samples").addObject().put("index", 0).put("bytes", 640).put("status", 206)
                .put("start", 4_000_000_000L).put("end", 4_000_000_639L).put("container", "fmp4")
                .put("clearBytes", 624).put("clearSha256", "e".repeat(64)).put("responseSha256", "f".repeat(64));
        sample.putObject("encryption").put("method", "AES-128").put("keyDeclaration", 1).put("ivSource", "explicit")
                .put("prefixOnly", false).put("paddingChecked", true).put("authenticated", false).put("iv", "PRIVATE");
        var safe = receipts.project(input, profile, source, source, worker);
        assertEquals("pass", safe.path("nodes").get(4).path("status").asText());
        var projected = safe.path("nodes").get(5).path("samples").get(0);
        assertEquals(4_000_000_000L, projected.path("start").longValue());
        assertEquals("fmp4", projected.path("container").asText());
        assertTrue(projected.path("encryption").path("paddingChecked").asBoolean());
        assertFalse(safe.toString().contains("PRIVATE"));
        for (String defect : List.of("persist", "range", "padding", "authenticated", "container", "keyBytes")) {
            var changed = input.deepCopy();
            var node = (ObjectNode) changed.path("nodes").get(5);
            var item = (ObjectNode) node.path("samples").get(0);
            switch (defect) {
                case "persist" -> ((ObjectNode) changed.path("nodes").get(4)).put("keyBytesPersisted", true);
                case "range" -> item.put("end", 4_000_000_640L);
                case "padding" -> ((ObjectNode) item.path("encryption")).put("prefixOnly", true);
                case "authenticated" -> ((ObjectNode) item.path("encryption")).put("authenticated", true);
                case "container" -> item.put("container", "html");
                default -> ((ObjectNode) changed.path("nodes").get(4).path("resources").get(0)).put("bytes", 17);
            }
            assertThrows(IOException.class, () -> receipts.project(changed, profile, source, source, worker), defect);
        }
    }

    @Test
    void preservesHlsResourceFailureReasonsWithoutPromotingTheSource() throws Exception {
        for (String reason : List.of("HLS_RANGE_RESPONSE_INVALID", "HLS_INIT_IV_REQUIRED", "HLS_DECRYPT_FAILED",
                "HLS_ENCRYPTION_UNSUPPORTED", "HLS_INIT_INCOMPLETE")) {
            var profile = profile();
            profile.withArray("requiredNodes").add("M04");
            var input = receipt();
            input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
            input.withArray("requiredNodes").add("M04");
            input.withArray("nodes").addObject().put("id", "M04").put("status", "blocked").put("reason", reason);
            var safe = receipts.project(input, profile, source, source, worker);
            assertEquals(reason, safe.path("nodes").get(4).path("reason").asText());
            assertFalse(safe.path("successful").asBoolean());
        }
    }

    @Test
    void projectsMp4RangeCoordinatesIncludingLargeAndShortResources() throws Exception {
        for (long total : List.of(12L, 65536L, 65537L, 4_000_000_000L)) {
            var profile = profile();
            profile.withArray("requiredNodes").add("M06");
            var input = receipt();
            input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
            input.withArray("requiredNodes").add("M06");
            var row = input.withArray("nodes").addObject().put("id", "M06").put("status", "pass")
                    .put("reason", "MP4_COHERENT_RANGE_SAMPLES").put("format", "mp4")
                    .put("totalBytes", total).put("clientPlaybackVerified", false).put("url", "PRIVATE");
            var samples = row.putArray("samples");
            var starts = new java.util.LinkedHashSet<Long>();
            starts.add(0L);
            starts.add(Math.max(0, (total - 65536) / 2));
            starts.add(Math.max(0, total - 65536));
            for (long start : starts) {
                long end = start + Math.min(65536, total - start) - 1;
                int index = samples.size();
                samples.addObject().put("index", index).put("start", start).put("end", end)
                        .put("bytes", end - start + 1).put("status", 206).put("responseSha256", "f".repeat(64));
            }
            var safe = receipts.project(input, profile, source, source, worker).path("nodes").get(4);
            assertEquals(total, safe.path("totalBytes").longValue());
            assertEquals(samples, safe.path("samples"));
            assertFalse(safe.path("clientPlaybackVerified").asBoolean());
            assertFalse(safe.toString().contains("PRIVATE"));
            for (String defect : List.of("missing", "total", "offset", "bytes", "status", "index", "client", "format")) {
                var changed = input.deepCopy();
                var node = (ObjectNode) changed.path("nodes").get(4);
                var first = (ObjectNode) node.path("samples").get(0);
                switch (defect) {
                    case "missing" -> node.remove("samples");
                    case "total" -> node.put("totalBytes", total + 1);
                    case "offset" -> first.put("start", 1);
                    case "bytes" -> first.put("bytes", 65537);
                    case "status" -> first.put("status", 200);
                    case "index" -> first.put("index", 1);
                    case "client" -> node.put("clientPlaybackVerified", true);
                    default -> node.put("format", "hls");
                }
                assertThrows(IOException.class, () -> receipts.project(changed, profile, source, source, worker),
                        total + ":" + defect);
            }
        }
    }

    @Test
    void preservesMp4RangeFailureAndCompletedSamplesWithoutLeakingRawHeaders() throws Exception {
        var profile = profile();
        profile.withArray("requiredNodes").add("M06");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("M06");
        var row = input.withArray("nodes").addObject().put("id", "M06").put("status", "blocked")
                .put("reason", "MP4_TOTAL_CHANGED").put("totalBytes", 262144).put("Content-Range", "PRIVATE");
        row.putArray("samples").addObject().put("index", 0).put("start", 0).put("end", 65535)
                .put("bytes", 65536).put("status", 206).put("responseSha256", "f".repeat(64));
        var safe = receipts.project(input, profile, source, source, worker);
        assertEquals("MP4_TOTAL_CHANGED", safe.path("nodes").get(4).path("reason").asText());
        assertEquals(row.path("samples"), safe.path("nodes").get(4).path("samples"));
        assertFalse(safe.path("successful").asBoolean());
        assertFalse(safe.toString().contains("PRIVATE"));
    }

    @Test
    void failedCallsKeepAnAllowlistedTypeWithoutRawExceptionText() throws Exception {
        var input = receipt();
        var init = (ObjectNode) input.path("nodes").get(0);
        init.put("status", "blocked").put("reason", "LOADER_INIT_FAILED")
                .put("failureType", "ModuleNotFoundError").put("message", "PRIVATE_SECRET");
        var call = (ObjectNode) input.path("calls").get(0);
        call.put("ok", false).put("failureType", "ModuleNotFoundError");
        input.put("callsSha256", Hashes.sha256(mapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .without(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(mapper.convertValue(input.path("calls"), Object.class))));
        var safe = project(input);
        assertEquals("ModuleNotFoundError", safe.path("nodes").get(0).path("failureType").asText());
        assertEquals("ModuleNotFoundError", safe.path("calls").get(0).path("failureType").asText());
        assertFalse(safe.toString().contains("PRIVATE_SECRET"));
        call.put("failureType", "PRIVATE_SECRET");
        assertThrows(IOException.class, () -> project(input));
    }

    @Test
    void projectsHashedDetailIdentityAndOfflineRouteWithoutRawIds() throws Exception {
        var profile = profile();
        profile.withArray("requiredNodes").add("C07").add("R01");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("C07").add("R01");
        var detail = input.withArray("nodes").addObject().put("id", "C07").put("status", "pass")
                .put("reason", "LIST_ID_TO_DETAIL_AND_INVALID_ID_REJECTED");
        detail.putObject("identityEvidence").put("selectedIdSha256", "1".repeat(64))
                .put("returnedIdSha256", "2".repeat(64)).put("exactMatch", false).put("guidCoverMatch", false)
                .put("atvpDetailPrefixMatch", true)
                .put("nameMatch", true).put("coverMatch", true);
        var player = input.withArray("nodes").addObject().put("id", "R01").put("status", "blocked")
                .put("reason", "PLAYER_OFFLINE_RESOLVER_REQUIRED").put("playerRouteKind", "magnet")
                .put("playerUrlSha256", "3".repeat(64)).put("offlineResolverRequired", true)
                .put("magnetInfoHashValid", true).put("url", "magnet:PRIVATE");
        var safe = receipts.project(input, profile, source, source, worker);
        assertEquals(detail.path("identityEvidence"), safe.path("nodes").get(4).path("identityEvidence"));
        assertEquals("magnet", safe.path("nodes").get(5).path("playerRouteKind").asText());
        assertEquals("PLAYER_OFFLINE_RESOLVER_REQUIRED", safe.path("nodes").get(5).path("reason").asText());
        assertFalse(safe.path("successful").asBoolean());
        assertFalse(safe.toString().contains("PRIVATE"));
        player.put("playerUrlSha256", "raw-url");
        assertThrows(IOException.class, () -> receipts.project(input, profile, source, source, worker));
        player.put("playerUrlSha256", "3".repeat(64));
        ((ObjectNode) detail.path("identityEvidence")).put("atvpDetailPrefixMatch", "yes");
        assertThrows(IOException.class, () -> receipts.project(input, profile, source, source, worker));
    }

    @Test
    void acceptsAtMagnetHandoffWithoutClaimingOfflineDownloadOrPlayback() throws Exception {
        var profile = profile();
        profile.withArray("requiredNodes").add("R01");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("R01");
        var row = input.withArray("nodes").addObject().put("id", "R01").put("status", "pass")
                .put("reason", "SELECTED_PLAY_ID_TO_AT_OFFLINE_CONTRACT").put("playerRouteKind", "magnet")
                .put("playerUrlSha256", "3".repeat(64)).put("offlineResolverRequired", true)
                .put("magnetInfoHashValid", true).put("offlineDownloadVerified", false)
                .put("clientPlaybackVerified", false).put("url", "magnet:PRIVATE");
        var safe = receipts.project(input, profile, source, source, worker);
        var projected = safe.path("nodes").get(4);
        assertEquals("pass", projected.path("status").asText());
        assertFalse(projected.path("offlineDownloadVerified").asBoolean());
        assertFalse(projected.path("clientPlaybackVerified").asBoolean());
        assertFalse(safe.toString().contains("PRIVATE"));
        for (String field : List.of("offlineDownloadVerified", "clientPlaybackVerified",
                "magnetInfoHashValid", "offlineResolverRequired", "playerRouteKind", "playerUrlSha256")) {
            var changed = input.deepCopy();
            var node = (ObjectNode) changed.path("nodes").get(4);
            node.remove(field);
            assertThrows(IOException.class, () -> receipts.project(changed, profile, source, source, worker), field);
        }
        row.put("magnetInfoHashValid", false);
        assertThrows(IOException.class, () -> receipts.project(input, profile, source, source, worker));
    }

    @Test
    void projectsTheFrozenFullNodeSetWithoutRawOutputOrAClaimedSuccess() throws Exception {
        var input = receipt();
        input.put("successful", true).put("url", "https://PRIVATE/?token=PRIVATE");
        ((ObjectNode) input.path("nodes").get(1)).put("rawResult", "PRIVATE");
        ((ObjectNode) input.path("nodes").get(2)).put("reason", "PRIVATE_UPPERCASE_SECRET");
        var value = project(input);
        assertEquals(4, value.path("nodes").size());
        assertEquals("LOADER_NODE_UNRESOLVED", value.path("nodes").get(2).path("reason").asText());
        assertFalse(value.path("successful").asBoolean());
        assertEquals(ManagedLoaderReceipt.PROJECTION, value.path("projection").asText());
        assertFalse(value.toString().contains("PRIVATE"));
        assertFalse(value.path("publishEligible").asBoolean());
    }

    @Test
    void pageWindowPassIsBoundToItsFrozenModeAndKeepsCoverageLimits() throws Exception {
        var profile = profile().put("paginationMode", "page-window");
        profile.withArray("requiredNodes").add("C03");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("C03");
        input.withArray("nodes").addObject().put("id", "C03").put("status", "pass")
                .put("reason", "FIRST_SECOND_PAGE_WINDOW_CONTRACT").put("paginationMode", "page-window")
                .put("pagesObserved", 2).put("firstItems", 20).put("secondItems", 20)
                .put("exactTotalVerified", false).put("lastPageVerified", false)
                .put("pageNumbersVerified", false);
        var safe = receipts.project(input, profile, source, source, worker);
        var row = safe.path("nodes").get(4);
        assertEquals("page-window", row.path("paginationMode").asText());
        assertFalse(row.path("exactTotalVerified").asBoolean());
        assertFalse(row.path("lastPageVerified").asBoolean());
        assertTrue(row.has("pageNumbersVerified"));
        assertFalse(row.path("pageNumbersVerified").asBoolean());
        for (String defect : List.of("exactTotalVerified", "lastPageVerified", "pageNumbersVerified",
                "pagesObserved", "reason")) {
            var changed = input.deepCopy();
            var changedRow = (ObjectNode) changed.path("nodes").get(4);
            if (defect.equals("reason")) changedRow.put("reason", "FIRST_SECOND_LAST_PAGE_CONTRACT");
            else if (defect.equals("pageNumbersVerified")) changedRow.put(defect, "unknown");
            else if (defect.equals("pagesObserved")) changedRow.put("pagesObserved", 3);
            else changedRow.put(defect, true);
            assertThrows(IOException.class, () -> receipts.project(changed, profile, source, source, worker));
        }
        var exact = profile.deepCopy().put("paginationMode", "exact-total");
        input.put("inputProfileSha256", Hashes.sha256(exact.toString()));
        assertThrows(IOException.class, () -> receipts.project(input, exact, source, source, worker));
    }

    @Test
    void knownHeaderOriginDenialSurvivesProjectionWithoutRevealingHeaderValues() throws Exception {
        var input = receipt();
        ((ObjectNode) input.path("nodes").get(2)).put("status", "blocked")
                .put("reason", "EGRESS_HEADER_ORIGIN_DENIED").put("headerValue", "PRIVATE_ORIGIN");
        var safe = project(input);
        assertEquals("EGRESS_HEADER_ORIGIN_DENIED", safe.path("nodes").get(2).path("reason").asText());
        assertFalse(safe.toString().contains("PRIVATE_ORIGIN"));
        assertFalse(safe.path("successful").asBoolean());
    }

    @Test
    void privateAggregatesStaleProfilesAndChangedSourceRuntimeOrLoadedTextAreRejected() throws Exception {
        for (String field : List.of("authority", "scope", "inputSourceSha256", "loadedTextSha256", "runtimeSha256",
                "inputProfileSha256", "requirementsFrozen", "profileSha256")) {
            var input = receipt();
            if (field.equals("requirementsFrozen")) input.put(field, "true");
            else input.put(field, "wrong");
            assertThrows(IOException.class, () -> project(input), field);
        }
        var input = receipt();
        input.put("authority", "private_sample_host_acceptance");
        assertThrows(IOException.class, () -> project(input));
        var changed = profile();
        changed.withArray("requiredNodes").add("C07");
        assertThrows(IOException.class, () -> receipts.project(receipt(), changed, source, source, worker));
    }

    @Test
    void missingReorderedDuplicateAndUnsupportedPassesNeverBecomeVerifiedEvidence() throws Exception {
        for (String defect : List.of("missing", "reorder", "duplicate", "unsupported", "passReason", "status")) {
            var input = receipt();
            switch (defect) {
                case "missing" -> input.withArray("nodes").remove(3);
                case "reorder" -> input.withArray("requiredNodes").remove(0);
                case "duplicate" -> ((ObjectNode) input.path("nodes").get(1)).put("id", "S06");
                case "unsupported" -> ((ObjectNode) input.path("nodes").get(2))
                        .put("status", "pass").put("reason", "SELF_REPORTED_PASS");
                case "passReason" -> ((ObjectNode) input.path("nodes").get(1)).put("reason", "PRIVATE");
                case "status" -> ((ObjectNode) input.path("nodes").get(1)).put("status", "success");
            }
            assertThrows(IOException.class, () -> project(input), defect);
        }
    }

    @Test
    void callsAreContiguousBoundedHashedAndRequiredForInitialization() throws Exception {
        for (String defect : List.of("id", "method", "hash", "time", "boolean", "empty", "digest", "init")) {
            var input = receipt();
            var call = (ObjectNode) input.path("calls").get(0);
            switch (defect) {
                case "id" -> call.put("id", 2);
                case "method" -> call.put("method", "PRIVATE");
                case "hash" -> call.put("inputSha256", "invalid");
                case "time" -> call.put("elapsedMillis", 1.5);
                case "boolean" -> call.put("ok", "true");
                case "empty" -> input.withArray("calls").removeAll();
                case "digest" -> input.put("callsSha256", "c".repeat(64));
                case "init" -> call.put("method", "home");
            }
            assertThrows(IOException.class, () -> project(input), defect);
        }
    }

    @Test
    void hostPrettyPrintingDoesNotChangeTheRuntimeCallsDigest() throws Exception {
        var input = receipt();
        var hostMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        var safe = new ManagedLoaderReceipt(hostMapper).project(input, profile(), source, source, worker);
        assertEquals(input.path("callsSha256"), safe.path("callsSha256"));
        input.put("callsSha256", "0".repeat(64));
        assertThrows(IOException.class, () -> new ManagedLoaderReceipt(hostMapper)
                .project(input, profile(), source, source, worker));
    }

    @Test
    void initializedDependencyAndConfigurationContractsRemainBoundToTheSameReceipt() throws Exception {
        var profile = profile();
        profile.withArray("requiredNodes").add("S04").add("S05");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("S04").add("S05");
        input.withArray("nodes").addObject().put("id", "S04").put("status", "pass")
                .put("reason", "UNCONDITIONAL_IMPORTS_LOADED_IN_PINNED_IMAGE").put("importStatements", 6)
                .put("dynamicImportCoverage", false);
        input.withArray("nodes").addObject().put("id", "S05").put("status", "pass")
                .put("reason", "NO_USER_CONFIGURATION_REQUIRED_INIT_OBSERVED").put("userExtRequired", false)
                .put("credentialUse", false).put("generatedRawExtObserved", true);
        var safe = receipts.project(input, profile, source, source, worker);
        assertEquals(6, safe.path("nodes").get(4).path("importStatements").asInt());
        assertFalse(safe.path("nodes").get(5).path("credentialUse").asBoolean());
        assertFalse(safe.path("successful").asBoolean());
    }

    @Test
    void coldCacheComparisonRequiresDistinctWorkersAndHomeIdentity() throws Exception {
        var input = receipt();
        var cold = (ObjectNode) input.path("nodes").get(3);
        cold.put("status", "pass").put("reason", "FRESH_TMPFS_WORKERS_MATCHED_HOT_HOME")
                .put("hotHomeCategoriesSha256", "f".repeat(64)).put("persistentCacheConfigured", false);
        cold.putArray("workerContainerIds").add("1".repeat(64)).add("2".repeat(64));
        assertEquals(2, project(input).path("nodes").get(3).path("workerContainerIds").size());
        for (String defect : List.of("same", "hash", "persistent", "missing")) {
            var changed = input.deepCopy();
            var row = (ObjectNode) changed.path("nodes").get(3);
            switch (defect) {
                case "same" -> row.withArray("workerContainerIds").set(1, mapper.getNodeFactory().textNode("1".repeat(64)));
                case "hash" -> row.put("hotHomeCategoriesSha256", "invalid");
                case "persistent" -> row.put("persistentCacheConfigured", true);
                case "missing" -> row.remove("workerContainerIds");
            }
            assertThrows(IOException.class, () -> project(changed), defect);
        }
    }

    @Test
    void lifecyclePassRequiresActualHttpAndCleanupForEveryOrderedScenario() throws Exception {
        var profile = profile();
        profile.withArray("requiredNodes").add("R09").add("S07");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()));
        input.withArray("requiredNodes").add("R09").add("S07");
        var cleanup = input.withArray("nodes").addObject().put("id", "R09").put("status", "pass")
                .put("reason", "NORMAL_CANCEL_TIMEOUT_EXIT_CLEANUP_OBSERVED").put("normalCleanupConfirmed", true);
        var scenarios = cleanup.putArray("scenarios");
        int ordinal = 1;
        for (String scenario : List.of("cancel", "timeout", "exit")) {
            var entry = scenarios.addObject().put("scenario", scenario).put("httpCount", 1)
                    .put("containerId", Integer.toString(ordinal++).repeat(64))
                    .put("code", scenario.equals("cancel") ? "MANAGED_CANCELLED"
                            : scenario.equals("timeout") ? "ENGINE_TIMEOUT" : "EGRESS_PROTOCOL_INVALID");
            entry.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        }
        var protocol = input.withArray("nodes").addObject().put("id", "S07").put("status", "pass")
                .put("reason", "JSONL_ERRORS_RECOVERY_TIMEOUT_EXIT_OBSERVED").put("strictFrameParsing", true)
                .put("protocolChecksPassed", true).put("rejectedRequests", 3);
        protocol.set("scenarios", scenarios.deepCopy());
        assertEquals(3, receipts.project(input, profile, source, source, worker)
                .path("nodes").get(4).path("scenarios").size());
        for (String defect : List.of("http", "cleanup", "same", "order", "code", "missing")) {
            var changed = input.deepCopy();
            var row = (ObjectNode) changed.path("nodes").get(4);
            var entry = (ObjectNode) row.path("scenarios").get(1);
            switch (defect) {
                case "http" -> entry.put("httpCount", 0);
                case "cleanup" -> ((ObjectNode) entry.path("cleanup")).put("containerAbsent", false);
                case "same" -> entry.put("containerId", "1".repeat(64));
                case "order" -> entry.put("scenario", "exit");
                case "code" -> entry.put("code", "MANAGED_CANCELLED");
                case "missing" -> row.withArray("scenarios").remove(2);
            }
            assertThrows(IOException.class, () -> receipts.project(changed, profile, source, source, worker), defect);
        }
    }

    private ObjectNode project(ObjectNode input) throws IOException {
        return receipts.project(input, profile(), source, source, worker);
    }

    @Test
    void driverFailureIsDiagnosticOnlyAndCannotTurnAnUnknownFaultIntoASourceRepair() throws Exception {
        var fixture = httpSessions(true);
        var phase = fixture[1].put("status", "FAILED").put("code", "T4_HTTP_DRIVER_FAILED");
        phase.remove("observation");
        var driver = phase.putObject("driverFailure").put("schemaVersion", 1)
                .put("authority", "at_t4_http_driver_observed").put("code", "T4_BROKER_TIMEOUT")
                .put("completedActions", 0).put("cleanupConfirmed", true);
        receipts.applyHttp(fixture[0], phase, fixture[2]);
        assertEquals("blocked", fixture[0].path("nodes").get(0).path("status").asText());
        assertEquals(driver, fixture[0].path("httpDriverFailure"));
        assertEquals(driver, fixture[0].path("nodes").get(0).path("driverFailure"));
        assertFalse(fixture[0].path("successful").asBoolean());
        for (String defect : List.of("code", "actions", "raw")) {
            var changed = phase.deepCopy();
            var diagnostic = (ObjectNode) changed.path("driverFailure");
            if (defect.equals("code")) diagnostic.put("code", "PRIVATE_UPPERCASE_SECRET");
            else if (defect.equals("actions")) diagnostic.put("completedActions", 37);
            else diagnostic.put("rawError", "PRIVATE");
            assertThrows(IOException.class, () -> receipts.applyHttp(httpSessions(true)[0], changed, fixture[2]));
        }
    }

    @Test
    void currentBoundedSessionsAndEveryProxyPairCanProjectR04AndR05() throws Exception {
        var fixture = httpSessions(true);
        receipts.applyHttp(fixture[0], fixture[1], fixture[2]);
        assertEquals("pass", fixture[0].path("nodes").get(0).path("status").asText());
        assertEquals("pass", fixture[0].path("nodes").get(1).path("status").asText());
        assertTrue(fixture[0].path("successful").asBoolean());
        assertFalse(fixture[0].path("nodes").get(1).path("clientPlaybackVerified").asBoolean());
    }

    @Test
    void manifestAndBoundSegmentPairsCanProjectR05WithoutClaimingClientPlayback() throws Exception {
        var fixture = httpSessions(true, true);
        receipts.applyHttp(fixture[0], fixture[1], fixture[2]);
        assertTrue(fixture[0].path("successful").asBoolean());
        assertEquals(24, fixture[0].path("t4HttpObservation").path("sessionReceipts").get(0).path("actions").size());
        assertFalse(fixture[0].path("nodes").get(1).path("clientPlaybackVerified").asBoolean());
    }

    @Test
    void hlsProjectionRejectsMissingRewriteIdentityAndUnboundOrHeadSegments() throws Exception {
        for (String defect : List.of("source", "size", "target", "head", "player", "raw")) {
            var fixture = httpSessions(true, true);
            var proxies = fixture[1].path("observation").path("proxyCalls");
            var manifest = (ObjectNode) proxies.get(0);
            var segment = (ObjectNode) proxies.get(1);
            switch (defect) {
                case "source" -> manifest.remove("sourceBodySha256");
                case "size" -> manifest.put("sourceBytes", 524289);
                case "target" -> segment.put("targetSha256", "0".repeat(64));
                case "head" -> segment.put("httpMethod", "HEAD");
                case "player" -> segment.put("playerOrdinal", 12);
                case "raw" -> manifest.put("body", "PRIVATE");
            }
            assertThrows(IOException.class, () -> receipts.applyHttp(fixture[0], fixture[1], fixture[2]), defect);
        }
    }

    @Test
    void matchingSessionConfigurationCannotSubstituteForTheFrozenLoaderConfiguration() throws Exception {
        var fixture = httpSessions(true);
        for (var session : fixture[1].path("observation").path("sessionReceipts")) {
            ((ObjectNode) session).put("sourceDataPresent", true).put("sourceDataSha256", "8".repeat(64));
        }
        assertThrows(IOException.class, () -> receipts.applyHttp(fixture[0], fixture[1], fixture[2]));
        fixture[0].put("sourceDataPresent", true).put("sourceDataSha256", "8".repeat(64));
        receipts.applyHttp(fixture[0], fixture[1], fixture[2]);
        assertTrue(fixture[0].path("successful").asBoolean());
    }

    @Test
    void projectionRequiresTheCurrentRuntimeConfigDigestAndPersistsNoRawSettings() throws Exception {
        var profile = profile();
        profile.putObject("publicSourceData").put("label", "STATIC_IDENTITY_SENTINEL")
                .put("unicode", "\u6d4b\u8bd5 \uD83D\uDE00");
        var data = profile.putObject("sourceConfig").put("label", "RUNTIME_CONFIG")
                .put("unicode", "\u6d4b\u8bd5 \uD83D\uDE00");
        var input = receipt();
        input.put("inputProfileSha256", Hashes.sha256(profile.toString()))
                .put("sourceDataPresent", true).put("sourceDataSha256", SourceConfig.sha256(data));
        var safe = receipts.project(input, profile, source, source, worker);
        assertTrue(safe.path("sourceDataPresent").asBoolean());
        assertFalse(safe.toString().contains("STATIC_IDENTITY_SENTINEL"));
        for (String defect : List.of("missing", "digest", "present")) {
            var changed = input.deepCopy();
            if (defect.equals("missing")) changed.remove("sourceDataSha256");
            else if (defect.equals("digest")) changed.put("sourceDataSha256", "0".repeat(64));
            else changed.put("sourceDataPresent", false);
            assertThrows(IOException.class, () -> receipts.project(changed, profile, source, source, worker));
        }
    }

    @Test
    void typedRuntimeConfigurationUsesItsOwnDigestAndExplicitEmptyNeverLeaksStaticIdentity() throws Exception {
        for (var data : List.of(mapper.createObjectNode(), mapper.createObjectNode()
                .put("proxy_gateway", "https://gateway.example.test").put("gateway_timeout", 30)
                .put("subtitle_enabled", false))) {
            var profile = profile();
            profile.putObject("publicSourceData").put("label", "STATIC_IDENTITY_SENTINEL");
            profile.set("sourceConfig", data);
            var input = receipt();
            input.put("inputProfileSha256", Hashes.sha256(profile.toString()))
                    .put("sourceDataPresent", !data.isEmpty())
                    .put("sourceDataSha256", SourceConfig.sha256(data));
            var safe = receipts.project(input, profile, source, source, worker);
            assertEquals(!data.isEmpty(), safe.path("sourceDataPresent").asBoolean());
            assertEquals(SourceConfig.sha256(data), safe.path("sourceDataSha256").asText());
            assertFalse(safe.toString().contains("STATIC_IDENTITY_SENTINEL"));
            assertFalse(safe.toString().contains("gateway.example.test"));
            input.put("sourceDataSha256", PublicSourceData.sha256(profile.path("publicSourceData")));
            assertThrows(IOException.class, () -> receipts.project(input, profile, source, source, worker));
        }
    }

    @Test
    void directSessionsDoNotInventProxyEvidenceOrPassR05() throws Exception {
        var fixture = httpSessions(false);
        receipts.applyHttp(fixture[0], fixture[1], fixture[2]);
        assertEquals("pass", fixture[0].path("nodes").get(0).path("status").asText());
        assertEquals("not_tested", fixture[0].path("nodes").get(1).path("status").asText());
        assertFalse(fixture[0].path("successful").asBoolean());
    }

    @Test
    void onlyTheHostVerifiedRegisteredQuerySeparatorMismatchBecomesARepairableR04Failure() throws Exception {
        var fixture = httpSessions(false);
        var phase = fixture[1];
        phase.put("status", "FAILED").put("code", "T4_HTTP_DRIVER_FAILED");
        var binding = fixture[2];
        var failure = phase.putObject("sourceRequestFailure").put("schemaVersion", 1)
                .put("authority", "host_registered_request_mismatch")
                .put("jobId", binding.path("jobId").asText())
                .put("instanceId", binding.path("instanceId").asText())
                .put("imageId", binding.path("imageId").asText())
                .put("bundleSha256", binding.path("bundleSha256").asText())
                .put("executionPolicySha256", binding.path("executionPolicySha256").asText())
                .put("egressPolicySha256", binding.path("egressPolicySha256").asText())
                .put("sourceSha256", binding.path("sourceSha256").asText())
                .put("inputProfileSha256", binding.path("inputProfileSha256").asText())
                .put("childJobId", "00000000-0000-4000-8000-000000000123")
                .put("childJournalSha256", "9".repeat(64)).put("currentChildJournalVerified", true)
                .put("httpId", 4).put("method", "GET").put("requestUrlSha256", "a".repeat(64))
                .put("registeredUrlSha256", "b".repeat(64)).put("registeredQuerySeparatorMissing", true);
        failure.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        receipts.applyHttp(fixture[0], phase, binding);
        assertEquals("fail", fixture[0].path("nodes").get(0).path("status").asText());
        assertEquals("SOURCE_REGISTERED_QUERY_SEPARATOR_MISSING",
                fixture[0].path("nodes").get(0).path("reason").asText());
        assertEquals(failure, fixture[0].path("sourceRequestFailure"));
        assertEquals("not_tested", fixture[0].path("nodes").get(1).path("status").asText());

        for (String defect : List.of("sameHash", "authority", "httpCode", "rawUrl", "unverifiedJournal")) {
            var changed = httpSessions(false);
            var changedPhase = changed[1].put("status", "FAILED").put("code", "T4_HTTP_DRIVER_FAILED");
            var changedFailure = changedPhase.putObject("sourceRequestFailure").put("schemaVersion", 1)
                    .put("authority", "host_registered_request_mismatch")
                    .put("jobId", changed[2].path("jobId").asText())
                    .put("instanceId", changed[2].path("instanceId").asText())
                    .put("imageId", changed[2].path("imageId").asText())
                    .put("bundleSha256", changed[2].path("bundleSha256").asText())
                    .put("executionPolicySha256", changed[2].path("executionPolicySha256").asText())
                    .put("egressPolicySha256", changed[2].path("egressPolicySha256").asText())
                    .put("sourceSha256", changed[2].path("sourceSha256").asText())
                    .put("inputProfileSha256", changed[2].path("inputProfileSha256").asText())
                    .put("childJobId", "00000000-0000-4000-8000-000000000123")
                    .put("childJournalSha256", "9".repeat(64)).put("currentChildJournalVerified", true)
                    .put("httpId", 4).put("method", "GET").put("requestUrlSha256", "a".repeat(64))
                    .put("registeredUrlSha256", "b".repeat(64)).put("registeredQuerySeparatorMissing", true);
            changedFailure.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
            switch (defect) {
                case "sameHash" -> changedFailure.put("requestUrlSha256", "b".repeat(64));
                case "authority" -> changedFailure.put("authority", "untrusted");
                case "httpCode" -> changedPhase.put("code", "EGRESS_URL_DENIED");
                case "rawUrl" -> changedFailure.put("url", "PRIVATE");
                case "unverifiedJournal" -> changedFailure.put("currentChildJournalVerified", false);
            }
            assertThrows(IOException.class,
                    () -> receipts.applyHttp(changed[0], changedPhase, changed[2]), defect);
        }
    }

    @Test
    void staleOrReorderedSessionMediaAndPrematureCleanupCannotProjectAFormalPass() throws Exception {
        for (String defect : List.of("journal", "actions", "init", "destroy", "text", "data", "cleanup",
                "mode", "ordinal", "result", "early", "media", "head", "range", "raw")) {
            var fixture = httpSessions(true);
            var phase = fixture[1];
            var observed = phase.withObject("observation");
            var session = (ObjectNode) observed.path("sessionReceipts").get(1);
            var call = (ObjectNode) observed.path("calls").get(0);
            var proxy = (ObjectNode) observed.path("proxyCalls").get(0);
            switch (defect) {
                case "journal" -> phase.put("currentChildJournalsVerified", false);
                case "actions" -> session.withArray("actions").remove(0);
                case "init" -> session.put("initCount", 2);
                case "destroy" -> session.put("destroyReturned", false);
                case "text" -> session.put("loadedTextSha256", "0".repeat(64));
                case "data" -> session.put("sourceDataSha256", "0".repeat(64));
                case "cleanup" -> session.withObject("cleanup").put("containerAbsent", false);
                case "mode" -> call.put("playType", "t4-direct");
                case "ordinal" -> call.put("sessionOrdinal", 2);
                case "result" -> call.put("resultSha256", "0".repeat(64));
                case "early" -> call.put("workerReturnedAfterCleanup", true);
                case "media" -> proxy.put("responseBytes", 563);
                case "head" -> ((ObjectNode) observed.path("proxyCalls").get(1)).put("responseSha256", "0".repeat(64));
                case "range" -> proxy.put("contentRange", "bytes 0-999/1000");
                case "raw" -> proxy.put("body", "PRIVATE");
            }
            assertThrows(IOException.class, () -> receipts.applyHttp(fixture[0], phase, fixture[2]), defect);
        }
    }

    private ObjectNode[] httpSessions(boolean proxies) {
        return httpSessions(proxies, false);
    }

    private ObjectNode[] httpSessions(boolean proxies, boolean hls) {
        var binding = mapper.createObjectNode().put("jobId", "00000000-0000-4000-8000-000000000001")
                .put("instanceId", "00000000-0000-4000-8000-000000000002")
                .put("imageId", "sha256:" + "c".repeat(64)).put("bundleSha256", "d".repeat(64))
                .put("runtimeSha256", worker).put("executionPolicySha256", "e".repeat(64))
                .put("egressPolicySha256", "f".repeat(64)).put("sourceSha256", source)
                .put("inputProfileSha256", "1".repeat(64));
        var loader = mapper.createObjectNode().put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputProfileSha256", binding.path("inputProfileSha256").asText())
                .put("inputSourceSha256", source).put("runtimeSha256", worker).put("loadedTextSha256", source)
                .put("sourceDataPresent", false).put("sourceDataSha256", Hashes.sha256("{}"));
        var nodes = loader.putArray("nodes");
        for (String id : List.of("R04", "R05")) nodes.addObject().put("id", id).put("status", "not_tested");
        var phase = mapper.createObjectNode().put("status", "COMPLETED").put("currentChildJournalsVerified", true);
        phase.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        var observed = binding.deepCopy().put("schemaVersion", 1).put("authority", "at_t4_http_owner_observed")
                .put("scope", "t4-http-parameters-v1").put("allPlannedCallsObserved", true)
                .put("rawOutputStored", false).put("formalAcceptanceIntegrated", false)
                .put("productNodePassesAdded", 0).put("publishEligible", false).put("sessionMode", true);
        phase.set("observation", observed);
        var calls = observed.putArray("calls");
        var proxyCalls = observed.putArray("proxyCalls");
        var sessions = observed.putArray("sessionReceipts");
        for (int segment = 0; segment < 2; segment++) {
            String job = String.format("00000000-0000-4000-8000-%012d", segment + 100);
            String mode = segment == 0 ? "t4" : "t4-direct";
            var session = sessions.addObject().put("brokerJobId", job).put("runtimeSha256", worker)
                    .put("loadedTextSha256", source).put("playType", mode).put("sourceDataPresent", false)
                    .put("sourceDataSha256", Hashes.sha256("{}")).put("initCount", 1).put("destroyReturned", true)
                    .put("initializationKind", "credential_free_local_source_projection")
                    .put("fullGeneratedExtForwarded", false);
            session.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
            var actions = session.putArray("actions");
            int ordinal = 0;
            for (int i = segment * 18; i < (segment + 1) * 18; i++) {
                String method = List.of("home", "category", "category", "detail", "search", "play").get(i % 6);
                sessionAction(actions, ++ordinal, method);
                calls.addObject().put("ordinal", i + 1).put("shape", List.of("get", "form", "json").get((i / 6) % 3))
                        .put("playType", mode).put("method", method).put("httpStatus", 200)
                        .put("t4CallSha256", "2".repeat(64)).put("brokerJobId", job).put("resultSha256", "3".repeat(64))
                        .put("httpResponseSha256", "4".repeat(64)).put("httpResponseBytes", 2)
                        .put("workerReturnedAfterCleanup", false).put("sessionOrdinal", ordinal).put("cleanupPending", true);
                if (!proxies || !method.equals("play")) continue;
                for (boolean head : List.of(false, true)) {
                    sessionAction(actions, ++ordinal, "proxy");
                    int proxyOrdinal = proxyCalls.size() + 1;
                    boolean manifest = hls && !head, noBody = head && !hls;
                    var proxy = proxyCalls.addObject().put("ordinal", proxyOrdinal).put("playerOrdinal", i + 1)
                            .put("brokerJobId", job).put("sessionOrdinal", ordinal)
                            .put("httpMethod", noBody ? "HEAD" : "GET").put("httpStatus", manifest ? 200 : 206)
                            .put("t4CallSha256", "2".repeat(64)).put("resultSha256", "3".repeat(64))
                            .put("targetSha256", "5".repeat(64))
                            .put("responseSha256", noBody ? Hashes.sha256(new byte[0]) : "6".repeat(64))
                            .put("responseBytes", noBody ? 0 : manifest ? 250000 : 564)
                            .put("contentLength", manifest ? 250000 : 564)
                            .put("mime", manifest ? "application/vnd.apple.mpegurl" : "video/mp2t")
                            .put("contentRange", manifest ? "" : "bytes 0-563/564");
                    if (manifest) proxy.put("sourceBodySha256", "8".repeat(64)).put("sourceBytes", 190000)
                            .put("segmentTargetSha256", "5".repeat(64));
                }
            }
        }
        return new ObjectNode[]{loader, phase, binding};
    }

    private void sessionAction(com.fasterxml.jackson.databind.node.ArrayNode actions, int ordinal, String method) {
        actions.addObject().put("ordinal", ordinal).put("method", method).put("t4CallSha256", "2".repeat(64))
                .put("paramsSha256", "7".repeat(64)).put("resultSha256", "3".repeat(64)).put("workerCallId", ordinal + 1);
    }

    private ObjectNode profile() {
        var value = mapper.createObjectNode().put("schemaVersion", 1).put("runtime", "t4");
        value.putArray("requiredNodes").add("C01").add("C05").add("S06").add("S08");
        return value;
    }

    private ObjectNode receipt() throws Exception {
        var value = mapper.createObjectNode().put("schemaVersion", 1).put("authority", "host_loader_contract_observed")
                .put("scope", ManagedLoaderReceipt.SCOPE).put("requirementsFrozen", true)
                .put("inputSourceSha256", source).put("loadedTextSha256", source).put("runtimeSha256", worker)
                .put("sourceDataPresent", false).put("sourceDataSha256", Hashes.sha256("{}"))
                .put("inputProfileSha256", Hashes.sha256(profile().toString())).put("profileSha256", "c".repeat(64));
        value.putArray("requiredNodes").add("S06").add("C01").add("C05").add("S08");
        var nodes = value.putArray("nodes");
        nodes.addObject().put("id", "S06").put("status", "pass").put("reason", "PRE_EXEC_SOURCE_SHA_AND_INIT_RETURNED");
        nodes.addObject().put("id", "C01").put("status", "pass").put("reason", "HOME_CATEGORY_CONTRACT").put("classes", 46);
        nodes.addObject().put("id", "C05").put("status", "not_tested").put("reason", "NODE_NOT_VALIDATED_BY_THIS_RUNNER");
        nodes.addObject().put("id", "S08").put("status", "not_tested").put("reason", "FRESH_WORKER_CACHE_COMPARISON_NOT_IN_SAMPLE_RUNNER");
        var calls = value.putArray("calls");
        calls.addObject().put("id", 1).put("method", "init").put("ok", true)
                .put("inputSha256", "d".repeat(64)).put("outputSha256", "e".repeat(64)).put("elapsedMillis", 1);
        value.put("callsSha256", Hashes.sha256(mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(mapper.convertValue(calls, Object.class))));
        return value;
    }
}
