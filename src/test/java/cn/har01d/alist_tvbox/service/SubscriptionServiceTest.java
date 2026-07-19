package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionServiceTest {

    // ---- helpers ----
    private Map<String, Object> site(String key) {
        Map<String, Object> s = new HashMap<>();
        s.put("key", key);
        s.put("name", key);
        return s;
    }

    private Map<String, Object> parse(String name) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        return p;
    }

    private Map<String, Object> config(String... siteKeys) {
        List<Map<String, Object>> sites = new ArrayList<>();
        for (String k : siteKeys) sites.add(site(k));
        Map<String, Object> c = new HashMap<>();
        c.put("sites", sites);
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<String> siteKeys(Map<String, Object> c) {
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) c.get("sites")) keys.add((String) s.get("key"));
        return keys;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseNames(Map<String, Object> c) {
        List<String> names = new ArrayList<>();
        Object obj = c.get("parses");
        if (obj instanceof List) for (Map<String, Object> p : (List<Map<String, Object>>) obj) names.add((String) p.get("name"));
        return names;
    }

    private Map<String, Object> whitelist(String... keys) {
        return new HashMap<>(Map.of("sites-whitelist", new ArrayList<>(List.of(keys))));
    }

    private Map<String, Object> blacklistSites(String... keys) {
        Map<String, Object> bl = new HashMap<>();
        bl.put("sites", new ArrayList<>(List.of(keys)));
        return new HashMap<>(Map.of("blacklist", bl));
    }

    // ---- truth table ----

    @Test
    void subscriptionWhitelistWinsAndIgnoresEverything() {
        Map<String, Object> c = config("A", "B", "C");
        SubscriptionService.resolveAndApplyFilters(c, blacklistSites("A"), whitelist("A"));
        assertThat(siteKeys(c)).containsExactly("A");
        assertThat(c).doesNotContainKeys("sites-whitelist", "sites-blacklist", "blacklist");
    }

    @Test
    void subscriptionBlacklistReplacesGlobal() {
        Map<String, Object> c = config("A", "B", "C");
        SubscriptionService.resolveAndApplyFilters(c, blacklistSites("A"), blacklistSites("B"));
        assertThat(siteKeys(c)).containsExactlyInAnyOrder("A", "C"); // global A ignored, sub removes B
    }

    @Test
    void globalWhitelistUsedWhenNoSubscriptionFilter() {
        Map<String, Object> c = config("A", "B", "C");
        SubscriptionService.resolveAndApplyFilters(c, whitelist("A", "B"), new HashMap<>());
        assertThat(siteKeys(c)).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void globalBlacklistUsedWhenNoSubscriptionFilter() {
        Map<String, Object> c = config("A", "B", "C");
        SubscriptionService.resolveAndApplyFilters(c, blacklistSites("B"), new HashMap<>());
        assertThat(siteKeys(c)).containsExactlyInAnyOrder("A", "C");
    }

    @Test
    void subscriptionWhitelistBeatsSubscriptionBlacklist() {
        Map<String, Object> c = config("A", "B");
        Map<String, Object> override = whitelist("A");
        override.putAll(blacklistSites("A")); // both whitelist + blacklist present
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), override);
        assertThat(siteKeys(c)).containsExactly("A");
    }

    @Test
    void emptyFiltersStillRemoveAlist1() {
        Map<String, Object> c = config("A", "Alist1", "B");
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), new HashMap<>());
        assertThat(siteKeys(c)).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void legacyTopLevelSitesBlacklistIsHonored() {
        Map<String, Object> c = config("A", "B");
        Map<String, Object> override = new HashMap<>();
        override.put("sites-blacklist", new ArrayList<>(List.of("B")));
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), override);
        assertThat(siteKeys(c)).containsExactly("A");
    }

    @Test
    void blacklistParsesRemovedByName() {
        Map<String, Object> c = config("A");
        c.put("parses", new ArrayList<>(List.of(parse("虾米"), parse("Json"))));
        Map<String, Object> bl = new HashMap<>();
        bl.put("parses", new ArrayList<>(List.of("虾米")));
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), new HashMap<>(Map.of("blacklist", bl)));
        assertThat(parseNames(c)).containsExactly("Json");
    }

    @Test
    void whitelistModeIgnoresParseBlacklist() {
        Map<String, Object> c = config("A", "B");
        c.put("parses", new ArrayList<>(List.of(parse("虾米"))));
        Map<String, Object> override = whitelist("A");
        Map<String, Object> bl = new HashMap<>();
        bl.put("parses", new ArrayList<>(List.of("虾米")));
        override.put("blacklist", bl);
        SubscriptionService.resolveAndApplyFilters(c, new HashMap<>(), override);
        assertThat(siteKeys(c)).containsExactly("A");
        assertThat(parseNames(c)).containsExactly("虾米"); // parse NOT removed in whitelist mode
    }

    @Test
    void buildCatalogTagsUpstreamBuiltinPluginAndMapsPushKey() {
        Map<String, Object> c = config("csp_Bili"); // upstream
        c.put("parses", new ArrayList<>(List.of(parse("虾米"))));

        Plugin plugin = new Plugin();
        plugin.setName("我的插件");

        List<SubscriptionSourceService.SubscriptionSourceRef> sources = List.of(
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_AList", true, "csp_AList", "🟢 AList", null),
                new SubscriptionSourceService.SubscriptionSourceRef("builtin-csp_Push", true, "csp_Push", "推送", null),
                new SubscriptionSourceService.SubscriptionSourceRef("plugin-1", false, "我的插件", "我的插件", plugin)
        );

        Map<String, Object> catalog = SubscriptionService.buildCatalog(c, sources);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sites = (List<Map<String, Object>>) catalog.get("sites");
        assertThat(sites).anySatisfy(s -> assertThat(s).containsEntry("key", "csp_Bili").containsEntry("origin", "upstream"));
        assertThat(sites).anySatisfy(s -> assertThat(s).containsEntry("key", "csp_AList").containsEntry("origin", "builtin"));
        assertThat(sites).anySatisfy(s -> assertThat(s).containsEntry("key", "push_agent").containsEntry("origin", "builtin"));
        assertThat(sites).anySatisfy(s -> assertThat(s).containsEntry("key", "我的插件").containsEntry("origin", "plugin"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parses = (List<Map<String, Object>>) catalog.get("parses");
        assertThat(parses).extracting(p -> p.get("name")).containsExactly("虾米");
    }

    @Test
    void rawPythonPluginShouldUsePyProxyLoaderAndLocalProxyInEveryRunMode() {
        Plugin plugin = new Plugin();
        plugin.setId(7);
        plugin.setUrl("https://example.com/demo.py?raw=1");
        plugin.setExtend("{\"site\":\"demo\"}");
        Map<String, Object> localProxyConfig = new HashMap<>();
        localProxyConfig.put("ALI", Map.of("enabled", true));

        Map<String, Object> payload = SubscriptionService.buildPluginExtPayload(
                plugin, "http://atv", "web", "vod-token", "secret", true, localProxyConfig);

        assertThat(SubscriptionService.selectPluginApi(plugin, true, "http://atv"))
                .isEqualTo("csp_PyProxy");
        assertThat(SubscriptionService.selectPluginApi(plugin, false, "http://atv"))
                .isEqualTo("csp_PyProxy");
        assertThat(payload)
                .containsEntry("loader", "http://atv/Atvp.py")
                .containsEntry("source", "http://atv/plugins/web/7.py")
                .containsEntry("raw", true)
                .containsEntry("api", "http://atv")
                .containsEntry("token", "vod-token")
                .containsEntry("secret", "secret")
                .containsEntry("data", "{\"site\":\"demo\"}")
                .containsEntry("local_proxy_config", localProxyConfig);
        assertThat(payload.get("loader")).isNotEqualTo("http://atv/plugins/web/7.py");
    }

    @Test
    void encryptedTxtPluginShouldKeepSourceAndNativePythonModeBehavior() {
        Plugin plugin = new Plugin();
        plugin.setId(8);
        plugin.setUrl("https://example.com/demo.txt");
        Map<String, Object> localProxyConfig = new HashMap<>();
        localProxyConfig.put("ALI", Map.of("enabled", true));

        Map<String, Object> payload = SubscriptionService.buildPluginExtPayload(
                plugin, "http://atv", "web", "", "secret", true, localProxyConfig);

        assertThat(SubscriptionService.selectPluginApi(plugin, true, "http://atv"))
                .isEqualTo("http://atv/Atvp.py");
        assertThat(SubscriptionService.selectPluginApi(plugin, false, "http://atv"))
                .isEqualTo("csp_PyProxy");
        assertThat(payload)
                .containsEntry("source", "http://atv/plugins/web/8.txt")
                .containsEntry("token", "-")
                .doesNotContainKey("loader");
        assertThat(payload.get("local_proxy_config"))
                .isEqualTo(new HashMap<>());

        Map<String, Object> javaPayload = SubscriptionService.buildPluginExtPayload(
                plugin, "http://atv", "web", "", "secret", false, localProxyConfig);
        assertThat(javaPayload.get("local_proxy_config")).isEqualTo(localProxyConfig);
    }
}
