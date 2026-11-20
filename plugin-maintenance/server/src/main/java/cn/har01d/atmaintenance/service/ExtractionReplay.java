package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ExtractionReplay {
    private final ObjectMapper mapper;
    private static final Set<String> FIELDS = Set.of("vod_id", "vod_name", "vod_pic", "vod_remarks",
            "vod_play_from", "vod_play_url");
    private static final Set<String> TRANSFORMS = Set.of("trim", "collapseWhitespace");
    private static final String XPATH_ATTRIBUTE = "[a-z][a-z0-9-]{0,63}";
    private static final String XPATH_VALUE = "(?:'[-A-Za-z0-9_./:?&=%# ]{1,96}'|\"[-A-Za-z0-9_./:?&=%# ]{1,96}\")";
    private static final String XPATH_STEP =
            "(?:[a-z][a-z0-9-]*|\\*)(?:\\[(?:@" + XPATH_ATTRIBUTE + "(?:\\s*=\\s*" + XPATH_VALUE + ")?"
                    + "|contains\\(@" + XPATH_ATTRIBUTE + ",\\s*" + XPATH_VALUE + "\\))\\])?"
                    + "(?:\\[(?:[1-9]|[1-9][0-9]|1[01][0-9]|12[0-8])\\])?";
    private static final Pattern XPATH_PATH = Pattern.compile(XPATH_STEP + "(?://?" + XPATH_STEP + "){0,5}");

    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }

    static boolean xpath(String value) {
        return value != null && value.startsWith("xpath:");
    }

    static String expression(String value) {
        return xpath(value) ? value.substring(6) : value;
    }

    private static String terminal(String expression) {
        if (expression.equals("text()") || expression.matches("@" + XPATH_ATTRIBUTE)) return expression;
        int slash = expression.lastIndexOf('/');
        String last = expression.substring(slash + 1);
        return last.equals("text()") || last.matches("@" + XPATH_ATTRIBUTE) ? last : "";
    }

    private static void selector(String value, boolean field) {
        require(value != null && value.length() <= 160, "EXTRACTION_SELECTOR_INVALID");
        if (xpath(value)) {
            String path = expression(value);
            require(!path.isEmpty() && (field || terminal(path).isEmpty())
                    && (!field || !path.startsWith("/")), "EXTRACTION_SELECTOR_INVALID");
            String end = terminal(path);
            if (!end.isEmpty()) path = path.equals(end) ? "." : path.substring(0, path.length() - end.length() - 1);
            if (path.equals(".") && field) return;
            path = path.replaceFirst("^(?:\\.//|\\./|//|/)", "");
            require(XPATH_PATH.matcher(path).matches(), "EXTRACTION_SELECTOR_INVALID");
            return;
        }
        require(field && value.isEmpty() || value.matches(
                "[a-z][a-z0-9-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]{0,63})?(?: > [a-z][a-z0-9-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]{0,63})?){0,3}"),
                "EXTRACTION_SELECTOR_INVALID");
    }

    private static List<Element> elements(Element context, String selector) {
        if (selector.isEmpty()) return List.of(context);
        return xpath(selector) ? context.selectXpath(expression(selector)) : context.select(selector);
    }

    private static List<String> values(Element row, ExtractionField field) {
        if (xpath(field.selector())) {
            String path = expression(field.selector());
            String end = terminal(path);
            if (end.equals("text()"))
                return row.selectXpath(path, TextNode.class).stream().map(TextNode::getWholeText).toList();
            if (end.startsWith("@")) {
                String parent = path.equals(end) ? "." : path.substring(0, path.length() - end.length() - 1);
                String attribute = end.substring(1);
                return row.selectXpath(parent).stream().filter(node -> node.hasAttr(attribute))
                        .map(node -> node.attr(attribute)).toList();
            }
        }
        return elements(row, field.selector()).stream().map(node -> field.attribute().equals("text")
                ? node.wholeText() : node.attr(field.attribute())).toList();
    }

    public ObjectNode replay(ExtractionReplayRequest input) {
        validate(input);
        return evaluate(input, false);
    }

    private ObjectNode evaluate(ExtractionReplayRequest input, boolean originalOnly) {
        ExtractionRule rule = input.rule();
        var report = mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "host_bounded_rule_extraction").put("artifactSha256", input.expectedArtifactSha256())
                .put("ruleSha256", Hashes.sha256(json(rule))).put("dependencySha256", Hashes.sha256(json(input.dependencySha256())))
                .put("publishEligible", false).put("sourceExecutionObserved", false)
                .put("sourceRuleBindingVerified", false).put("dependenciesVerified", false)
                .put("expectedValuesAuthority", "operator_supplied");
        var pages = report.putArray("pages");
        boolean allPassed = true;
        for (ExtractionPage page : input.pages()) {
            if (originalOnly && !page.role().equals("original")) continue;
            var document = Jsoup.parse(page.html());
            require(document.getAllElements().size() <= 8192, "EXTRACTION_DOM_LIMIT");
            var rows = elements(document, rule.rowSelector());
            require(rows.size() <= 128, "EXTRACTION_ROW_LIMIT");
            var observed = new LinkedHashMap<String, Map<String, String>>();
            var counts = new LinkedHashMap<String, int[]>();
            rule.fields().keySet().stream().sorted().forEach(key -> counts.put(key, new int[4]));
            var result = pages.addObject().put("role", page.role()).put("htmlSha256", page.htmlSha256())
                    .put("expectedSha256", Hashes.sha256(json(page.expected())))
                    .put("matchedRows", rows.size());
            var trace = result.putArray("items");
            int filtered = 0;
            for (Element row : rows) {
                var values = new LinkedHashMap<String, String>();
                var item = mapper.createObjectNode();
                var fieldTrace = item.putArray("fields");
                boolean keep = true;
                for (String key : counts.keySet()) {
                    ExtractionField field = rule.fields().get(key);
                    var matches = values(row, field);
                    require(matches.size() <= 128, "EXTRACTION_FIELD_MATCH_LIMIT");
                    int[] count = counts.get(key);
                    count[0] += matches.size();
                    String raw = matches.size() == 1 ? matches.getFirst() : "";
                    require(raw.length() <= 8192, "EXTRACTION_FIELD_LIMIT");
                    String value = raw;
                    for (String transform : field.transforms())
                        value = transform.equals("trim") ? value.strip() : value.replaceAll("\\s+", " ");
                    boolean changed = !raw.equals(value), accepted = matches.size() == 1 && !value.isBlank();
                    if (changed) count[1]++;
                    if (!accepted) count[2]++;
                    if (accepted) count[3]++;
                    if (!accepted && rule.requiredFields().contains(key)) keep = false;
                    values.put(key, value);
                    fieldTrace.addObject().put("field", key).put("matches", matches.size())
                            .put("transformed", changed).put("filtered", !accepted)
                            .put("valueSha256", Hashes.sha256(value));
                }
                item.put("itemIdSha256", Hashes.sha256(values.get("vod_id"))).put("kept", keep);
                trace.add(item);
                if (keep) require(observed.putIfAbsent(values.get("vod_id"), values) == null,
                        "EXTRACTION_DUPLICATE_ID");
                else filtered++;
            }
            var expected = new LinkedHashMap<String, Map<String, String>>();
            for (var row : page.expected())
                require(expected.putIfAbsent(row.get("vod_id"), row) == null, "EXTRACTION_EXPECTED_DUPLICATE_ID");
            boolean passed = observed.equals(expected);
            var statistics = result.putArray("fieldCounts");
            counts.forEach((key, count) -> statistics.addObject().put("field", key).put("matched", count[0])
                    .put("transformed", count[1]).put("filtered", count[2]).put("kept", count[3]));
            result.put("filteredRows", filtered).put("keptRows", observed.size()).put("passed", passed)
                    .put("missingIds", expected.keySet().stream().filter(key -> !observed.containsKey(key)).count())
                    .put("unexpectedIds", observed.keySet().stream().filter(key -> !expected.containsKey(key)).count())
                    .put("changedIds", expected.keySet().stream()
                            .filter(key -> observed.containsKey(key) && !expected.get(key).equals(observed.get(key))).count());
            allPassed &= passed;
        }
        return report.put("passed", allPassed);
    }

    public List<ExtractionRule> learn(SelectorLearningRequest input) {
        require(input != null, "SELECTOR_LEARNING_INPUT_INVALID");
        validate(input.replay());
        require(input.field() != null && input.replay().rule().fields().containsKey(input.field())
                && Objects.equals(input.oldSelector(), input.replay().rule().fields().get(input.field()).selector()),
                "SELECTOR_LEARNING_FIELD_INVALID");
        require(!replay(input.replay()).path("passed").asBoolean(), "SELECTOR_LEARNING_NO_FAILURE");
        var training = input.replay().pages().stream().filter(page -> page.role().equals("original")).findFirst().orElseThrow();
        var candidates = new TreeSet<String>();
        boolean xpath = xpath(input.oldSelector());
        String end = xpath ? terminal(expression(input.oldSelector())) : "";
        String suffix = end.isEmpty() ? "" : "/" + end;
        for (Element row : elements(Jsoup.parse(training.html()), input.replay().rule().rowSelector())) {
            for (Element element : row.getAllElements()) {
                if (element == row) continue;
                if (!element.normalName().matches("[a-z][a-z0-9-]*")) continue;
                candidates.add(xpath ? "xpath:.//" + element.normalName() + suffix : element.normalName());
                for (String className : element.classNames())
                    if (className.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")) {
                        String candidate = xpath ? "xpath:.//" + element.normalName()
                                + "[contains(@class,'" + className + "')]" + suffix
                                : element.normalName() + "." + className;
                        if (candidate.length() <= 160) candidates.add(candidate);
                    }
                require(candidates.size() <= 64, "SELECTOR_LEARNING_CANDIDATE_LIMIT");
            }
        }
        var accepted = new ArrayList<ExtractionRule>();
        for (String selector : candidates) {
            if (selector.equals(input.oldSelector())) continue;
            var fields = new LinkedHashMap<>(input.replay().rule().fields());
            var previous = fields.get(input.field());
            fields.put(input.field(), new ExtractionField(selector, previous.attribute(), previous.transforms()));
            var rule = new ExtractionRule(input.replay().rule().rowSelector(), fields, input.replay().rule().requiredFields());
            var replay = new ExtractionReplayRequest(input.replay().expectedArtifactSha256(), rule,
                    input.replay().pages(), input.replay().dependencySha256());
            var original = evaluate(replay, true).path("pages").valueStream()
                    .filter(page -> page.path("role").asText().equals("original")).findFirst().orElseThrow();
            if (original.path("passed").asBoolean()) accepted.add(rule);
        }
        require(accepted.size() == 1, "SELECTOR_LEARNING_AMBIGUOUS_OR_UNVERIFIED");
        require(replay(new ExtractionReplayRequest(input.replay().expectedArtifactSha256(), accepted.getFirst(),
                input.replay().pages(), input.replay().dependencySha256())).path("passed").asBoolean(),
                "SELECTOR_HOLDOUT_FAILED");
        return accepted;
    }

    private void validate(ExtractionReplayRequest input) {
        require(input != null && input.expectedArtifactSha256() != null
                && input.expectedArtifactSha256().matches("[a-f0-9]{64}") && input.rule() != null,
                "EXTRACTION_INPUT_INVALID");
        var rule = input.rule();
        selector(rule.rowSelector(), false);
        require(rule.fields() != null && rule.fields().keySet().containsAll(Set.of("vod_id", "vod_name"))
                && rule.fields().keySet().stream().allMatch(Objects::nonNull)
                && FIELDS.containsAll(rule.fields().keySet()) && rule.requiredFields() != null
                && rule.requiredFields().stream().allMatch(Objects::nonNull)
                && rule.requiredFields().containsAll(Set.of("vod_id", "vod_name"))
                && rule.fields().keySet().containsAll(rule.requiredFields())
                && new HashSet<>(rule.requiredFields()).size() == rule.requiredFields().size(),
                "EXTRACTION_RULE_INVALID");
        for (ExtractionField field : rule.fields().values()) {
            require(field != null && field.attribute() != null
                    && Set.of("text", "href", "src", "data-id", "data-src", "title").contains(field.attribute())
                    && field.transforms() != null && field.transforms().size() <= 2
                    && field.transforms().stream().allMatch(Objects::nonNull)
                    && TRANSFORMS.containsAll(field.transforms()), "EXTRACTION_FIELD_INVALID");
            selector(field.selector(), true);
            if (xpath(field.selector())) {
                String end = terminal(expression(field.selector()));
                require(end.isEmpty() || end.equals("text()") && field.attribute().equals("text")
                        || end.equals("@" + field.attribute()), "EXTRACTION_XPATH_ATTRIBUTE_MISMATCH");
            }
        }
        require(input.pages() != null && input.pages().size() >= 2 && input.pages().size() <= 5
                && input.pages().stream().filter(page -> page != null && "original".equals(page.role())).count() == 1,
                "EXTRACTION_HOLDOUT_REQUIRED");
        var hashes = new HashSet<String>();
        int total = 0;
        for (ExtractionPage page : input.pages()) {
            require(page != null && page.role() != null && Set.of("original", "holdout").contains(page.role())
                    && page.html() != null && page.html().length() <= 262144
                    && page.html().getBytes(StandardCharsets.UTF_8).length <= 262144
                    && Hashes.sha256(page.html()).equals(page.htmlSha256()) && hashes.add(page.htmlSha256())
                    && page.expected() != null && !page.expected().isEmpty()
                    && page.expected().size() <= 128, "EXTRACTION_PAGE_INVALID");
            total += page.html().getBytes(StandardCharsets.UTF_8).length;
            for (var row : page.expected())
                require(row != null && row.keySet().equals(rule.fields().keySet())
                        && row.values().stream().allMatch(value -> value != null && value.length() <= 8192)
                        && !row.get("vod_id").isBlank() && !row.get("vod_name").isBlank(),
                        "EXTRACTION_EXPECTED_INVALID");
        }
        require(total <= 524288 && input.dependencySha256() != null && input.dependencySha256().size() <= 16
                && input.dependencySha256().entrySet().stream().allMatch(entry ->
                    entry.getKey() != null && entry.getKey().matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")
                    && entry.getValue() != null && entry.getValue().matches("[a-f0-9]{64}")),
                "EXTRACTION_DEPENDENCY_INVALID");
    }

    private String json(Object value) {
        try { return mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("EXTRACTION_JSON_INVALID"); }
    }
}
