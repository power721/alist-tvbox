package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionReplayTest {
    final ExtractionReplay replay = new ExtractionReplay(new ObjectMapper());
    final String sha = "a".repeat(64);
    ExtractionRule rule(String selector) {
        return new ExtractionRule("article.card", Map.of(
                "vod_id", new ExtractionField("", "data-id", List.of("trim")),
                "vod_name", new ExtractionField(selector, "text", List.of("trim", "collapseWhitespace")),
                "vod_pic", new ExtractionField("img", "src", List.of("trim"))), List.of("vod_id", "vod_name"));
    }
    String card(String id, String title, String image) {
        return "<article class=card data-id='" + id + "'><span class=title> " + title
                + " </span><span class=other>noise</span>" + (image.isEmpty() ? "" : "<img src='" + image + "'>") + "</article>";
    }
    Map<String, String> expected(String id, String title, String image) {
        return Map.of("vod_id", id, "vod_name", title, "vod_pic", image);
    }
    ExtractionPage page(String role, String html, List<Map<String, String>> expected) {
        return new ExtractionPage(role, html, Hashes.sha256(html), expected);
    }
    ExtractionReplayRequest input(ExtractionRule rule, String holdout) {
        return new ExtractionReplayRequest(sha, rule, List.of(
                page("original", card("1", "same", "p1") + card("2", "two", ""), List.of(
                        expected("1", "same", "p1"), expected("2", "two", ""))),
                page("holdout", holdout, List.of(expected("3", "same", "p3"), expected("4", "four", "")))),
                Map.of("parser", "b".repeat(64)));
    }
    String holdout() { return card("4", "four", "") + card("3", "same", "p3"); }

    @Test void fieldsCountsAreHostMeasuredAndReorderingUsesIdsNotPositionsOrTitles() {
        var result = replay.replay(input(rule("span.title"), holdout()));
        assertTrue(result.path("passed").asBoolean());
        var fields = result.path("pages").get(0).path("fieldCounts");
        var pic = fields.valueStream().filter(field -> field.path("field").asText().equals("vod_pic")).findFirst().orElseThrow();
        assertEquals(1, pic.path("matched").asInt());
        assertEquals(1, pic.path("filtered").asInt());
        assertFalse(result.toString().contains("noise"));
        assertFalse(result.toString().contains("p1"));
        assertEquals(2, result.path("pages").get(0).path("items").size());
    }
    @Test void deletionInsertionMissingFieldAndWrongSameTitleIdsAreNotGreen() {
        for (String html : List.of(card("4", "four", ""), holdout() + card("5", "new", "p"),
                card("4", "", "") + card("3", "same", "p3"),
                card("4", "same", "p3") + card("3", "four", ""))) {
            assertFalse(replay.replay(input(rule("span.title"), html)).path("passed").asBoolean());
        }
    }
    @Test void learnedSelectorNeedsIndependentPerFieldSuccessAndDoesNotExecutePageCode() {
        var input = input(rule("span.old"), holdout() + "<script>fetch('http://private/')</script>");
        var learned = replay.learn(new SelectorLearningRequest(input, "vod_name", "span.old"));
        assertEquals(1, learned.size());
        assertEquals("span.title", learned.getFirst().fields().get("vod_name").selector());
        assertThrows(IllegalArgumentException.class, () -> replay.learn(new SelectorLearningRequest(
                input(rule("span.old"), card("4", "WRONG", "") + card("3", "same", "p3")), "vod_name", "span.old")));
    }
    @Test void boundedXpathReplaysTextNodesAndLearnsAClassCandidateAcrossHoldout() {
        var xpathRule = rule("xpath:.//span[contains(@class,'title')]/text()");
        var input = input(xpathRule, holdout());
        var result = replay.replay(input);
        assertTrue(result.path("passed").asBoolean());
        var learnedInput = input(rule("xpath:.//span[contains(@class,'old')]/text()"), holdout());
        var learned = replay.learn(new SelectorLearningRequest(learnedInput, "vod_name",
                "xpath:.//span[contains(@class,'old')]/text()"));
        assertEquals("xpath:.//span[contains(@class,'title')]/text()",
                learned.getFirst().fields().get("vod_name").selector());
        assertTrue(replay.replay(new ExtractionReplayRequest(sha, learned.getFirst(),
                learnedInput.pages(), learnedInput.dependencySha256())).path("passed").asBoolean());
    }
    @Test void xpathRowsAttributesAndDirectTextRetainPerFieldCountsAndIdAlignment() {
        var xpathRule = new ExtractionRule("xpath://article[@class='card']", Map.of(
                "vod_id", new ExtractionField("xpath:@data-id", "data-id", List.of("trim")),
                "vod_name", new ExtractionField("xpath:./span[@class='title']/text()", "text",
                        List.of("trim", "collapseWhitespace")),
                "vod_pic", new ExtractionField("xpath:.//img/@src", "src", List.of("trim"))),
                List.of("vod_id", "vod_name"));
        var result = replay.replay(input(xpathRule, holdout()));
        assertTrue(result.path("passed").asBoolean());
        var counts = result.path("pages").get(0).path("fieldCounts");
        var name = counts.valueStream().filter(field -> field.path("field").asText().equals("vod_name"))
                .findFirst().orElseThrow();
        assertEquals(2, name.path("matched").asInt());
        assertEquals(2, name.path("transformed").asInt());
        var pic = counts.valueStream().filter(field -> field.path("field").asText().equals("vod_pic"))
                .findFirst().orElseThrow();
        assertEquals(1, pic.path("matched").asInt());
        assertEquals(1, pic.path("filtered").asInt());
        assertEquals(0, result.path("pages").get(1).path("changedIds").asInt());
        var multiText = replay.replay(input(xpathRule,
                holdout().replace(" four ", " f<b>ignored</b>our ")));
        assertFalse(multiText.path("passed").asBoolean());
        var multiCounts = multiText.path("pages").get(1).path("fieldCounts").valueStream()
                .filter(field -> field.path("field").asText().equals("vod_name")).findFirst().orElseThrow();
        assertEquals(3, multiCounts.path("matched").asInt());
        assertEquals(1, multiCounts.path("filtered").asInt());
    }
    @Test void xpathRejectsOutOfRowQueriesFunctionsDeepPathsAndAttributeContractChanges() {
        for (String selector : List.of("xpath://span", "xpath:/html/body/span", "xpath:../span",
                "xpath:ancestor::span", "xpath:.//span | .//b", "xpath:string(.//span)",
                "xpath:document('file.xml')", "xpath:.//span[129]", "xpath:.//span[0]",
                "xpath:.//a/b/c/d/e/f/g", "xpath:.//span//text()", "xpath:")) {
            assertEquals("EXTRACTION_SELECTOR_INVALID", assertThrows(IllegalArgumentException.class,
                    () -> replay.replay(input(rule(selector), holdout()))).getMessage(), selector);
        }
        assertEquals("EXTRACTION_XPATH_ATTRIBUTE_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> replay.replay(input(rule("xpath:.//span/@title"), holdout()))).getMessage());
        var valid = input(rule("span.title"), holdout());
        assertEquals("EXTRACTION_SELECTOR_INVALID", assertThrows(IllegalArgumentException.class,
                () -> replay.replay(new ExtractionReplayRequest(sha,
                        new ExtractionRule("xpath://article/text()", valid.rule().fields(), valid.rule().requiredFields()),
                        valid.pages(), Map.of()))).getMessage());
    }
    @Test void observedProductionXpathShapesWorkWithoutGlobalFieldQueries() {
        var rule = new ExtractionRule("xpath://*[@data-link]", Map.of(
                "vod_id", new ExtractionField("xpath:@data-link", "data-id", List.of()),
                "vod_name", new ExtractionField("xpath:.//a[contains(@href,'.html')]/text()", "text", List.of("trim"))),
                List.of("vod_id", "vod_name"));
        var fields = new LinkedHashMap<>(rule.fields());
        fields.put("vod_id", new ExtractionField("xpath:@data-id", "data-id", List.of()));
        rule = new ExtractionRule(rule.rowSelector(), fields, rule.requiredFields());
        String original = "<article data-link=x data-id=1><a href='/one.html'>one</a><a href='/other'>noise</a></article>";
        String holdout = "<article data-link=y data-id=2><a href='/other'>noise</a><a href='/two.html'>two</a></article>";
        var pages = List.of(page("original", original, List.of(Map.of("vod_id", "1", "vod_name", "one"))),
                page("holdout", holdout, List.of(Map.of("vod_id", "2", "vod_name", "two"))));
        assertTrue(replay.replay(new ExtractionReplayRequest(sha, rule, pages, Map.of())).path("passed").asBoolean());
        fields.put("vod_name", new ExtractionField("xpath:.//a[contains(@href,'/')]/text()", "text", List.of("trim")));
        assertFalse(replay.replay(new ExtractionReplayRequest(sha, new ExtractionRule(rule.rowSelector(),
                fields, rule.requiredFields()), pages, Map.of())).path("passed").asBoolean());
    }
    @Test void xpathHoldoutCannotResolveAnAmbiguousTrainingSelector() {
        String original = "<article class=card data-id=1><span class=title>one</span><b>one</b><span>noise</span></article>";
        String other = "<article class=card data-id=2><span class=title>WRONG</span><b>two</b><span>noise</span></article>";
        var fields = Map.of("vod_id", new ExtractionField("xpath:@data-id", "data-id", List.of()),
                "vod_name", new ExtractionField("xpath:.//span[@class='old']/text()", "text", List.of("trim")));
        var input = new ExtractionReplayRequest(sha, new ExtractionRule("xpath://article", fields,
                List.of("vod_id", "vod_name")), List.of(
                page("original", original, List.of(Map.of("vod_id", "1", "vod_name", "one"))),
                page("holdout", other, List.of(Map.of("vod_id", "2", "vod_name", "two")))), Map.of());
        assertEquals("SELECTOR_LEARNING_AMBIGUOUS_OR_UNVERIFIED", assertThrows(IllegalArgumentException.class,
                () -> replay.learn(new SelectorLearningRequest(input, "vod_name",
                        "xpath:.//span[@class='old']/text()"))).getMessage());
    }
    @Test void badShaNoHoldoutUnsafeSelectorOversizeAndDuplicateIdsFailClosed() {
        var valid = input(rule("span.title"), holdout());
        assertThrows(IllegalArgumentException.class, () -> replay.replay(new ExtractionReplayRequest(
                sha, valid.rule(), List.of(valid.pages().getFirst(), valid.pages().getFirst()), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> replay.replay(new ExtractionReplayRequest(
                sha, rule("span:contains(secret)"), valid.pages(), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> replay.replay(input(rule("span.title"), "x".repeat(262145))));
        assertThrows(IllegalArgumentException.class, () -> replay.replay(input(rule("span.title"),
                card("4", "four", "") + card("4", "four", ""))));
    }
    @Test void malformedFieldsAndDeclaredDependenciesFailWithBoundedValidationErrors() {
        var valid = input(rule("span.title"), holdout());
        var fields = new HashMap<>(valid.rule().fields());
        fields.put("vod_name", new ExtractionField("span.title", null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> replay.replay(new ExtractionReplayRequest(sha,
                new ExtractionRule("article.card", fields, List.of("vod_id", "vod_name")), valid.pages(), Map.of())));
        fields.put("vod_name", new ExtractionField("span.title", "text", Arrays.asList("trim", null)));
        assertThrows(IllegalArgumentException.class, () -> replay.replay(new ExtractionReplayRequest(sha,
                new ExtractionRule("article.card", fields, List.of("vod_id", "vod_name")), valid.pages(), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> replay.learn(null));
        var dependencies = new HashMap<String, String>();
        dependencies.put("parser", null);
        assertThrows(IllegalArgumentException.class, () -> replay.replay(new ExtractionReplayRequest(
                sha, valid.rule(), valid.pages(), dependencies)));
    }
    @Test void holdoutCannotChooseBetweenAmbiguousTrainingCandidates() {
        String original = "<article class=card data-id=1><span class=title>one</span><b>one</b><span>noise</span></article>";
        String other = "<article class=card data-id=2><span class=title>WRONG</span><b>two</b><span>noise</span></article>";
        var fields = Map.of("vod_id", new ExtractionField("", "data-id", List.of()),
                "vod_name", new ExtractionField("span.old", "text", List.of("trim")));
        var input = new ExtractionReplayRequest(sha, new ExtractionRule("article.card", fields,
                List.of("vod_id", "vod_name")), List.of(
                page("original", original, List.of(Map.of("vod_id", "1", "vod_name", "one"))),
                page("holdout", other, List.of(Map.of("vod_id", "2", "vod_name", "two")))), Map.of());
        assertEquals("SELECTOR_LEARNING_AMBIGUOUS_OR_UNVERIFIED", assertThrows(IllegalArgumentException.class,
                () -> replay.learn(new SelectorLearningRequest(input, "vod_name", "span.old"))).getMessage());
    }
}
